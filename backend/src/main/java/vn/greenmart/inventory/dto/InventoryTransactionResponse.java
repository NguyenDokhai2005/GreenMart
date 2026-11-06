package vn.greenmart.inventory.dto;

import vn.greenmart.inventory.domain.InventoryReferenceType;
import vn.greenmart.inventory.domain.InventoryTransactionType;
import java.time.OffsetDateTime;

public record InventoryTransactionResponse(long id, long branchId, long productId, String sku,
        InventoryTransactionType type, int deltaOnHand, int onHandAfter, int deltaReserved, int reservedAfter,
        InventoryReferenceType refType, long refId, String refCode, UserRef createdBy, OffsetDateTime createdAt) {
    public record UserRef(long id, String fullName) {}
}
