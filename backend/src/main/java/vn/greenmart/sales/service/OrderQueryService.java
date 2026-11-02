package vn.greenmart.sales.service;

import org.springframework.data.domain.Pageable;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.sales.dto.OrderSummaryResponse;

import java.util.Collection;

public interface OrderQueryService {
    PageResponse<OrderSummaryResponse> findByCustomer(Long customerId, Collection<Long> branchIds, Pageable pageable);
}
