package vn.greenmart.sales.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import vn.greenmart.common.json.StrictLongDeserializer;

public record CreateReturnRequest(
    @NotNull(message = "orderId không được để trống")
    @JsonDeserialize(using = StrictLongDeserializer.class)
    Long orderId,

    @NotNull(message = "branchId không được để trống")
    @JsonDeserialize(using = StrictLongDeserializer.class)
    Long branchId,

    @NotBlank(message = "Lý do trả hàng không được để trống")
    @Size(min = 3, max = 255, message = "Lý do trả hàng phải từ 3 đến 255 ký tự")
    String reason,

    @NotEmpty(message = "Danh sách sản phẩm trả không được để trống")
    List<@NotNull(message = "Dòng sản phẩm trả không được null") @Valid ReturnItemRequest> items
) {}
