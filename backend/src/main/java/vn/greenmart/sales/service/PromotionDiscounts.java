package vn.greenmart.sales.service;

import java.math.BigInteger;

/** BR-07 money arithmetic; callers provide a nonnegative subtotal and validated promotion values. */
final class PromotionDiscounts {
    private PromotionDiscounts() {}

    static long amount(long value, int quantity, long subtotal) {
        return BigInteger.valueOf(value).multiply(BigInteger.valueOf(quantity))
                .min(BigInteger.valueOf(subtotal)).longValueExact();
    }

    static long percent(long value, Long maxDiscount, long subtotal) {
        BigInteger discount = BigInteger.valueOf(subtotal).multiply(BigInteger.valueOf(value))
                .divide(BigInteger.valueOf(100));
        if (maxDiscount != null) discount = discount.min(BigInteger.valueOf(maxDiscount));
        return discount.min(BigInteger.valueOf(subtotal)).longValueExact();
    }
}
