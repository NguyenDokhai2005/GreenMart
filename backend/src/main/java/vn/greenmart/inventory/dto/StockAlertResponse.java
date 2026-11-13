package vn.greenmart.inventory.dto;

import java.time.OffsetDateTime;

public record StockAlertResponse(long id, long branchId, long productId, String sku,
        String productName, StockStatus severity, int available, Integer reorderPoint,
        OffsetDateTime openedAt) {}
