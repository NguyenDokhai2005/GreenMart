package vn.greenmart.sales.dto;

public record InsufficientStockLine(
    Long productId,
    String sku,
    Integer requested,
    Integer available
) {}
