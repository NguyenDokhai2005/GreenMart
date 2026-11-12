package vn.greenmart.reporting.dto;

public record ProductSalesResponse(
        Long productId,
        String sku,
        String name,
        String categoryName,
        Long soldQuantity,
        Long returnedQuantity,
        Long netQuantity,
        Long revenue
) {
}
