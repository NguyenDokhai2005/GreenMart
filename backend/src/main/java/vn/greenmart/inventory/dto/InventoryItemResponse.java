package vn.greenmart.inventory.dto;

import java.time.OffsetDateTime;

public record InventoryItemResponse(
        BranchRef branch, Long productId, String sku, String productName, String unit,
        int onHand, int reserved, int available, Integer reorderPoint,
        StockStatus stockStatus, long version, OffsetDateTime updatedAt) {
    public record BranchRef(Long id, String code, String name) {}
}
