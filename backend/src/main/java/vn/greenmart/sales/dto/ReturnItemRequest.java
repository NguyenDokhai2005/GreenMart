package vn.greenmart.sales.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import vn.greenmart.sales.domain.ReturnItemCondition;

public record ReturnItemRequest(
    @NotNull(message = "orderItemId không được để trống")
    Long orderItemId,

    @NotNull(message = "quantity không được để trống")
    @Min(value = 1, message = "Số lượng trả phải lớn hơn 0")
    Integer quantity,

    @NotNull(message = "condition không được để trống")
    ReturnItemCondition condition
) {}
