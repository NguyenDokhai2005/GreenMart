package vn.greenmart.sales.dto;

public record IdempotencyResult(
        boolean isCreated, 
        OrderResponse response
) {}
