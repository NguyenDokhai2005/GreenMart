package vn.greenmart.sales.dto;

import jakarta.validation.constraints.NotNull;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentSimulation;

public record PaymentRequest(
        @NotNull(message = "Phương thức thanh toán không được để trống")
        PaymentMethod method,

        Long cashReceived,

        PaymentSimulation simulate
) {
    public PaymentRequest {
        if (simulate == null) {
            simulate = PaymentSimulation.SUCCESS;
        }
    }
}
