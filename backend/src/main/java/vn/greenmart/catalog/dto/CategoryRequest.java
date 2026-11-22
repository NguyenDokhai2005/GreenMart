package vn.greenmart.catalog.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import vn.greenmart.common.json.StrictLongDeserializer;
import vn.greenmart.catalog.domain.CategoryNames;

public record CategoryRequest(
    @NotBlank(message = "Tên danh mục không được để trống")
    @Size(max = 120, message = "Tên danh mục không được vượt quá 120 ký tự")
    String name,

    @JsonDeserialize(using = StrictLongDeserializer.class)
    Long parentId
) {
    public CategoryRequest { name = CategoryNames.normalize(name); }
}
