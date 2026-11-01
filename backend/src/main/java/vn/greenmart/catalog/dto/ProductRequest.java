package vn.greenmart.catalog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProductRequest(
    @NotBlank(message = "Mã SKU không được để trống")
    @Size(max = 40, message = "SKU không được vượt quá 40 ký tự")
    @Pattern(regexp = "^[A-Z0-9-]+$", message = "SKU chỉ được chứa chữ in hoa, số và dấu gạch ngang")
    String sku,

    @Size(max = 40, message = "Mã vạch không được vượt quá 40 ký tự")
    String barcode,

    @NotBlank(message = "Tên sản phẩm không được để trống")
    @Size(max = 200, message = "Tên sản phẩm không được vượt quá 200 ký tự")
    String name,

    @NotBlank(message = "Đơn vị tính không được để trống")
    @Size(max = 20, message = "Đơn vị tính không được vượt quá 20 ký tự")
    String unit,

    @NotNull(message = "Danh mục không được để trống")
    Long categoryId,

    @NotNull(message = "Giá bán không được để trống")
    @Min(value = 0, message = "Giá bán phải lớn hơn hoặc bằng 0")
    Long salePrice,

    @NotNull(message = "Giá vốn không được để trống")
    @Min(value = 0, message = "Giá vốn phải lớn hơn hoặc bằng 0")
    Long costPrice,

    Boolean active
) {}
