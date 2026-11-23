package vn.greenmart.sales.dto;

import vn.greenmart.sales.domain.ReturnStatus;

import java.time.Instant;
import java.util.List;

public record ReturnResponse(
    Long id,
    String code,
    Long orderId,
    String orderCode,
    BranchRef branch,
    String reason,
    Long refundAmount,
    ReturnStatus status,
    UserRef createdBy,
    UserRef approvedBy,
    Instant createdAt,
    Instant approvedAt,
    List<ReturnItemResponse> items
) {}
