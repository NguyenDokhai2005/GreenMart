package vn.greenmart.sales.dto;

import jakarta.validation.constraints.NotNull;
import vn.greenmart.sales.domain.PaymentMethod;

public record PaymentRequest(
    @NotNull(message = "Phương thức thanh toán không được để trống")
    PaymentMethod method,

    Long cashReceived,

    String simulate // Mặc định là SUCCESS, có thể nhận FAILED hoặc TIMEOUT
) {
    public PaymentRequest {
        if (simulate == null || simulate.isBlank()) {
            simulate = "SUCCESS";
        }
    }
}
