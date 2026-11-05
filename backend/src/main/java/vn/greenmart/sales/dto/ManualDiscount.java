package vn.greenmart.sales.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ManualDiscount(
    @NotNull(message = "Số tiền giảm giá thủ công không được để trống")
    @Min(value = 0, message = "Số tiền giảm giá thủ công phải lớn hơn hoặc bằng 0")
    Long amount,

    @NotNull(message = "Lý do giảm giá thủ công không được để trống")
    @Size(min = 5, max = 255, message = "Lý do giảm giá thủ công phải từ 5 đến 255 ký tự")
    String reason
) {}
