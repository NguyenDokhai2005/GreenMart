package vn.greenmart.inventory.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.common.pagination.PageUtils;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.inventory.dto.InventoryItemResponse;
import vn.greenmart.inventory.dto.StockStatus;
import vn.greenmart.inventory.repository.InventoryQueryRepository;

@Service
@RequiredArgsConstructor
public class InventoryQueryServiceImpl implements InventoryQueryService {
    private final InventoryQueryRepository repository;
    private final JdbcTemplate jdbc;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InventoryItemResponse> list(Long branchId, Long productId, Long categoryId,
            String q, StockStatus stockStatus, Integer page, Integer size, String[] sort) {
        SecurityUtils.requireRole("ADMIN", "OWNER", "BRANCH_MANAGER", "CASHIER", "STOREKEEPER");
        var allowedBranches = SecurityUtils.getAccessibleBranchIds(jdbc);
        if (branchId != null && allowedBranches != null && !allowedBranches.contains(branchId)) {
            throw new AppException(ErrorCode.BRANCH_FORBIDDEN);
        }
        var pageable = PageUtils.createPageable(page, size, sort, Sort.by("branchId", "productId"),
                InventoryQueryRepository.SORT_COLUMNS.keySet());
        return PageResponse.from(repository.search(branchId, allowedBranches, productId, categoryId, q, stockStatus, pageable));
    }
}
