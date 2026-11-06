package vn.greenmart.inventory.dto;

import vn.greenmart.inventory.domain.StockDocumentStatus;
import vn.greenmart.inventory.domain.StockDocumentType;
import java.time.OffsetDateTime;
import java.util.List;

public record StockDocumentResponse(Long id, String code, StockDocumentType type,
        BranchRef branch, BranchRef toBranch, StockDocumentStatus status, int itemCount,
        boolean hasDiscrepancy, UserRef createdBy, OffsetDateTime createdAt,
        OffsetDateTime approvedAt, OffsetDateTime receivedAt, String supplier,
        String exportReason, String note, UserRef approvedBy, UserRef receivedBy,
        Long totalCost, List<Item> items) {
    public record BranchRef(Long id, String code, String name) {}
    public record UserRef(Long id, String fullName) {}
    public record Item(Long id, Long productId, String sku, String productName, String unit,
            int quantity, Long unitCost, Integer receivedQuantity, Integer countedQuantity, Integer difference) {}
}
