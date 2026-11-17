package vn.greenmart.promotion.dto;

import jakarta.validation.constraints.NotNull;
import vn.greenmart.promotion.domain.PromotionStatus;

public record PromotionStatusRequest(
    @NotNull(message = "Trạng thái mới không được để trống")
    PromotionStatus status
) {}
