package vn.greenmart.sales.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.sales.dto.PromotionRef;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DefaultPromotionCalculator implements PromotionCalculator {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Override
    public PromotionRef evaluateItemPromotion(Long productId, String sku, long unitPrice, int quantity, Long branchId, String channelCode) {
        final long lineSubtotal;
        try {
            lineSubtotal = Math.multiplyExact(unitPrice, (long) quantity);
        } catch (ArithmeticException ex) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Thành tiền dòng sản phẩm vượt quá giới hạn cho phép");
        }
        if (productId == null || lineSubtotal <= 0) {
            return null;
        }
        String effectiveChannel = (channelCode != null && !channelCode.isBlank()) ? channelCode.trim().toUpperCase() : "POS";
        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);

        List<PromotionCandidate> candidates = jdbcTemplate.query(
                """
                SELECT p.id, p.code, p.type, p.value, p.max_discount, p.end_at
                FROM promotion p
                JOIN promotion_product pp ON pp.promotion_id = p.id AND pp.product_id = ?
                WHERE p.status = 'ACTIVE'
                  AND p.level = 'ITEM'
                  AND p.start_at <= ? AND p.end_at > ?
                  AND (p.usage_limit IS NULL OR p.used_count < p.usage_limit)
                  AND (NOT EXISTS (SELECT 1 FROM promotion_branch pb WHERE pb.promotion_id = p.id)
                       OR EXISTS (SELECT 1 FROM promotion_branch pb WHERE pb.promotion_id = p.id AND pb.branch_id = ?))
                  AND (NOT EXISTS (SELECT 1 FROM promotion_channel pc WHERE pc.promotion_id = p.id)
                       OR EXISTS (SELECT 1 FROM promotion_channel pc JOIN sales_channel sc ON sc.id = pc.channel_id
                                 WHERE pc.promotion_id = p.id AND sc.code = ?))
                """,
                (rs, rowNum) -> new PromotionCandidate(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("type"),
                        rs.getLong("value"),
                        (Long) rs.getObject("max_discount"),
                        rs.getTimestamp("end_at").toInstant()
                ),
                productId, nowTs, nowTs, branchId, effectiveChannel
        );

        return pickBestPromotion(candidates, lineSubtotal);
    }

    @Override
    public PromotionRef evaluateOrderPromotion(long subtotalAfterLineDiscounts, Long branchId, String channelCode) {
        if (subtotalAfterLineDiscounts <= 0) {
            return null;
        }
        String effectiveChannel = (channelCode != null && !channelCode.isBlank()) ? channelCode.trim().toUpperCase() : "POS";
        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);

        List<PromotionCandidate> candidates = jdbcTemplate.query(
                """
                SELECT p.id, p.code, p.type, p.value, p.max_discount, p.end_at
                FROM promotion p
                WHERE p.status = 'ACTIVE'
                  AND p.level = 'ORDER'
                  AND p.start_at <= ? AND p.end_at > ?
                  AND (p.min_order IS NULL OR ? >= p.min_order)
                  AND (p.usage_limit IS NULL OR p.used_count < p.usage_limit)
                  AND (NOT EXISTS (SELECT 1 FROM promotion_branch pb WHERE pb.promotion_id = p.id)
                       OR EXISTS (SELECT 1 FROM promotion_branch pb WHERE pb.promotion_id = p.id AND pb.branch_id = ?))
                  AND (NOT EXISTS (SELECT 1 FROM promotion_channel pc WHERE pc.promotion_id = p.id)
                       OR EXISTS (SELECT 1 FROM promotion_channel pc JOIN sales_channel sc ON sc.id = pc.channel_id
                                 WHERE pc.promotion_id = p.id AND sc.code = ?))
                """,
                (rs, rowNum) -> new PromotionCandidate(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("type"),
                        rs.getLong("value"),
                        (Long) rs.getObject("max_discount"),
                        rs.getTimestamp("end_at").toInstant()
                ),
                nowTs, nowTs, subtotalAfterLineDiscounts, branchId, effectiveChannel
        );

        return pickBestPromotion(candidates, subtotalAfterLineDiscounts);
    }

    private PromotionRef pickBestPromotion(List<PromotionCandidate> candidates, long baseAmount) {
        return candidates.stream()
                .map(c -> new EvaluatedCandidate(c, computeDiscount(c, baseAmount)))
                .filter(ec -> ec.discount() > 0)
                .max(Comparator
                        .comparingLong(EvaluatedCandidate::discount)
                        .thenComparing(ec -> ec.candidate().endAt(), Comparator.reverseOrder())
                        .thenComparing(ec -> ec.candidate().id(), Comparator.reverseOrder()))
                .map(ec -> new PromotionRef(
                        ec.candidate().id(),
                        ec.candidate().code(),
                        ec.candidate().code(),
                        ec.discount()
                ))
                .orElse(null);
    }

    private long computeDiscount(PromotionCandidate c, long baseAmount) {
        long rawDiscount;
        if ("PERCENT".equalsIgnoreCase(c.type())) {
            // Dùng BigInteger tránh tràn số int64 khi nhân baseAmount lớn với phần trăm
            BigInteger calculated = BigInteger.valueOf(baseAmount)
                    .multiply(BigInteger.valueOf(c.value()))
                    .divide(BigInteger.valueOf(100L));
            rawDiscount = calculated.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                    ? Long.MAX_VALUE
                    : calculated.longValue();
            if (c.maxDiscount() != null && rawDiscount > c.maxDiscount()) {
                rawDiscount = c.maxDiscount();
            }
        } else {
            rawDiscount = c.value();
        }
        return Math.min(rawDiscount, baseAmount);
    }

    private record PromotionCandidate(
            Long id,
            String code,
            String type,
            long value,
            Long maxDiscount,
            Instant endAt
    ) {}

    private record EvaluatedCandidate(PromotionCandidate candidate, long discount) {}
}
