package vn.greenmart.sales.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.catalog.dto.ProductSaleInfo;
import vn.greenmart.catalog.service.ProductService;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.integration.outbox.OutboxService;
import vn.greenmart.inventory.exception.InsufficientStockException;
import vn.greenmart.inventory.service.InventoryService;
import vn.greenmart.sales.domain.OrderItem;
import vn.greenmart.sales.domain.OrderStatus;
import vn.greenmart.sales.domain.Payment;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentStatus;
import vn.greenmart.sales.domain.SalesOrder;
import vn.greenmart.sales.dto.BranchRef;
import vn.greenmart.sales.dto.CustomerRef;
import vn.greenmart.sales.dto.InsufficientStockLine;
import vn.greenmart.sales.dto.OrderItemResponse;
import vn.greenmart.sales.dto.OrderLineRequest;
import vn.greenmart.sales.dto.OrderResponse;
import vn.greenmart.sales.dto.PaymentRequest;
import vn.greenmart.sales.dto.PaymentResponse;
import vn.greenmart.sales.dto.PosOrderRequest;
import vn.greenmart.sales.dto.Quote;
import vn.greenmart.sales.dto.QuoteLine;
import vn.greenmart.sales.dto.QuoteRequest;
import vn.greenmart.sales.dto.UserRef;
import vn.greenmart.sales.repository.OrderItemRepository;
import vn.greenmart.sales.repository.PaymentRepository;
import vn.greenmart.sales.repository.SalesOrderRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PosOrderServiceImpl implements PosOrderService {

    private final QuoteService quoteService;
    private final ProductService productService;
    private final InventoryService inventoryService;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final OutboxService outboxService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse createPosOrder(PosOrderRequest request, String idempotencyKey) {
        // Phân quyền: x-roles chỉ gồm BRANCH_MANAGER và CASHIER
        SecurityUtils.requireRole("BRANCH_MANAGER", "CASHIER");

        // Kiểm tra quyền chi nhánh theo BR-11
        List<Long> accessibleBranches = SecurityUtils.getAccessibleBranchIds(jdbcTemplate);
        if (accessibleBranches != null && !accessibleBranches.contains(request.branchId())) {
            throw new AppException(ErrorCode.BRANCH_FORBIDDEN, "Bạn không có quyền thao tác trên chi nhánh này");
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

        // Kiểm tra xem có sản phẩm ngưng kinh doanh không
        for (OrderLineRequest lineReq : request.items()) {
            ProductSaleInfo pInfo = productService.getSaleInfo(lineReq.productId());
            if (!pInfo.active()) {
                throw new AppException(ErrorCode.PRODUCT_INACTIVE, "Sản phẩm " + pInfo.sku() + " đã ngừng kinh doanh");
            }
        }

        // Kiểm tra tồn trước khi lock và tránh Deadlock (Sort theo productId ASC)
        List<OrderLineRequest> sortedItems = new ArrayList<>(request.items());
        sortedItems.sort(Comparator.comparing(OrderLineRequest::productId));

        // Kiểm tra sơ bộ các dòng thiếu tồn
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

        // Kiểm tra thanh toán giả lập
        PaymentRequest payReq = request.payment();
        long totalAmount = recomputedQuote.total();
        Long cashReceived = null;
        Long change = null;
        String providerRef = null;

        if (payReq.method() == PaymentMethod.CASH) {
            cashReceived = payReq.cashReceived();
            if (cashReceived == null || cashReceived < totalAmount) {
                throw new AppException(ErrorCode.CASH_INSUFFICIENT, "Tiền khách đưa nhỏ hơn tổng phải trả",
                        Map.of("total", totalAmount, "cashReceived", cashReceived != null ? cashReceived : 0L));
            }
            change = cashReceived - totalAmount;
        } else {
            String sim = payReq.simulate();
            if ("FAILED".equalsIgnoreCase(sim)) {
                throw new AppException(ErrorCode.PAYMENT_FAILED, "Thanh toán thẻ/QR không thành công, vui lòng chọn phương thức khác");
            }
            if ("TIMEOUT".equalsIgnoreCase(sim)) {
                throw new AppException(ErrorCode.PAYMENT_TIMEOUT, "Thanh toán quá thời gian chờ (60s)");
            }
            providerRef = "SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }

        // Tạo đơn hàng SalesOrder
        Long posChannelId = jdbcTemplate.queryForObject(
                "SELECT id FROM sales_channel WHERE code = 'POS'", Long.class
        );

        String branchCode = jdbcTemplate.queryForObject(
                "SELECT code FROM branch WHERE id = ?", String.class, request.branchId()
        );
        String branchCodeClean = (branchCode != null) ? branchCode.replace("-", "") : "GM001";
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        Long seq = jdbcTemplate.queryForObject("SELECT nextval('sales_order_id_seq')", Long.class);
        String orderCode = String.format("POS-%s-%s-%04d", branchCodeClean, dateStr, (seq != null ? seq : 1L) % 10000);

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
        order.setTotal(recomputedQuote.total());
        order.setCreatedBy(currentUserId);
        order.setCreatedAt(now);
        order.setCompletedAt(now);

        order = salesOrderRepository.save(order);

        // Trừ tồn kho & Ghi dòng đơn hàng
        List<OrderItemResponse> itemResponses = new ArrayList<>();
        int totalItemCount = 0;

        for (QuoteLine qLine : recomputedQuote.lines()) {
            try {
                inventoryService.sellDirect(request.branchId(), qLine.productId(), qLine.quantity(), order.getId());
            } catch (InsufficientStockException ex) {
                throw new AppException(ErrorCode.INSUFFICIENT_STOCK, "Không đủ tồn kho cho sản phẩm: " + qLine.sku());
            }

            OrderItem orderItem = new OrderItem();
            orderItem.setOrderId(order.getId());
            orderItem.setProductId(qLine.productId());
            orderItem.setQuantity(qLine.quantity());
            orderItem.setUnitPrice(qLine.unitPrice());
            orderItem.setDiscount(qLine.discount());
            orderItem.setLineTotal(qLine.lineTotal());
            orderItem.setPromotionId(qLine.promotion() != null ? qLine.promotion().id() : null);

            orderItem = orderItemRepository.save(orderItem);

            itemResponses.add(new OrderItemResponse(
                    orderItem.getId(),
                    orderItem.getProductId(),
                    qLine.sku(),
                    qLine.name(),
                    qLine.unit(),
                    orderItem.getQuantity(),
                    orderItem.getUnitPrice(),
                    orderItem.getDiscount(),
                    orderItem.getLineTotal(),
                    qLine.promotion()
            ));
            totalItemCount += orderItem.getQuantity();
        }

        // Tạo bản ghi Payment
        Payment payment = new Payment();
        payment.setOrderId(order.getId());
        payment.setMethod(payReq.method());
        payment.setAmount(totalAmount);
        payment.setStatus(PaymentStatus.PAID);
        payment.setProviderRef(providerRef);
        payment.setPaidAt(now);

        payment = paymentRepository.save(payment);

        PaymentResponse paymentResponse = new PaymentResponse(
                payment.getId(),
                payment.getMethod(),
                payment.getAmount(),
                payment.getStatus(),
                cashReceived,
                change,
                payment.getProviderRef(),
                payment.getPaidAt()
        );

        // Cập nhật used_count của khuyến mại nếu có
        if (order.getPromotionId() != null) {
            jdbcTemplate.update("UPDATE promotion SET used_count = used_count + 1 WHERE id = ?", order.getPromotionId());
        }
        for (QuoteLine qLine : recomputedQuote.lines()) {
            if (qLine.promotion() != null && qLine.promotion().id() != null) {
                jdbcTemplate.update("UPDATE promotion SET used_count = used_count + 1 WHERE id = ?", qLine.promotion().id());
            }
        }

        // Enqueue 2 sự kiện outbox trong cùng Transaction
        Map<String, Object> orderPayload = Map.of(
                "orderId", order.getId(),
                "code", order.getCode(),
                "branchId", order.getBranchId(),
                "total", order.getTotal(),
                "completedAt", now.toString()
        );
        outboxService.enqueue("order", order.getId().toString(), "order.completed", orderPayload);

        List<Long> changedProductIds = sortedItems.stream().map(OrderLineRequest::productId).toList();
        Map<String, Object> stockPayload = Map.of(
                "branchId", order.getBranchId(),
                "productIds", changedProductIds,
                "reason", "POS_SALE"
        );
        outboxService.enqueue("stock", order.getBranchId().toString(), "stock.changed", stockPayload);

        String branchName = jdbcTemplate.queryForObject(
                "SELECT name FROM branch WHERE id = ?", String.class, request.branchId()
        );
        BranchRef branchRef = new BranchRef(request.branchId(), branchCode, branchName);

        CustomerRef customerRef = null;
        if (request.customerId() != null) {
            customerRef = jdbcTemplate.queryForObject(
                    "SELECT id, phone, name FROM customer WHERE id = ?",
                    (rs, rowNum) -> new CustomerRef(rs.getLong("id"), rs.getString("phone"), rs.getString("name")),
                    request.customerId()
            );
        }

        String userFullName = jdbcTemplate.queryForObject(
                "SELECT full_name FROM app_user WHERE id = ?", String.class, currentUserId
        );
        UserRef userRef = new UserRef(currentUserId, userFullName);

        return new OrderResponse(
                order.getId(),
                order.getCode(),
                "POS",
                null,
                branchRef,
                customerRef,
                order.getStatus(),
                totalItemCount,
                order.getTotal(),
                order.getCreatedAt(),
                order.getCompletedAt(),
                userRef,
                itemResponses,
                recomputedQuote.orderPromotion(),
                request.manualDiscount() != null ? request.manualDiscount().reason() : null,
                recomputedQuote.subtotal(),
                recomputedQuote.itemDiscount(),
                recomputedQuote.orderDiscount(),
                recomputedQuote.manualDiscount(),
                recomputedQuote.discount(),
                List.of(paymentResponse),
                null,
                null,
                null
        );
    }
}
