package vn.greenmart.sales.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.greenmart.sales.dto.PromotionRef;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DefaultPromotionCalculator implements PromotionCalculator {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public PromotionRef evaluateItemPromotion(Long productId, String sku, long unitPrice, int quantity, Long branchId, String channelCode) {
        long lineSubtotal = unitPrice * quantity;
        if (productId == null || lineSubtotal <= 0) {
            return null;
        }
        String effectiveChannel = (channelCode != null && !channelCode.isBlank()) ? channelCode.trim().toUpperCase() : "POS";
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        List<PromotionCandidate> candidates = jdbcTemplate.query(
                """
                SELECT p.id, p.code, p.type, p.value, p.max_discount, p.end_at
                FROM promotion p
                JOIN promotion_product pp ON pp.promotion_id = p.id AND pp.product_id = ?
                WHERE p.status = 'ACTIVE'
                  AND p.level = 'ITEM'
                  AND p.start_at <= ? AND p.end_at >= ?
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
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        List<PromotionCandidate> candidates = jdbcTemplate.query(
                """
                SELECT p.id, p.code, p.type, p.value, p.max_discount, p.end_at
                FROM promotion p
                WHERE p.status = 'ACTIVE'
                  AND p.level = 'ORDER'
                  AND p.start_at <= ? AND p.end_at >= ?
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
            rawDiscount = (baseAmount * c.value()) / 100L;
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

