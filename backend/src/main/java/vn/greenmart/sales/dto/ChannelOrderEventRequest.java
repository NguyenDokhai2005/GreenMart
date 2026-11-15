package vn.greenmart.sales.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record ChannelOrderEventRequest(
        @NotNull(message = "Loại sự kiện không được để trống")
        EventType type,

        @NotNull(message = "Thời điểm xảy ra sự kiện không được để trống")
        OffsetDateTime occurredAt,

        @Size(max = 60, message = "Mã giao dịch thanh toán tối đa 60 ký tự")
        String providerRef,

        @Size(max = 255, message = "Lý do tối đa 255 ký tự")
        String reason
) {
    public enum EventType {
        PAID,
        CANCELLED
    }
}
