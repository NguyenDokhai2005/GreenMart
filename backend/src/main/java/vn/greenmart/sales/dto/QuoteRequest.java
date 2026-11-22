package vn.greenmart.sales.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import vn.greenmart.common.json.StrictLongDeserializer;

public record QuoteRequest(
    @NotNull(message = "Mã chi nhánh không được để trống")
    @JsonDeserialize(using = StrictLongDeserializer.class)
    Long branchId,

    @JsonDeserialize(using = StrictLongDeserializer.class)
    Long customerId,

    @NotEmpty(message = "Danh sách sản phẩm không được rỗng")
    @Size(min = 1, max = 100, message = "Danh sách sản phẩm từ 1 đến 100 dòng")
    List<@NotNull(message = "Dòng sản phẩm không được null") @Valid OrderLineRequest> items,

    @Valid
    ManualDiscount manualDiscount
) {}
