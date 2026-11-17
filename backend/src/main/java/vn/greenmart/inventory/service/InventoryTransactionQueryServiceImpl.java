package vn.greenmart.inventory.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.common.pagination.PageUtils;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.inventory.dto.InventoryTransactionFilter;
import vn.greenmart.inventory.dto.InventoryTransactionResponse;
import vn.greenmart.inventory.repository.InventoryTransactionQueryRepository;

import java.util.Set;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class InventoryTransactionQueryServiceImpl implements InventoryTransactionQueryService {
    private final InventoryTransactionQueryRepository repository;
    private final JdbcTemplate jdbc;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<InventoryTransactionResponse> list(InventoryTransactionFilter filter, Integer page, Integer size) {
        SecurityUtils.requireRole("OWNER", "BRANCH_MANAGER", "STOREKEEPER");
        var allowed = SecurityUtils.getAccessibleBranchIds(jdbc);
        if (filter.branchId() != null && allowed != null && !allowed.contains(filter.branchId())) {
            throw AppException.branchForbidden(filter.branchId());
        }
        if (Stream.of(filter.branchId(), filter.productId(), filter.refId()).anyMatch(id -> id != null && id <= 0)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "ID bộ lọc phải lớn hơn 0");
        }
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Ngày bắt đầu không được sau ngày kết thúc");
        }
        if (Stream.of(filter.from(), filter.to()).anyMatch(date -> date != null && (date.getYear() < 1 || date.getYear() > 9999))) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Ngày bộ lọc phải thuộc năm 0001–9999");
        }
        var pageable = PageUtils.createPageable(page, size, (String[]) null,
                Sort.unsorted(), Set.of());
        return PageResponse.from(repository.search(filter, allowed, pageable));
    }
}
