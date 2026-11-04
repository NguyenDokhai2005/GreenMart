package vn.greenmart.catalog.dto;

public record ProductSaleInfo(
    Long id,
    String sku,
    String name,
    String unit,
    Long salePrice,
    boolean active
) {}
