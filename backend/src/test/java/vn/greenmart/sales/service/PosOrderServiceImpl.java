package vn.greenmart.sales.service;

import lombok.RequiredArgsConstructor;
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
import vn.greenmart.sales.domain.SalesOrder;
import vn.greenmart.sales.dto.*;
import vn.greenmart.sales.repository.OrderItemRepository;
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
    private final OutboxService outboxService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse createPosOrder(PosOrderRequest request, String idempotencyKey) {
        SecurityUtils.requireRole("BRANCH_MANAGER", "CASHIER");

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
        order.setTotal(recomputedQuote.total());
        order.setCreatedBy(currentUserId);
        order.setCreatedAt(now);
        order.setCompletedAt(now);

        order = salesOrderRepository.save(order);

        // Trừ tồn kho & Ghi dòng đơn hàng
        // Sắp xếp productId tăng dần khi trừ tồn kho để chống Deadlock 
        List<QuoteLine> sortedQuoteLines = new ArrayList<>(recomputedQuote.lines());
        sortedQuoteLines.sort(Comparator.comparing(QuoteLine::productId));

        for (QuoteLine qLine : sortedQuoteLines) {
            inventoryService.sellDirect(request.branchId(), qLine.productId(), qLine.quantity(), order.getId());
        }

        List<OrderItemResponse> itemResponses = new ArrayList<>();
        int totalItemCount = 0;

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

        // Tạo bản ghi Payment qua PaymentService
        PaymentResponse paymentResponse = paymentService.processPayment(order.getId(), totalAmount, request.payment());

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

        BranchRef branchRef = new BranchRef(request.branchId(), branchInfo.code(), branchInfo.name());
        CustomerRef customerRef = null;
        if (request.customerId() != null) {
            CustomerResponse cust = customerService.getCustomer(request.customerId());
            customerRef = new CustomerRef(cust.id(), cust.phone(), cust.name());
        }

        String userFullName = authService.getFullName(currentUserId);
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
