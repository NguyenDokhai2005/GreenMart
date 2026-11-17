package vn.greenmart.promotion.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import vn.greenmart.promotion.domain.PromotionLevel;
import vn.greenmart.promotion.domain.PromotionType;

import java.time.Instant;
import java.util.List;

public record PromotionRequest(
    @NotBlank(message = "Mã khuyến mại không được để trống")
    @Size(min = 2, max = 30, message = "Mã khuyến mại từ 2 đến 30 ký tự")
    @Pattern(regexp = "^[A-Z0-9-]+$", message = "Mã khuyến mại chỉ gồm chữ hoa, số và dấu gạch ngang")
    String code,

    @NotNull(message = "Loại khuyến mại không được để trống")
    PromotionType type,

    @NotNull(message = "Cấp áp dụng không được để trống")
    PromotionLevel level,

    @NotNull(message = "Giá trị khuyến mại không được để trống")
    @Min(value = 1, message = "Giá trị khuyến mại phải lớn hơn hoặc bằng 1")
    Long value,

    @Min(value = 1, message = "Mức giảm tối đa phải lớn hơn hoặc bằng 1")
    Long maxDiscount,

    @Min(value = 0, message = "Giá trị đơn tối thiểu không được âm")
    Long minOrder,

    @NotNull(message = "Thời gian bắt đầu không được để trống")
    Instant startAt,

    @NotNull(message = "Thời gian kết thúc không được để trống")
    Instant endAt,

    @Min(value = 1, message = "Giới hạn lượt dùng phải lớn hơn hoặc bằng 1")
    Integer usageLimit,

    List<Long> productIds,
    List<Long> branchIds,
    List<String> channels
) {}
