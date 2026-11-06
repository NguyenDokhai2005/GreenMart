package vn.greenmart.inventory.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.greenmart.inventory.dto.InventoryItemResponse;
import vn.greenmart.inventory.dto.StockStatus;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read model for inventory; no writes or repositories from other modules. */
@Repository
@RequiredArgsConstructor
public class InventoryQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public static final Map<String, String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("branchId", "i.branch_id"), Map.entry("productId", "i.product_id"),
            Map.entry("sku", "p.sku"), Map.entry("productName", "p.name"),
            Map.entry("onHand", "i.on_hand"), Map.entry("reserved", "i.reserved"),
            Map.entry("available", "(i.on_hand-i.reserved)"), Map.entry("reorderPoint", "i.reorder_point"),
            Map.entry("stockStatus", "stock_status"), Map.entry("version", "i.version"),
            Map.entry("updatedAt", "i.updated_at"));
    private static final String STATUS = """
            CASE WHEN i.on_hand-i.reserved=0 THEN 'OUT'
                 WHEN i.on_hand-i.reserved<=i.reorder_point THEN 'LOW'
                 ELSE 'OK' END
            """;

    public Page<InventoryItemResponse> search(Long branchId, List<Long> allowedBranches,
            Long productId, Long categoryId, String q, StockStatus stockStatus, Pageable pageable) {
        if (allowedBranches != null && allowedBranches.isEmpty()) return Page.empty(pageable);
        var params = new MapSqlParameterSource();
        var predicates = new ArrayList<String>();
        if (allowedBranches != null) {
            predicates.add("i.branch_id IN (:allowedBranches)");
            params.addValue("allowedBranches", allowedBranches);
        }
        if (branchId != null) {
            predicates.add("i.branch_id=:branchId");
            params.addValue("branchId", branchId);
        }
        if (productId != null) {
            predicates.add("i.product_id=:productId");
            params.addValue("productId", productId);
        }
        if (categoryId != null) {
            predicates.add("p.category_id=:categoryId");
            params.addValue("categoryId", categoryId);
        }
        if (q != null && !q.isBlank()) {
            predicates.add("(strpos(lower(p.name),:q)>0 OR strpos(lower(p.sku),:q)>0 OR strpos(lower(p.barcode),:q)>0)");
            params.addValue("q", q.trim().toLowerCase(Locale.ROOT));
        }
        if (stockStatus != null) {
            predicates.add("(" + STATUS + ")=:stockStatus");
            params.addValue("stockStatus", stockStatus.name());
        }
        String from = " FROM inventory i JOIN product p ON p.id=i.product_id JOIN branch b ON b.id=i.branch_id"
                + (predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates));
        long total = jdbc.queryForObject("SELECT count(*)" + from, params, Long.class);
        var orders = new ArrayList<String>();
        pageable.getSort().forEach(order -> {
            String column = SORT_COLUMNS.get(order.getProperty());
            if (column == null) throw new IllegalArgumentException("Unsupported inventory sort");
            orders.add(column + (order.isAscending() ? " ASC" : " DESC"));
        });
        // A unique tie-breaker prevents unstable pages when the requested sort has ties.
        orders.add("i.branch_id ASC");
        orders.add("i.product_id ASC");
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        var content = jdbc.query("""
                SELECT i.*,b.code AS branch_code,b.name AS branch_name,
                       p.sku,p.name AS product_name,p.unit,
                """ + STATUS + " AS stock_status" + from
                + " ORDER BY " + String.join(",", orders) + " LIMIT :limit OFFSET :offset", params,
                (rs, rowNum) -> new InventoryItemResponse(
                        new InventoryItemResponse.BranchRef(rs.getLong("branch_id"), rs.getString("branch_code"), rs.getString("branch_name")),
                        rs.getLong("product_id"), rs.getString("sku"), rs.getString("product_name"), rs.getString("unit"),
                        rs.getInt("on_hand"), rs.getInt("reserved"), rs.getInt("on_hand") - rs.getInt("reserved"),
                        rs.getObject("reorder_point", Integer.class), StockStatus.valueOf(rs.getString("stock_status")),
                        rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class)));
        return new PageImpl<>(content, pageable, total);
    }
}
