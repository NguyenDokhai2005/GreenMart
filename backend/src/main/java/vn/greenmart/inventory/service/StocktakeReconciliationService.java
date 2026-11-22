package vn.greenmart.inventory.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.catalog.service.ProductCostService;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.inventory.domain.StockDocument;
import vn.greenmart.inventory.dto.StocktakeReconciliation;
import vn.greenmart.sales.service.OrderReservationQuery;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class StocktakeReconciliationService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ProductCostService productCost;
    private final OrderReservationQuery reservations;

    public StocktakeReconciliation inspect(StockDocument document) {
        var ids = document.getItems().stream().map(item -> item.getProductId()).sorted().toList();
        var orders = reservations.find(document.getBranchId(), ids).stream()
                .collect(Collectors.toMap(OrderReservationQuery.Summary::productId, Function.identity()));
        record Balance(String sku, int onHand, int reserved) {}
        var balances = ids.isEmpty() ? Map.<Long, Balance>of() : namedJdbc.query("""
                SELECT p.id,p.sku,coalesce(i.on_hand,0) AS on_hand,coalesce(i.reserved,0) AS reserved
                FROM product p LEFT JOIN inventory i ON i.product_id=p.id AND i.branch_id=:branch
                WHERE p.id IN (:ids)
                """, Map.of("ids", ids, "branch", document.getBranchId()), (rs, n) -> Map.entry(rs.getLong("id"),
                new Balance(rs.getString("sku"), rs.getInt("on_hand"), rs.getInt("reserved"))))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var lines = document.getItems().stream().sorted(java.util.Comparator.comparing(item -> item.getProductId())).map(item -> {
            var balance = balances.get(item.getProductId());
            Integer delta = item.getCountedQuantity() == null ? null : item.getCountedQuantity() - item.getQuantity();
            Long projected = delta == null ? null : (long) balance.onHand() + delta;
            var order = orders.get(item.getProductId());
            return new StocktakeReconciliation.Line(item.getProductId(), balance.sku(), item.getQuantity(),
                    item.getCountedQuantity(), delta, balance.onHand(), balance.reserved(), projected,
                    projected == null ? 0 : Math.max(0L, balance.reserved() - projected),
                    balance.reserved() - (order == null ? 0L : order.quantity()),
                    order == null ? 0L : order.orderCount(), order == null ? List.of() : order.orders());
        }).toList();
        boolean ready = !lines.isEmpty() && lines.stream().allMatch(line -> line.projectedOnHand() != null
                && line.shortfall() == 0 && line.projectedOnHand() <= Integer.MAX_VALUE);
        return new StocktakeReconciliation(document.getId(), document.getBranchId(), ready, lines);
    }

    public void validateForApproval(StockDocument document) {
        // Same product-before-inventory order as every stock writer; retain all locks until approval commits.
        document.getItems().stream().map(item -> item.getProductId()).sorted().forEach(product -> {
            productCost.lockForStockChange(product);
            jdbc.queryForList("SELECT on_hand FROM inventory WHERE branch_id=? AND product_id=? FOR NO KEY UPDATE",
                    document.getBranchId(), product);
        });
        var state = inspect(document);
        if (state.lines().stream().anyMatch(line -> line.projectedOnHand() != null && line.projectedOnHand() > Integer.MAX_VALUE)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Tồn sau kiểm kê vượt giới hạn số lượng");
        }
        if (!state.inventoryReady()) {
            throw new AppException(ErrorCode.INSUFFICIENT_STOCK,
                    "Chưa thể duyệt kiểm kê: cần bổ sung hàng hoặc xử lý đơn giữ chỗ qua luồng được phép, rồi đối soát lại.",
                    Map.of("documentId", document.getId(), "reconciliationPath",
                            "/api/v1/stock-documents/" + document.getId() + "/reconciliation", "lines", state.lines()));
        }
    }
}
