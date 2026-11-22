package vn.greenmart.sales.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderReservationQueryImpl implements OrderReservationQuery {
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<Summary> find(Long branchId, Collection<Long> productIds) {
        if (productIds.isEmpty()) return List.of();
        record Row(Long productId, long quantity, long count, OrderRef order) {}
        var rows = jdbc.query("""
                SELECT * FROM (
                    SELECT i.product_id,o.id,o.code,o.status,sum(i.quantity) AS quantity,
                           sum(sum(i.quantity)) OVER (PARTITION BY i.product_id) AS total_quantity,
                           count(*) OVER (PARTITION BY i.product_id) AS order_count,
                           row_number() OVER (PARTITION BY i.product_id ORDER BY o.id) AS position
                    FROM order_item i JOIN sales_order o ON o.id=i.order_id
                    WHERE o.branch_id=:branch AND o.status IN ('RESERVED','CONFIRMED')
                      AND i.product_id IN (:products)
                    GROUP BY i.product_id,o.id,o.code,o.status
                ) reservations WHERE position<=20 ORDER BY product_id,id
                """, Map.of("branch", branchId, "products", productIds), (rs, n) -> new Row(
                rs.getLong("product_id"), rs.getLong("total_quantity"), rs.getLong("order_count"),
                new OrderRef(rs.getLong("id"), rs.getString("code"), rs.getString("status"), rs.getLong("quantity"))));
        var grouped = new LinkedHashMap<Long, List<Row>>();
        rows.forEach(row -> grouped.computeIfAbsent(row.productId(), key -> new ArrayList<>()).add(row));
        return grouped.entrySet().stream().map(entry -> {
            var first = entry.getValue().getFirst();
            return new Summary(entry.getKey(), first.quantity(), first.count(),
                    entry.getValue().stream().map(Row::order).toList());
        }).toList();
    }
}
