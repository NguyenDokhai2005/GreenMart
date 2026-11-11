package vn.greenmart.inventory.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.catalog.service.ProductCostService;

@Service
@RequiredArgsConstructor
public class ChannelStockAvailability {
    private final NamedParameterJdbcTemplate jdbc;
    private final ProductCostService costs;

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Map<Long, Integer>> lockAvailable(List<Long> branches, Set<Long> products) {
        // Preserve the product-before-inventory lock order used by POS and stock documents.
        products.stream().sorted().forEach(costs::lockForStockChange);
        var balances = new HashMap<Long, Map<Long, Integer>>();
        jdbc.query("""
                SELECT branch_id, product_id, on_hand - reserved AS available
                FROM inventory WHERE branch_id IN (:branches) AND product_id IN (:products)
                ORDER BY product_id, branch_id FOR UPDATE
                """, Map.of("branches", branches, "products", products), rs -> {
            balances.computeIfAbsent(rs.getLong("branch_id"), ignored -> new HashMap<>())
                    .put(rs.getLong("product_id"), rs.getInt("available"));
        });
        return balances;
    }
}
