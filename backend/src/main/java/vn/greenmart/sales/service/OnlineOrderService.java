package vn.greenmart.sales.service;

import vn.greenmart.integration.webhook.ChannelOrderIntake;
import vn.greenmart.sales.dto.CancelOrderRequest;
import vn.greenmart.sales.dto.ChannelOrderEventRequest;
import vn.greenmart.sales.dto.OrderResponse;

public interface OnlineOrderService {
    OrderResponse completeOrder(Long orderId);

    OrderResponse cancelOrder(Long orderId, CancelOrderRequest request);

    ChannelOrderIntake.Ack handleChannelEvent(String channelCode, String externalOrderId, ChannelOrderEventRequest event);

    int expireOverdueOrders();
}
