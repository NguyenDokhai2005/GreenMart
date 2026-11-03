package vn.greenmart.sales.service;

import org.springframework.stereotype.Service;
import vn.greenmart.catalog.domain.Product;
import vn.greenmart.sales.dto.PromotionRef;


@Service
public class PromotionCalculatorService {

    // KM cấp ITEM mẫu: Gạo ST25 giảm 15.000 đ
    public PromotionRef evaluateItemPromotion(Product product, int quantity) {
        if (product.getSku() != null && product.getSku().startsWith("GAO-ST25")) {
            long discountAmount = 15000L;
            return new PromotionRef(11L, "KM-GAO", "Giảm 15.000 đ cho Gạo ST25", discountAmount);
        }
        return null;
    }

    // KM cấp ORDER mẫu: KM-10 giảm 10% cho đơn từ 200.000 đ, tối đa 30.000 đ
    public PromotionRef evaluateOrderPromotion(long subtotalAfterItemDiscounts) {
        if (subtotalAfterItemDiscounts >= 200000L) {
            long discount = (long) Math.floor(subtotalAfterItemDiscounts * 0.10);
            if (discount > 30000L) {
                discount = 30000L;
            }
            return new PromotionRef(12L, "KM-10", "Giảm 10% đơn từ 200.000 đ, tối đa 30.000 đ", discount);
        }
        return null;
    }
}
