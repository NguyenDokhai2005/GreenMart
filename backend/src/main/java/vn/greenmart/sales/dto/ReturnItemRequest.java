package vn.greenmart.sales.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import vn.greenmart.common.json.StrictIntegerDeserializer;
import vn.greenmart.common.json.StrictLongDeserializer;
import vn.greenmart.sales.domain.ReturnItemCondition;

public record ReturnItemRequest(
    @NotNull(message = "orderItemId không được để trống")
    @JsonDeserialize(using = StrictLongDeserializer.class)
    Long orderItemId,

    @NotNull(message = "quantity không được để trống")
    @Min(value = 1, message = "Số lượng trả phải lớn hơn 0")
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    Integer quantity,

    @NotNull(message = "condition không được để trống")
    ReturnItemCondition condition
) {}
