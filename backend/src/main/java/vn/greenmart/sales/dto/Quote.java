package vn.greenmart.sales.dto;

import java.util.List;

public record Quote(
    Long branchId,
    Long customerId,
    List<QuoteLine> lines,
    PromotionRef orderPromotion,
    Long subtotal,
    Long itemDiscount,
    Long orderDiscount,
    Long manualDiscount,
    Long discount,
    Long total,
    Boolean canComplete
) {}
