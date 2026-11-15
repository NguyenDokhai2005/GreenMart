package vn.greenmart.sales.service;

import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.sales.dto.CancelOrderRequest;
import vn.greenmart.sales.dto.OrderResponse;
import vn.greenmart.sales.dto.OrderSummaryResponse;
import vn.greenmart.sales.dto.PosOrderRequest;

import java.time.LocalDate;
import java.util.List;

public interface PosOrderService {
    OrderResponse createPosOrder(PosOrderRequest request, String idempotencyKey);
    OrderResponse getOrder(Long orderId);
    PageResponse<OrderSummaryResponse> listOrders(
            Long branchId,
            String channel,
            List<String> status,
            LocalDate from,
            LocalDate to,
            Long customerId,
            String q,
            Integer page,
            Integer size,
            String[] sort
    );
    OrderResponse completeOnlineOrder(Long orderId);
    OrderResponse cancelOnlineOrder(Long orderId, CancelOrderRequest request);
}
