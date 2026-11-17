package vn.greenmart.sales.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.greenmart.branch.dto.BranchSaleInfo;
import vn.greenmart.branch.service.BranchService;
import vn.greenmart.catalog.dto.ProductSaleInfo;
import vn.greenmart.catalog.service.ProductService;
import vn.greenmart.customer.dto.CustomerResponse;
import vn.greenmart.customer.service.CustomerService;
import vn.greenmart.identity.service.AuthService;
import vn.greenmart.sales.domain.OrderItem;
import vn.greenmart.sales.domain.Payment;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.SalesOrder;
import vn.greenmart.sales.dto.*;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderResponseMapper {

    private static final Logger log = LoggerFactory.getLogger(OrderResponseMapper.class);

    private final BranchService branchService;
    private final CustomerService customerService;
    private final AuthService authService;
    private final ProductService productService;
    private final JdbcTemplate jdbcTemplate;

    public OrderResponse toOrderResponseFromEntities(SalesOrder order, List<OrderItem> items, List<Payment> payments) {
        return toOrderResponse(order, items, mapPayments(payments));
    }

    public OrderResponse toOrderResponse(SalesOrder order, List<OrderItem> items, List<PaymentResponse> paymentResponses) {
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
                log.warn("Không thể tra cứu thông tin nhân viên id={}: {}", order.getCreatedBy(), e.getMessage());
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
                log.warn("Không thể tra cứu thông tin sản phẩm id={}: {}", item.getProductId(), e.getMessage());
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
        long orderDiscount = Math.max(0L, order.getDiscount() - itemDiscountTotal - manualDiscount);
        String manualDiscountReason = order.getManualDiscountReason();

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

    public List<PaymentResponse> mapPayments(List<Payment> payments) {
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
        return paymentResponses;
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
            log.warn("Lỗi tra cứu thông tin khuyến mại id={}: {}", promotionId, e.getMessage());
        }
        return new PromotionRef(promotionId, "KM-" + promotionId, null, discount != null ? discount : 0L);
    }
}
