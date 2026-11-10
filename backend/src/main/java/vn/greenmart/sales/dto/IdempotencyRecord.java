package vn.greenmart.sales.dto;

public record IdempotencyRecord(
        String status, // "PROCESSING" hoặc "COMPLETED"
        String requestHash,
        OrderResponse response
) {}
