package vn.greenmart.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PosOrderRequest(
    @NotNull(message = "Mã chi nhánh không được để trống")
    Long branchId,

    Long customerId,

    @NotEmpty(message = "Danh sách sản phẩm không được rỗng")
    @Size(min = 1, max = 100, message = "Danh sách sản phẩm từ 1 đến 100 dòng")
    List<@Valid OrderLineRequest> items,

    @Valid
    ManualDiscount manualDiscount,

    @NotNull(message = "Thông tin thanh toán không được để trống")
    @Valid
    PaymentRequest payment,

    @NotNull(message = "expectedTotal không được để trống")
    Long expectedTotal
) {}
