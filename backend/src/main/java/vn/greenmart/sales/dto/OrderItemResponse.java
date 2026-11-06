package vn.greenmart.sales.dto;

public record OrderItemResponse(
    Long id,
    Long productId,
    String sku,
    String name,
    String unit,
    Integer quantity,
    Long unitPrice,
    Long discount,
    Long lineTotal,
    PromotionRef promotion
) {}
