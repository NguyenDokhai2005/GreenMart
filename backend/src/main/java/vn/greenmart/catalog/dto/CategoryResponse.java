package vn.greenmart.catalog.dto;

import vn.greenmart.catalog.domain.Category;

public record CategoryResponse(
    Long id,
    String name,
    Long parentId
) {
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getParentId());
    }
}
