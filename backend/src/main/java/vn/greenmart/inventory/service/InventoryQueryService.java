package vn.greenmart.inventory.service;

import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.inventory.dto.InventoryItemResponse;
import vn.greenmart.inventory.dto.StockStatus;

public interface InventoryQueryService {
    PageResponse<InventoryItemResponse> list(Long branchId, Long productId, Long categoryId,
            String q, StockStatus stockStatus, Integer page, Integer size, String[] sort);
}
