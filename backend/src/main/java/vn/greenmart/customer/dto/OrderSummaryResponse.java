package vn.greenmart.customer.dto;

import java.time.Instant;

public record OrderSummaryResponse(
    Long id,
    String code,
    String channel,
    String externalOrderId,
    BranchRef branch,
    CustomerRef customer,
    String status,
    Integer itemCount,
    Long total,
    Instant createdAt,
    Instant completedAt
) {}
