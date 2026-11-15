package vn.greenmart.sales.service;

import org.springframework.data.domain.Pageable;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.sales.dto.OrderSummaryResponse;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface OrderQueryService {
    PageResponse<OrderSummaryResponse> findByCustomer(Long customerId, Collection<Long> branchIds, Pageable pageable);

    PageResponse<OrderSummaryResponse> searchOrders(
            Long branchId,
            Collection<Long> accessibleBranchIds,
            String channel,
            List<String> statuses,
            LocalDate from,
            LocalDate to,
            Long customerId,
            String q,
            Pageable pageable
    );
}
