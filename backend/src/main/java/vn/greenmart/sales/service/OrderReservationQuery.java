package vn.greenmart.sales.service;

import java.util.Collection;
import java.util.List;

/** Inventory reconciliation reads reservation references, never customer or payment information. */
public interface OrderReservationQuery {
    List<Summary> find(Long branchId, Collection<Long> productIds);
    record OrderRef(Long orderId, String code, String status, long quantity) {}
    record Summary(Long productId, long quantity, long orderCount, List<OrderRef> orders) {}
}
