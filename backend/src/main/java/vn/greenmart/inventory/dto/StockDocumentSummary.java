package vn.greenmart.inventory.dto;

import vn.greenmart.inventory.domain.StockDocumentStatus;
import vn.greenmart.inventory.domain.StockDocumentType;

import java.time.OffsetDateTime;

public record StockDocumentSummary(long id, String code, StockDocumentType type,
        StockDocumentResponse.BranchRef branch, StockDocumentResponse.BranchRef toBranch,
        StockDocumentStatus status, int itemCount, boolean hasDiscrepancy,
        StockDocumentResponse.UserRef createdBy, OffsetDateTime createdAt,
        OffsetDateTime approvedAt, OffsetDateTime receivedAt) {}
