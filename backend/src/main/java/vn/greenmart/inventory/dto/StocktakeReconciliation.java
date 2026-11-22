package vn.greenmart.inventory.dto;

import java.util.List;
import vn.greenmart.sales.service.OrderReservationQuery.OrderRef;

public record StocktakeReconciliation(Long documentId, Long branchId, boolean inventoryReady, List<Line> lines) {
    public record Line(Long productId, String sku, int snapshotQuantity, Integer countedQuantity,
            Integer difference, int onHand, int reserved, Long projectedOnHand, long shortfall,
            long reservationGap, long reservationOrderCount, List<OrderRef> reservationOrders) {}
}
