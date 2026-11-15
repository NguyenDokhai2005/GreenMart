package vn.greenmart.sales.service;

import vn.greenmart.sales.dto.CancelOrderRequest;
import vn.greenmart.sales.dto.OrderResponse;
import vn.greenmart.sales.dto.PosOrderRequest;

public interface PosOrderService {
    OrderResponse createPosOrder(PosOrderRequest request, String idempotencyKey);
    OrderResponse getOrder(Long orderId);
    OrderResponse completeOnlineOrder(Long orderId);
    OrderResponse cancelOnlineOrder(Long orderId, CancelOrderRequest request);
}
