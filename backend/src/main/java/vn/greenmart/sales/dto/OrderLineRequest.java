package vn.greenmart.sales.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record OrderLineRequest(
    @NotNull(message = "Mã sản phẩm không được để trống")
    Long productId,

    @NotNull(message = "Số lượng không được để trống")
    @Min(value = 1, message = "Số lượng phải lớn hơn 0")
    @Max(value = 9999, message = "Số lượng tối đa là 9999")
    Integer quantity
) {}
