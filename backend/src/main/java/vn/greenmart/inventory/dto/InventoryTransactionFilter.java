package vn.greenmart.inventory.dto;

import vn.greenmart.inventory.domain.InventoryReferenceType;
import vn.greenmart.inventory.domain.InventoryTransactionType;
import java.time.LocalDate;

public record InventoryTransactionFilter(Long branchId, Long productId, InventoryTransactionType type,
        InventoryReferenceType refType, Long refId, LocalDate from, LocalDate to) {}
