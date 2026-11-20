package vn.greenmart.sales.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.branch.dto.BranchSaleInfo;
import vn.greenmart.branch.service.BranchService;
import vn.greenmart.catalog.dto.ProductSaleInfo;
import vn.greenmart.catalog.service.ProductService;
import vn.greenmart.common.audit.AuditLog;
import vn.greenmart.common.audit.AuditLogRepository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.ValidationErrorDetail;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.identity.service.AuthService;
import vn.greenmart.integration.outbox.OutboxService;
import vn.greenmart.inventory.service.InventoryService;
import vn.greenmart.sales.domain.*;
import vn.greenmart.sales.dto.*;
import vn.greenmart.sales.repository.OrderItemRepository;
import vn.greenmart.sales.repository.PaymentRepository;
import vn.greenmart.sales.repository.ReturnDocumentRepository;
import vn.greenmart.sales.repository.ReturnItemRepository;
import vn.greenmart.sales.repository.SalesOrderRepository;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnServiceImpl implements ReturnService {

    private static final int RETURN_WINDOW_DAYS = 7;

    private final ReturnDocumentRepository returnDocumentRepository;
    private final ReturnItemRepository returnItemRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final InventoryService inventoryService;
    private final BranchService branchService;
    private final ProductService productService;
    private final AuthService authService;
    private final AuditLogRepository auditLogRepository;
    private final OutboxService outboxService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReturnResponse createReturn(CreateReturnRequest request) {
        SecurityUtils.requireRole("CASHIER", "BRANCH_MANAGER", "OWNER");
        Long actorId = requireCurrentUser();
        requireBranchAccess(request.branchId());
        BranchSaleInfo branchInfo = branchService.getSaleInfo(request.branchId());

        // Khóa đơn hàng gốc để tránh tạo đồng thời nhiều phiếu trả vượt quá số lượng đã mua
        SalesOrder order = lockOrderForUpdate(request.orderId());
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new AppException(
                    ErrorCode.INVALID_STATUS,
                    "Chỉ được lập phiếu trả cho đơn hàng đã hoàn tất (COMPLETED)",
                    Map.of("currentStatus", order.getStatus().name(), "allowed", List.of("COMPLETED"))
            );
        }

        // [SỬA THEO REVIEW #410]: Quá hạn 7 ngày trả 422 RETURN_WINDOW_EXPIRED
        Instant now = Instant.now();
        if (order.getCompletedAt() == null || now.isAfter(order.getCompletedAt().plus(RETURN_WINDOW_DAYS, ChronoUnit.DAYS))) {
            throw new AppException(
                    ErrorCode.RETURN_WINDOW_EXPIRED,
                    "Đơn hàng đã quá thời hạn trả hàng 7 ngày kể từ khi hoàn tất",
                    List.of(new ValidationErrorDetail("orderId", "Đã quá hạn trả hàng 7 ngày (BR-10)"))
            );
        }

        List<OrderItem> orderItems = orderItemRepository.findByOrderId(order.getId());
        Map<Long, OrderItem> orderItemMap = orderItems.stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));

        // Tính tổng số lượng đã trả hoặc đang nằm trên phiếu DRAFT khác của đơn
        Map<Long, Long> alreadyReturnedMap = returnItemRepository
                .sumReturnedQuantitiesByOrder(order.getId(), List.of(ReturnStatus.DRAFT, ReturnStatus.APPROVED), null)
                .stream()
                .collect(Collectors.toMap(
                        ReturnItemRepository.ReturnedQtyProjection::getOrderItemId,
                        ReturnItemRepository.ReturnedQtyProjection::getTotalQuantity
                ));

        Set<Long> seenOrderItemIds = new HashSet<>();
        for (int i = 0; i < request.items().size(); i++) {
            ReturnItemRequest itemReq = request.items().get(i);
            if (!seenOrderItemIds.add(itemReq.orderItemId())) {
                throw new AppException(
                        ErrorCode.VALIDATION_ERROR,
                        "Mỗi dòng sản phẩm trong đơn chỉ được xuất hiện một lần trên phiếu trả",
                        List.of(new ValidationErrorDetail("items[" + i + "].orderItemId", "Dòng sản phẩm bị trùng"))
                );
            }
            OrderItem oi = orderItemMap.get(itemReq.orderItemId());
            if (oi == null) {
                throw new AppException(
                        ErrorCode.VALIDATION_ERROR,
                        "Dòng sản phẩm không thuộc đơn hàng gốc",
                        List.of(new ValidationErrorDetail("items[" + i + "].orderItemId", "Không thuộc đơn hàng " + order.getCode()))
                );
            }
            // [SỬA THEO REVIEW #410]: Vượt số lượng còn lại trả 422 RETURN_QUANTITY_EXCEEDED kèm details items[i].quantity
            long alreadyReturned = alreadyReturnedMap.getOrDefault(oi.getId(), 0L);
            long remainingQty = oi.getQuantity() - alreadyReturned;
            if (itemReq.quantity() > remainingQty) {
                throw new AppException(
                        ErrorCode.RETURN_QUANTITY_EXCEEDED,
                        "Số lượng trả vượt quá số lượng còn lại có thể trả (" + remainingQty + ")",
                        List.of(new ValidationErrorDetail("items[" + i + "].quantity", "Tối đa được trả " + remainingQty))
                );
            }
        }

        // Tính tiền hoàn theo tỷ lệ phân bổ KM cấp đơn (BR-10 & BA mục 5.2), làm tròn xuống đến đồng VND
        long totalRefundAmount = 0L;
        for (ReturnItemRequest itemReq : request.items()) {
            OrderItem oi = orderItemMap.get(itemReq.orderItemId());
            long lineRefund = calculateLineRefund(order, orderItems, oi, itemReq.quantity());
            totalRefundAmount = Math.addExact(totalRefundAmount, lineRefund);
        }

        // [SỬA THEO REVIEW #410]: Dùng bảng bộ đếm riêng return_sequence (V18) thay cho order_sequence
        String branchCodeClean = branchInfo.code().replace("-", "");
        LocalDate localDate = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        String dateStr = localDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Long seq = jdbcTemplate.queryForObject(
                "INSERT INTO return_sequence (branch_id, return_date, seq) VALUES (?, ?, 1) "
                        + "ON CONFLICT (branch_id, return_date) DO UPDATE SET seq = return_sequence.seq + 1 RETURNING seq",
                Long.class, request.branchId(), localDate
        );
        String code = String.format("RET-%s-%s-%04d", branchCodeClean, dateStr, seq);

        ReturnDocument doc = new ReturnDocument();
        doc.setCode(code);
        doc.setOrderId(order.getId());
        doc.setBranchId(request.branchId());
        doc.setReason(request.reason().trim());
        doc.setRefundAmount(totalRefundAmount);
        doc.setStatus(ReturnStatus.DRAFT);
        doc.setCreatedBy(actorId);
        doc.setCreatedAt(now);

        for (ReturnItemRequest itemReq : request.items()) {
            ReturnItem ri = new ReturnItem();
            ri.setOrderItemId(itemReq.orderItemId());
            ri.setQuantity(itemReq.quantity());
            ri.setCondition(itemReq.condition());
            doc.addItem(ri);
        }

        ReturnDocument saved = returnDocumentRepository.saveAndFlush(doc);
        return mapToResponse(saved, order, orderItems);
    }

    @Override
    @Transactional(readOnly = true)
    public ReturnResponse getReturn(Long returnId) {
        // [SỬA THEO REVIEW #410]: Kiểm tra vai trò theo ma trận quyền BA mục 2
        SecurityUtils.requireRole("CASHIER", "BRANCH_MANAGER", "OWNER");
        ReturnDocument doc = returnDocumentRepository.findById(returnId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy phiếu trả hàng"));
        requireBranchAccess(doc.getBranchId());

        SalesOrder order = salesOrderRepository.findById(doc.getOrderId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn hàng gốc"));
        List<OrderItem> orderItems = orderItemRepository.findByOrderId(order.getId());
        return mapToResponse(doc, order, orderItems);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReturnResponse> listReturnsByOrder(Long orderId) {
        // [SỬA THEO REVIEW #410]: Kiểm tra vai trò theo ma trận quyền BA mục 2
        SecurityUtils.requireRole("CASHIER", "BRANCH_MANAGER", "OWNER");
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn hàng"));
        List<OrderItem> orderItems = orderItemRepository.findByOrderId(order.getId());
        List<Long> accessibleBranches = SecurityUtils.getAccessibleBranchIds(jdbcTemplate);

        return returnDocumentRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .filter(doc -> accessibleBranches == null || accessibleBranches.contains(doc.getBranchId()))
                .map(doc -> mapToResponse(doc, order, orderItems))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReturnResponse approveReturn(Long returnId) {
        SecurityUtils.requireRole("BRANCH_MANAGER", "OWNER");
        Long actorId = requireCurrentUser();

        ReturnDocument doc = returnDocumentRepository.findByIdForUpdate(returnId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy phiếu trả hàng"));
        requireBranchAccess(doc.getBranchId());

        if (doc.getStatus() != ReturnStatus.DRAFT) {
            throw new AppException(
                    ErrorCode.INVALID_STATUS,
                    "Chỉ duyệt được phiếu trả ở trạng thái DRAFT",
                    Map.of("currentStatus", doc.getStatus().name(), "allowed", List.of("DRAFT"))
            );
        }

        // BR-13: Tách biệt người lập và người duyệt (SoD)
        if (Objects.equals(doc.getCreatedBy(), actorId)) {
            throw new AppException(ErrorCode.SELF_APPROVAL_FORBIDDEN, "Người lập phiếu không được tự duyệt phiếu của mình");
        }

        SalesOrder order = lockOrderForUpdate(doc.getOrderId());
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new AppException(
                    ErrorCode.INVALID_STATUS,
                    "Đơn hàng gốc không còn ở trạng thái COMPLETED",
                    Map.of("currentStatus", order.getStatus().name(), "allowed", List.of("COMPLETED"))
            );
        }

        // [SỬA THEO REVIEW #410]: Quá hạn 7 ngày trả 422 RETURN_WINDOW_EXPIRED
        if (order.getCompletedAt() == null
                || doc.getCreatedAt().isAfter(order.getCompletedAt().plus(RETURN_WINDOW_DAYS, ChronoUnit.DAYS))) {
            throw new AppException(ErrorCode.RETURN_WINDOW_EXPIRED, "Phiếu trả vượt quá thời hạn 7 ngày kể từ khi hoàn tất đơn");
        }

        List<OrderItem> orderItems = orderItemRepository.findByOrderId(order.getId());
        Map<Long, OrderItem> orderItemMap = orderItems.stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));

        // Kiểm tra lại số lượng đã duyệt trước đó để đảm bảo không vượt số mua
        Map<Long, Long> approvedBeforeMap = returnItemRepository
                .sumReturnedQuantitiesByOrder(order.getId(), List.of(ReturnStatus.APPROVED), doc.getId())
                .stream()
                .collect(Collectors.toMap(
                        ReturnItemRepository.ReturnedQtyProjection::getOrderItemId,
                        ReturnItemRepository.ReturnedQtyProjection::getTotalQuantity
                ));

        record StockAdjustment(Long productId, int quantity, boolean resellable) {}
        List<StockAdjustment> adjustments = new ArrayList<>();

        int idx = 0;
        for (ReturnItem item : doc.getItems()) {
            OrderItem oi = orderItemMap.get(item.getOrderItemId());
            if (oi == null) {
                throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy dòng đơn hàng gốc");
            }
            long alreadyApproved = approvedBeforeMap.getOrDefault(oi.getId(), 0L);
            long remainingQty = oi.getQuantity() - alreadyApproved;
            // [SỬA THEO REVIEW #410]: Vượt số lượng trả trả 422 RETURN_QUANTITY_EXCEEDED
            if (item.getQuantity() > remainingQty) {
                throw new AppException(
                        ErrorCode.RETURN_QUANTITY_EXCEEDED,
                        "Số lượng trả vượt quá số lượng còn lại của đơn hàng",
                        List.of(new ValidationErrorDetail("items[" + idx + "].quantity", "Tối đa được trả " + remainingQty))
                );
            }
            adjustments.add(new StockAdjustment(
                    oi.getProductId(),
                    item.getQuantity(),
                    item.getCondition() == ReturnItemCondition.RESELLABLE
            ));
            idx++;
        }

        // Sắp xếp theo productId ASC để tránh deadlock khi cập nhật kho (BR-01)
        adjustments.sort(Comparator.comparing(StockAdjustment::productId));
        Long branchId = doc.getBranchId();
        for (StockAdjustment adj : adjustments) {
            inventoryService.returnStock(branchId, adj.productId(), adj.quantity(), adj.resellable(), returnId, actorId);
        }

        // EntityManager đã được clear sau native bulk update của InventoryService -> nạp lại entity trước khi cập nhật
        doc = returnDocumentRepository.findById(returnId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy phiếu trả hàng"));
        order = salesOrderRepository.findById(doc.getOrderId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn hàng gốc"));

        Instant approvedAt = Instant.now();
        if (approvedAt.isBefore(doc.getCreatedAt())) {
            approvedAt = doc.getCreatedAt();
        }
        doc.setStatus(ReturnStatus.APPROVED);
        doc.setApprovedBy(actorId);
        doc.setApprovedAt(approvedAt);
        returnDocumentRepository.saveAndFlush(doc);

        // Kiểm tra nếu đã hoàn trả đủ 100% toàn bộ dòng của đơn -> chuyển đơn sang RETURNED
        Map<Long, Long> totalApprovedAfter = returnItemRepository
                .sumReturnedQuantitiesByOrder(order.getId(), List.of(ReturnStatus.APPROVED), null)
                .stream()
                .collect(Collectors.toMap(
                        ReturnItemRepository.ReturnedQtyProjection::getOrderItemId,
                        ReturnItemRepository.ReturnedQtyProjection::getTotalQuantity
                ));

        boolean fullyReturned = orderItems.stream()
                .allMatch(oi -> totalApprovedAfter.getOrDefault(oi.getId(), 0L) >= oi.getQuantity());

        if (fullyReturned) {
            order.setStatus(OrderStatus.RETURNED);
            salesOrderRepository.save(order);

            // Hoàn tiền giả lập toàn bộ payment nếu đơn trả hết 100%
            List<Payment> payments = paymentRepository.findByOrderId(order.getId());
            for (Payment p : payments) {
                if (p.getStatus() == PaymentStatus.PAID) {
                    p.setStatus(PaymentStatus.REFUNDED);
                    paymentRepository.save(p);
                }
            }
        }

        // Ghi audit_log (AUTH-03)
        AuditLog audit = new AuditLog();
        audit.setUserId(actorId);
        audit.setAction("APPROVE_RETURN_DOCUMENT");
        audit.setEntity("return_document");
        audit.setEntityId(doc.getId().toString());
        audit.setDetail(Map.of(
                "orderId", order.getId(),
                "branchId", doc.getBranchId(),
                "refundAmount", doc.getRefundAmount(),
                "fullyReturned", fullyReturned
        ));
        auditLogRepository.save(audit);

        // Ghi outbox sự kiện trả hàng (BR-18 / đồng bộ)
        Map<String, Object> outboxPayload = new LinkedHashMap<>();
        outboxPayload.put("returnId", doc.getId());
        outboxPayload.put("code", doc.getCode());
        outboxPayload.put("orderId", order.getId());
        outboxPayload.put("branchId", doc.getBranchId());
        outboxPayload.put("refundAmount", doc.getRefundAmount());
        outboxPayload.put("approvedAt", approvedAt.toString());
        outboxPayload.put("fullyReturned", fullyReturned);
        outboxService.enqueue("return", doc.getId().toString(), "return.approved", outboxPayload);

        return mapToResponse(doc, order, orderItems);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReturnResponse cancelReturn(Long returnId) {
        SecurityUtils.requireRole("BRANCH_MANAGER", "OWNER");
        Long actorId = requireCurrentUser();

        ReturnDocument doc = returnDocumentRepository.findByIdForUpdate(returnId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy phiếu trả hàng"));
        requireBranchAccess(doc.getBranchId());

        if (doc.getStatus() != ReturnStatus.DRAFT) {
            throw new AppException(
                    ErrorCode.INVALID_STATUS,
                    "Chỉ hủy được phiếu trả ở trạng thái DRAFT",
                    Map.of("currentStatus", doc.getStatus().name(), "allowed", List.of("DRAFT"))
            );
        }

        doc.setStatus(ReturnStatus.CANCELLED);
        returnDocumentRepository.saveAndFlush(doc);

        AuditLog audit = new AuditLog();
        audit.setUserId(actorId);
        audit.setAction("CANCEL_RETURN_DOCUMENT");
        audit.setEntity("return_document");
        audit.setEntityId(doc.getId().toString());
        audit.setDetail(Map.of("orderId", doc.getOrderId()));
        auditLogRepository.save(audit);

        SalesOrder order = salesOrderRepository.findById(doc.getOrderId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn hàng gốc"));
        List<OrderItem> orderItems = orderItemRepository.findByOrderId(order.getId());
        return mapToResponse(doc, order, orderItems);
    }

    /**
     * Công thức tiền hoàn của 1 dòng (BR-10 & BA mục 5.2):
     * Hoàn = (SL trả / SL mua) * (lineTotal - KM_đơn * lineTotal / Tổng_sau_giảm_dòng)
     * Làm tròn xuống đến đồng VND.
     */
    private long calculateLineRefund(SalesOrder order, List<OrderItem> allOrderItems, OrderItem targetItem, int returnQty) {
        long sumAfterItemDiscount = allOrderItems.stream()
                .mapToLong(OrderItem::getLineTotal)
                .sum();
        if (sumAfterItemDiscount <= 0L || targetItem.getLineTotal() <= 0L) {
            return 0L;
        }

        // Phần giảm cấp đơn (KM cấp đơn + giảm tay cấp đơn) = Tổng sau giảm dòng - Tổng thực thu
        long orderLevelDiscount = Math.max(0L, sumAfterItemDiscount - order.getTotal());

        BigDecimal lineNet = BigDecimal.valueOf(targetItem.getLineTotal());
        BigDecimal totalNetLines = BigDecimal.valueOf(sumAfterItemDiscount);
        BigDecimal orderDisc = BigDecimal.valueOf(orderLevelDiscount);

        BigDecimal allocatedOrderDiscount = orderDisc
                .multiply(lineNet)
                .divide(totalNetLines, MathContext.DECIMAL128);

        BigDecimal actualLinePaid = lineNet.subtract(allocatedOrderDiscount);
        if (actualLinePaid.compareTo(BigDecimal.ZERO) <= 0) {
            return 0L;
        }

        BigDecimal refund = actualLinePaid
                .multiply(BigDecimal.valueOf(returnQty))
                .divide(BigDecimal.valueOf(targetItem.getQuantity()), MathContext.DECIMAL128);

        return refund.setScale(0, RoundingMode.DOWN).longValueExact();
    }

    private Long requireCurrentUser() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Chưa đăng nhập");
        }
        return userId;
    }

    private void requireBranchAccess(Long branchId) {
        List<Long> accessibleBranches = SecurityUtils.getAccessibleBranchIds(jdbcTemplate);
        if (accessibleBranches != null && !accessibleBranches.contains(branchId)) {
            throw AppException.branchForbidden(branchId);
        }
    }

    private SalesOrder lockOrderForUpdate(Long orderId) {
        List<Long> locked = jdbcTemplate.queryForList(
                "SELECT id FROM sales_order WHERE id = ? FOR UPDATE", Long.class, orderId
        );
        if (locked.isEmpty()) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn hàng");
        }
        return salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn hàng"));
    }

    private ReturnResponse mapToResponse(ReturnDocument doc, SalesOrder order, List<OrderItem> orderItems) {
        BranchSaleInfo branchInfo = branchService.getSaleInfo(doc.getBranchId());
        BranchRef branchRef = new BranchRef(doc.getBranchId(), branchInfo.code(), branchInfo.name());

        UserRef createdByRef = resolveUserRef(doc.getCreatedBy());
        UserRef approvedByRef = resolveUserRef(doc.getApprovedBy());

        Map<Long, OrderItem> orderItemMap = orderItems.stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));

        List<ReturnItemResponse> itemResponses = new ArrayList<>();
        for (ReturnItem ri : doc.getItems()) {
            OrderItem oi = orderItemMap.get(ri.getOrderItemId());
            String sku = "N/A";
            String name = "N/A";
            String unit = "N/A";
            Long productId = oi != null ? oi.getProductId() : null;
            long lineRefund = 0L;

            if (oi != null) {
                lineRefund = calculateLineRefund(order, orderItems, oi, ri.getQuantity());
                try {
                    ProductSaleInfo pInfo = productService.getSaleInfo(oi.getProductId());
                    if (pInfo != null) {
                        sku = pInfo.sku();
                        name = pInfo.name();
                        unit = pInfo.unit();
                    }
                } catch (Exception e) {
                    log.warn("Không tra cứu được sản phẩm id={}: {}", oi.getProductId(), e.getMessage());
                }
            }

            itemResponses.add(new ReturnItemResponse(
                    ri.getId(),
                    ri.getOrderItemId(),
                    productId,
                    sku,
                    name,
                    unit,
                    ri.getQuantity(),
                    ri.getCondition(),
                    lineRefund
            ));
        }

        return new ReturnResponse(
                doc.getId(),
                doc.getCode(),
                order.getId(),
                order.getCode(),
                branchRef,
                doc.getReason(),
                doc.getRefundAmount(),
                doc.getStatus(),
                createdByRef,
                approvedByRef,
                doc.getCreatedAt(),
                doc.getApprovedAt(),
                itemResponses
        );
    }

    private UserRef resolveUserRef(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            return new UserRef(userId, authService.getFullName(userId));
        } catch (Exception e) {
            return new UserRef(userId, "N/A");
        }
    }
}
