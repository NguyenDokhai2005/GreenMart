package vn.greenmart.catalog.dto;

import vn.greenmart.catalog.domain.Product;

public record ProductResponse(
    Long id,
    String sku,
    String barcode,
    String name,
    String unit,
    Long categoryId,
    String categoryName,
    Long salePrice,
    Long costPrice,
    boolean active
) {
    public static ProductResponse of(Product p, String categoryName, boolean canViewCost) {
        return new ProductResponse(
            p.getId(),
            p.getSku(),
            p.getBarcode(),
            p.getName(),
            p.getUnit(),
            p.getCategoryId(),
            categoryName,
            p.getSalePrice(),
            canViewCost ? p.getCostPrice() : null,
            p.isActive()
        );
    }
}
