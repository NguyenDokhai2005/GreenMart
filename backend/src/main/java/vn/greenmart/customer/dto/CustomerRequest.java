package vn.greenmart.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CustomerRequest(
    @NotBlank(message = "Số điện thoại không được để trống")
    @Pattern(regexp = "^0[0-9]{9,10}$", message = "Số điện thoại không đúng định dạng")
    String phone,

    @NotBlank(message = "Họ và tên không được để trống")
    @Size(min = 1, max = 120, message = "Họ và tên tối đa 120 ký tự")
    String name,

    @Email(message = "Email không đúng định dạng")
    @Size(max = 120, message = "Email tối đa 120 ký tự")
    String email
) {}
