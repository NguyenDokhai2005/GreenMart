package vn.greenmart.sales.service;

import org.springframework.stereotype.Component;
import vn.greenmart.sales.dto.PromotionRef;

@Component
public class DefaultPromotionCalculator implements PromotionCalculator {

    @Override
    public PromotionRef evaluateItemPromotion(Long productId, String sku, long unitPrice, int quantity, Long branchId) {
        // Mặc định không áp dụng khuyến mại cấp sản phẩm
        return null;
    }

    @Override
    public PromotionRef evaluateOrderPromotion(long subtotalAfterLineDiscounts, Long branchId) {
        // Mặc định không áp dụng khuyến mại cấp đơn
        return null;
    }
}
