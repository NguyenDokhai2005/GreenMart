package vn.greenmart.sales.dto;

public record PromotionRef(
    Long id,
    String code,
    String name,
    Long discount
) {}
