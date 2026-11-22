package vn.greenmart.promotion.dto;

import vn.greenmart.promotion.domain.Promotion;
import vn.greenmart.promotion.domain.PromotionLevel;
import vn.greenmart.promotion.domain.PromotionStatus;
import vn.greenmart.promotion.domain.PromotionType;

import java.time.Instant;
import java.util.List;

public record PromotionResponse(
    Long id,
    String code,
    PromotionType type,
    PromotionLevel level,
    Long value,
    Long maxDiscount,
    Long minOrder,
    Integer minQuantity,
    Instant startAt,
    Instant endAt,
    Integer usageLimit,
    Integer usedCount,
    PromotionStatus status,
    List<Long> productIds,
    List<Long> branchIds,
    List<String> channels
) {
    public static PromotionResponse of(
            Promotion p,
            List<Long> productIds,
            List<Long> branchIds,
            List<String> channels
    ) {
        return new PromotionResponse(
                p.getId(),
                p.getCode(),
                p.getType(),
                p.getLevel(),
                p.getValue(),
                p.getMaxDiscount(),
                p.getMinOrder(),
                p.getLevel() == PromotionLevel.ITEM ? (p.getMinQuantity() == null ? 1 : p.getMinQuantity()) : null,
                p.getStartAt(),
                p.getEndAt(),
                p.getUsageLimit(),
                p.getUsedCount(),
                p.getStatus(),
                productIds,
                branchIds,
                channels
        );
    }
}
