package vn.greenmart.sales.service;

import vn.greenmart.sales.dto.PromotionRef;

public interface PromotionCalculator {
    default PromotionRef evaluateItemPromotion(Long productId, String sku, long unitPrice, int quantity, Long branchId) {
        return evaluateItemPromotion(productId, sku, unitPrice, quantity, branchId, "POS");
    }

    default PromotionRef evaluateOrderPromotion(long subtotalAfterLineDiscounts, Long branchId) {
        return evaluateOrderPromotion(subtotalAfterLineDiscounts, branchId, "POS");
    }

    PromotionRef evaluateItemPromotion(Long productId, String sku, long unitPrice, int quantity, Long branchId, String channelCode);

    PromotionRef evaluateOrderPromotion(long subtotalAfterLineDiscounts, Long branchId, String channelCode);
}
