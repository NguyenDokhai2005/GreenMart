package vn.greenmart.sales.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CancelOrderRequest(
        @NotNull(message = "Lý do hủy đơn không được để trống")
        CancelReason reason,

        @Size(max = 255, message = "Ghi chú không được vượt quá 255 ký tự")
        String note
) {}
