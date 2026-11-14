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
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.customer.dto.CustomerResponse;
import vn.greenmart.customer.service.CustomerService;
import vn.greenmart.identity.service.AuthService;
import vn.greenmart.integration.outbox.OutboxService;
import vn.greenmart.inventory.service.InventoryService;
import vn.greenmart.sales.domain.OrderItem;
import vn.greenmart.sales.domain.OrderStatus;
import vn.greenmart.sales.domain.Payment;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.SalesOrder;
import vn.greenmart.sales.dto.*;
import vn.greenmart.sales.repository.OrderItemRepository;
import vn.greenmart.sales.repository.PaymentRepository;
import vn.greenmart.sales.repository.SalesOrderRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class PosOrderServiceImpl implements PosOrderService {

    private final QuoteService quoteService;
    private final ProductService productService;
    private final CustomerService customerService;
    private final InventoryService inventoryService;
    private final BranchService branchService;
    private final AuthService authService;
    private final PromotionService promotionService;
    private final PaymentService paymentService;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final OutboxService outboxService;
    private final JdbcTemplate jdbcTemplate;
    private final OnlineOrderService onlineOrderService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse createPosOrder(PosOrderRequest request, String idempotencyKey) {
        SecurityUtils.requireRole("BRANCH_MANAGER", "CASHIER");

        List<Long> accessibleBranches = SecurityUtils.getAccessibleBranchIds(jdbcTemplate);
        if (accessibleBranches != null && !accessibleBranches.contains(request.branchId())) {
            throw AppException.branchForbidden(request.branchId(), "Bạn không có quyền thao tác trên chi nhánh này");
        }

        // Tính lại Báo giá & Kiểm tra expectedTotal
        QuoteRequest quoteRequest = new QuoteRequest(
                request.branchId(),
                request.customerId(),
                request.items(),
                request.manualDiscount()
        );
        Quote recomputedQuote = quoteService.calculateQuote(quoteRequest);

        if (!Objects.equals(recomputedQuote.total(), request.expectedTotal())) {
            throw new AppException(ErrorCode.TOTAL_CHANGED, "Tổng tiền đơn hàng đã thay đổi", Map.of("quote", recomputedQuote));
        }

        // Kiểm tra lượt khuyến mãi: nếu hết lượt thì tính lại quote mới và trả về 422 TOTAL_CHANGED
        boolean promoSuccess = true;
        if (recomputedQuote.orderPromotion() != null && recomputedQuote.orderPromotion().id() != null) {
            promoSuccess = promotionService.recordUsage(recomputedQuote.orderPromotion().id());
        }
        if (promoSuccess) {
            for (QuoteLine qLine : recomputedQuote.lines()) {
                if (qLine.promotion() != null && qLine.promotion().id() != null) {
                    if (!promotionService.recordUsage(qLine.promotion().id())) {
                        promoSuccess = false;
                        break;
                    }
                }
            }
        }

        if (!promoSuccess) {
            Quote updatedQuote = quoteService.calculateQuote(quoteRequest);
            throw new AppException(ErrorCode.TOTAL_CHANGED, "Khuyến mại đã hết lượt sử dụng, tổng tiền đã thay đổi", Map.of("quote", updatedQuote));
        }

        // Kiểm tra xem có sản phẩm ngưng kinh doanh không
        for (OrderLineRequest lineReq : request.items()) {
            ProductSaleInfo pInfo = productService.getSaleInfo(lineReq.productId());
            if (!pInfo.active()) {
                throw new AppException(ErrorCode.PRODUCT_INACTIVE, "Sản phẩm " + pInfo.sku() + " đã ngừng kinh doanh");
            }
        }

        // Sắp xếp theo productId ASC và kiểm tra tồn kho
        List<OrderLineRequest> sortedItems = new ArrayList<>(request.items());
        sortedItems.sort(Comparator.comparing(OrderLineRequest::productId));

        List<InsufficientStockLine> insufficientLines = new ArrayList<>();
        for (OrderLineRequest line : sortedItems) {
            int available = inventoryService.getAvailable(request.branchId(), line.productId());
            if (available < line.quantity()) {
                ProductSaleInfo p = productService.getSaleInfo(line.productId());
                insufficientLines.add(new InsufficientStockLine(
                        line.productId(),
                        p.sku(),
                        line.quantity(),
                        available
                ));
            }
        }
        if (!insufficientLines.isEmpty()) {
            throw new AppException(ErrorCode.INSUFFICIENT_STOCK, "Không đủ tồn kho khả dụng", Map.of("lines", insufficientLines));
        }

        // Kiểm tra thanh toán giả lập qua PaymentService
        long totalAmount = recomputedQuote.total();
        paymentService.validatePayment(totalAmount, request.payment());

        // Tạo đơn hàng SalesOrder
        Long posChannelId = jdbcTemplate.queryForObject(
                "SELECT id FROM sales_channel WHERE code = 'POS'", Long.class
        );

        BranchSaleInfo branchInfo = branchService.getSaleInfo(request.branchId());
        String branchCodeClean = branchInfo.code().replace("-", "");

        LocalDate localDate = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        String dateStr = localDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        Long seq = jdbcTemplate.queryForObject(
                "INSERT INTO order_sequence (branch_id, order_date, seq) VALUES (?, ?, 1) "
                        + "ON CONFLICT (branch_id, order_date) DO UPDATE SET seq = order_sequence.seq + 1 RETURNING seq",
                Long.class, request.branchId(), localDate
        );

        String orderCode = String.format("POS-%s-%s-%04d", branchCodeClean, dateStr, seq);

        Instant now = Instant.now();
        Long currentUserId = SecurityUtils.getCurrentUserId();

        SalesOrder order = new SalesOrder();
        order.setCode(orderCode);
        order.setChannelId(posChannelId);
        order.setExternalOrderId(null);
        order.setBranchId(request.branchId());
        order.setCustomerId(request.customerId());
        order.setPromotionId(recomputedQuote.orderPromotion() != null ? recomputedQuote.orderPromotion().id() : null);
        order.setStatus(OrderStatus.COMPLETED);
        order.setSubtotal(recomputedQuote.subtotal());
        order.setDiscount(recomputedQuote.discount());
        order.setManualDiscount(recomputedQuote.manualDiscount() != null ? recomputedQuote.manualDiscount() : 0L);
        order.setManualDiscountReason(request.manualDiscount() != null ? request.manualDiscount().reason() : null);
        order.setTotal(recomputedQuote.total());
        order.setCreatedBy(currentUserId);
        order.setCreatedAt(now);
        order.setCompletedAt(now);

        order = salesOrderRepository.save(order);

        // Trừ tồn kho trực tiếp
        var stockLines = recomputedQuote.lines().stream()
                .sorted(Comparator.comparing(QuoteLine::productId))
                .toList();
        for (QuoteLine line : stockLines) {
            inventoryService.sellDirect(request.branchId(), line.productId(), line.quantity(), order.getId(), currentUserId);
        }

        // Lưu chi tiết dòng đơn hàng
        List<OrderItem> savedItems = new ArrayList<>();
        for (QuoteLine qLine : recomputedQuote.lines()) {
            OrderItem orderItem = new OrderItem();
            orderItem.setOrderId(order.getId());
            orderItem.setProductId(qLine.productId());
            orderItem.setQuantity(qLine.quantity());
            orderItem.setUnitPrice(qLine.unitPrice());
            orderItem.setDiscount(qLine.discount());
            orderItem.setLineTotal(qLine.lineTotal());
            orderItem.setPromotionId(qLine.promotion() != null ? qLine.promotion().id() : null);

            orderItem = orderItemRepository.save(orderItem);
            savedItems.add(orderItem);
        }

        // Tạo bản ghi Payment qua PaymentService
        PaymentResponse paymentResponse = paymentService.processPayment(order.getId(), totalAmount, request.payment());

        // Enqueue Outbox events (chỉ enqueue order.completed; stock.changed đã được chuyển vào InventoryService ở INV-59)
        Map<String, Object> orderPayload = Map.of(
                "orderId", order.getId(),
                "code", order.getCode(),
                "branchId", order.getBranchId(),
                "total", order.getTotal(),
                "completedAt", now.toString()
        );
        outboxService.enqueue("order", order.getId().toString(), "order.completed", orderPayload);

        return mapToOrderResponse(order, savedItems, List.of(paymentResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        // Phân quyền vai trò: Chỉ OWNER, BRANCH_MANAGER, CASHIER, STOREKEEPER
        SecurityUtils.requireRole("OWNER", "BRANCH_MANAGER", "CASHIER", "STOREKEEPER");

        // Tìm đơn hàng
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn hàng"));

        // Phân quyền chi nhánh (BR-11, AUTH-02)
        List<Long> accessibleBranches = SecurityUtils.getAccessibleBranchIds(jdbcTemplate);
        if (accessibleBranches != null && !accessibleBranches.contains(order.getBranchId())) {
            throw AppException.branchForbidden(order.getBranchId(), "Bạn không có quyền thao tác trên chi nhánh này");
        }

        // Lấy danh sách dòng hàng và thanh toán trực tiếp từ DB
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        List<Payment> payments = paymentRepository.findByOrderId(order.getId());

        List<PaymentResponse> paymentResponses = new ArrayList<>();
        for (Payment p : payments) {
            Long cashReceived = null;
            Long change = null;
            if (p.getMethod() == PaymentMethod.CASH) {
                cashReceived = p.getCashReceived();
                if (cashReceived != null) {
                    change = cashReceived - p.getAmount();
                }
            }

            paymentResponses.add(new PaymentResponse(
                    p.getId(),
                    p.getMethod(),
                    p.getAmount(),
                    p.getStatus(),
                    cashReceived,
                    change,
                    p.getProviderRef(),
                    p.getPaidAt()
            ));
        }

        // Map dữ liệu sang OrderResponse dùng chung
        return mapToOrderResponse(order, items, paymentResponses);
    }

    @Override
    public OrderResponse completeOnlineOrder(Long orderId) {
        return onlineOrderService.completeOrder(orderId);
    }

    @Override
    public OrderResponse cancelOnlineOrder(Long orderId, CancelOrderRequest request) {
        return onlineOrderService.cancelOrder(orderId, request);
    }

    private OrderResponse mapToOrderResponse(SalesOrder order, List<OrderItem> items, List<PaymentResponse> paymentResponses) {
        BranchSaleInfo branchInfo = branchService.getSaleInfo(order.getBranchId());
        BranchRef branchRef = new BranchRef(order.getBranchId(), branchInfo.code(), branchInfo.name());

        CustomerRef customerRef = null;
        if (order.getCustomerId() != null) {
            try {
                CustomerResponse cust = customerService.getCustomer(order.getCustomerId());
                customerRef = new CustomerRef(cust.id(), cust.phone(), cust.name());
            } catch (Exception e) {
                log.warn("Không thể tra cứu thông tin khách hàng id={}: {}", order.getCustomerId(), e.getMessage());
            }
        }

        UserRef userRef = null;
        if (order.getCreatedBy() != null) {
            try {
                String fullName = authService.getFullName(order.getCreatedBy());
                userRef = new UserRef(order.getCreatedBy(), fullName);
            } catch (Exception e) {
                log.warn("Không thể tra cứu nhân viên id={}: {}", order.getCreatedBy(), e.getMessage());
                userRef = new UserRef(order.getCreatedBy(), "N/A");
            }
        }

        long itemDiscountTotal = 0L;
        int totalItemCount = 0;
        List<OrderItemResponse> itemResponses = new ArrayList<>();

        for (OrderItem item : items) {
            totalItemCount += item.getQuantity();
            if (item.getDiscount() != null) {
                itemDiscountTotal += item.getDiscount();
            }

            String sku = "N/A";
            String name = "N/A";
            String unit = "N/A";

            try {
                ProductSaleInfo pInfo = productService.getSaleInfo(item.getProductId());
                if (pInfo != null) {
                    sku = pInfo.sku();
                    name = pInfo.name();
                    unit = pInfo.unit();
                }
            } catch (Exception e) {
                log.warn("Không thể tra cứu sản phẩm id={}: {}", item.getProductId(), e.getMessage());
            }

            PromotionRef itemPromotion = getPromotionRef(item.getPromotionId(), item.getDiscount());

            itemResponses.add(new OrderItemResponse(
                    item.getId(),
                    item.getProductId(),
                    sku,
                    name,
                    unit,
                    item.getQuantity(),
                    item.getUnitPrice(),
                    item.getDiscount(),
                    item.getLineTotal(),
                    itemPromotion
            ));
        }

        long manualDiscount = order.getManualDiscount() != null ? order.getManualDiscount() : 0L;
        String manualDiscountReason = order.getManualDiscountReason();

        long orderDiscount = Math.max(0L, order.getDiscount() - itemDiscountTotal - manualDiscount);
        PromotionRef orderPromotion = getPromotionRef(order.getPromotionId(), orderDiscount);

        String channelCode = "POS";
        try {
            channelCode = jdbcTemplate.queryForObject(
                    "SELECT code FROM sales_channel WHERE id = ?", String.class, order.getChannelId()
            );
        } catch (Exception e) {
            log.warn("Không thể tra cứu mã kênh bán channelId={}: {}", order.getChannelId(), e.getMessage());
        }

        return new OrderResponse(
                order.getId(),
                order.getCode(),
                channelCode,
                order.getExternalOrderId(),
                branchRef,
                customerRef,
                order.getStatus(),
                totalItemCount,
                order.getTotal(),
                order.getCreatedAt(),
                order.getCompletedAt(),
                userRef,
                itemResponses,
                orderPromotion,
                manualDiscountReason,
                order.getSubtotal(),
                itemDiscountTotal,
                orderDiscount,
                manualDiscount,
                order.getDiscount(),
                paymentResponses,
                order.getReservedUntil(),
                null,
                null
        );
    }

    private PromotionRef getPromotionRef(Long promotionId, Long discount) {
        if (promotionId == null) {
            return null;
        }
        try {
            List<PromotionRef> list = jdbcTemplate.query(
                    "SELECT id, code, name FROM promotion WHERE id = ?",
                    (rs, rowNum) -> new PromotionRef(rs.getLong("id"), rs.getString("code"), rs.getString("name"), discount != null ? discount : 0L),
                    promotionId
            );
            if (!list.isEmpty()) {
                return list.get(0);
            }
        } catch (Exception e) {
            log.warn("Lỗi tra cứu khuyến mại id={}: {}", promotionId, e.getMessage());
        }
        return new PromotionRef(promotionId, "KM-" + promotionId, null, discount != null ? discount : 0L);
    }
}
