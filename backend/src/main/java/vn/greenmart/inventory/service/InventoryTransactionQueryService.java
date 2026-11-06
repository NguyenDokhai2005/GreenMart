package vn.greenmart.inventory.service;

import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.inventory.dto.InventoryTransactionFilter;
import vn.greenmart.inventory.dto.InventoryTransactionResponse;

public interface InventoryTransactionQueryService {
    PageResponse<InventoryTransactionResponse> list(InventoryTransactionFilter filter, Integer page, Integer size);
}
