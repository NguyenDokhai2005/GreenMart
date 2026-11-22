package vn.greenmart.sales.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import vn.greenmart.common.json.StrictLongDeserializer;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentSimulation;

public record PaymentRequest(
        @NotNull(message = "Phương thức thanh toán không được để trống")
        PaymentMethod method,

        @JsonDeserialize(using = StrictLongDeserializer.class)
        Long cashReceived,

        PaymentSimulation simulate
) {
    public PaymentRequest {
        if (simulate == null) {
            simulate = PaymentSimulation.SUCCESS;
        }
    }
}
