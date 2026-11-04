package vn.greenmart.sales.service;

import vn.greenmart.sales.dto.PromotionRef;

public interface PromotionCalculator {
    PromotionRef evaluateItemPromotion(Long productId, String sku, long unitPrice, int quantity, Long branchId);
    PromotionRef evaluateOrderPromotion(long subtotalAfterLineDiscounts, Long branchId);
}
