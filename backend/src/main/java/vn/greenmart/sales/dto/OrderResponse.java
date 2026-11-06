package vn.greenmart.sales.dto;

import vn.greenmart.sales.domain.OrderStatus;

import java.time.Instant;
import java.util.List;

public record OrderResponse(
    Long id,
    String code,
    String channel,
    String externalOrderId,
    BranchRef branch,
    CustomerRef customer,
    OrderStatus status,
    Integer itemCount,
    Long total,
    Instant createdAt,
    Instant completedAt,
    UserRef createdBy,
    List<OrderItemResponse> items,
    PromotionRef orderPromotion,
    String manualDiscountReason,
    Long subtotal,
    Long itemDiscount,
    Long orderDiscount,
    Long manualDiscount,
    Long discount,
    List<PaymentResponse> payments,
    Instant reservedUntil,
    String statusReason,
    String statusNote
) {}
