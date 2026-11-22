package vn.greenmart.sales.dto;

import vn.greenmart.sales.domain.ReturnItemCondition;

public record ReturnItemResponse(
    Long id,
    Long orderItemId,
    Long productId,
    String sku,
    String name,
    String unit,
    Integer quantity,
    ReturnItemCondition condition,
    Long refundAmount
) {}
