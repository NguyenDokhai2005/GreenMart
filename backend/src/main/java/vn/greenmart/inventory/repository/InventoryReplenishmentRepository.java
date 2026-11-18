package vn.greenmart.inventory.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class InventoryReplenishmentRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public record Plan(long branchId, long productId, Integer reorderPoint, Integer safetyStock, BigDecimal demand,
                       LocalDate forecastDate) {}
    public record Position(Long branchId, Long productId, int available, long incoming, Integer manualReorderPoint,
                           Integer reorderPoint, Integer safetyStock, BigDecimal demand, int packSize) {}

    public boolean tryLockRefresh() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT pg_try_advisory_xact_lock(hashtextextended('greenmart.inventory.replenishment', 0))
                """, Boolean.class));
    }

    public List<Plan> plans(LocalDate day) {
        return namedJdbc.query("""
                WITH forecast_windows AS (
                    SELECT f.branch_id, f.product_id, f.run_id,
                           count(*) FILTER (WHERE f.horizon=7 AND f.target_date < :day + 7) AS days7,
                           sum(f.predicted_qty) FILTER (WHERE f.horizon=7 AND f.target_date < :day + 7) AS qty7,
                           count(*) FILTER (WHERE f.horizon=30) AS days30,
                           sum(f.predicted_qty) FILTER (WHERE f.horizon=30) AS qty30
                    FROM forecast_result f
                    JOIN forecast_run fr ON fr.id=f.run_id AND fr.status='DONE'
                    JOIN inventory i ON i.branch_id=f.branch_id AND i.product_id=f.product_id
                    WHERE f.target_date >= :day AND f.target_date < :day + 9
                      AND f.predicted_qty BETWEEN 0 AND 2147483647
                    GROUP BY f.branch_id, f.product_id, f.run_id
                ), latest AS (
                    SELECT DISTINCT ON (w.branch_id, w.product_id)
                           w.branch_id, w.product_id, w.qty7,
                           CASE WHEN w.days30=9 THEN w.qty30 ELSE w.qty7 * 9 / 7 END AS demand
                    FROM forecast_windows w JOIN forecast_run fr ON fr.id=w.run_id
                    WHERE days7=7
                    ORDER BY w.branch_id, w.product_id, fr.finished_at DESC NULLS LAST, fr.id DESC
                ), historical_predictions AS (
                    SELECT DISTINCT ON (f.branch_id, f.product_id, f.target_date)
                           f.branch_id, f.product_id, f.target_date, f.predicted_qty
                    FROM forecast_result f
                    JOIN latest l ON l.branch_id=f.branch_id AND l.product_id=f.product_id
                    JOIN forecast_run fr ON fr.id=f.run_id AND fr.status='DONE'
                    WHERE f.horizon=7 AND f.target_date >= :day - 30 AND f.target_date < :day
                      AND f.predicted_qty BETWEEN 0 AND 2147483647
                      AND coalesce(fr.finished_at,fr.started_at) <
                          (f.target_date::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')
                    ORDER BY f.branch_id, f.product_id, f.target_date,
                             fr.finished_at DESC NULLS LAST, fr.id DESC
                ), forecast_error AS (
                    SELECT h.branch_id, h.product_id, stddev_pop(ds.qty_net-h.predicted_qty) AS sigma
                    FROM historical_predictions h
                    JOIN daily_sales ds ON ds.branch_id=h.branch_id AND ds.product_id=h.product_id
                                       AND ds.sale_date=h.target_date
                    GROUP BY h.branch_id, h.product_id
                ), stock_plan AS (
                    SELECT l.branch_id, l.product_id, l.qty7, l.demand,
                           ceil(1.65 * coalesce(e.sigma,0) * sqrt(2::numeric)) AS safety_stock
                    FROM latest l
                    LEFT JOIN forecast_error e ON e.branch_id=l.branch_id AND e.product_id=l.product_id
                ), calculated AS (
                    SELECT branch_id, product_id, demand, safety_stock,
                           ceil(qty7 * 2 / 7) + safety_stock AS reorder_point
                    FROM stock_plan
                )
                SELECT i.branch_id, i.product_id, p.reorder_point::int AS reorder_point,
                       p.safety_stock::int AS safety_stock, p.demand,
                       CASE WHEN p.reorder_point IS NOT NULL THEN :day END AS forecast_date
                FROM inventory i
                LEFT JOIN calculated p ON p.branch_id=i.branch_id AND p.product_id=i.product_id
                                      AND p.reorder_point <= 2147483647
                WHERE (i.forecast_reorder_point,i.reorder_safety_stock,i.reorder_demand,i.reorder_plan_date)
                      IS DISTINCT FROM (p.reorder_point,p.safety_stock,p.demand,
                                        CASE WHEN p.reorder_point IS NOT NULL THEN :day END)
                ORDER BY i.product_id, i.branch_id
                """, Map.of("day", day), (rs, row) -> new Plan(rs.getLong("branch_id"),
                rs.getLong("product_id"), rs.getObject("reorder_point", Integer.class),
                rs.getObject("safety_stock", Integer.class), rs.getBigDecimal("demand"),
                rs.getObject("forecast_date", LocalDate.class)));
    }

    public boolean save(Plan plan) {
        return jdbc.update("""
                UPDATE inventory
                SET forecast_reorder_point=?, reorder_safety_stock=?, reorder_demand=?, reorder_plan_date=?
                WHERE branch_id=? AND product_id=?
                  AND (forecast_reorder_point,reorder_safety_stock,reorder_demand,reorder_plan_date)
                      IS DISTINCT FROM (?,?,?,?)
                """, plan.reorderPoint(), plan.safetyStock(), plan.demand(), plan.forecastDate(),
                plan.branchId(), plan.productId(), plan.reorderPoint(), plan.safetyStock(), plan.demand(),
                plan.forecastDate()) == 1;
    }

    public Optional<Position> find(Long branchId, Long productId) {
        return jdbc.query("""
                SELECT i.on_hand-i.reserved AS available, i.reorder_point, i.effective_reorder_point,
                       i.effective_safety_stock, i.effective_reorder_demand, i.reorder_pack_size,
                       (SELECT coalesce(sum(item.quantity),0)
                        FROM stock_document d JOIN stock_document_item item ON item.document_id=d.id
                        WHERE d.type='TRANSFER' AND d.status='IN_TRANSIT'
                          AND d.to_branch_id=i.branch_id AND item.product_id=i.product_id) AS incoming
                FROM inventory_replenishment i WHERE i.branch_id=? AND i.product_id=?
                """, (rs, row) -> new Position(branchId, productId, rs.getInt("available"),
                rs.getLong("incoming"), rs.getObject("reorder_point", Integer.class),
                rs.getObject("effective_reorder_point", Integer.class), rs.getObject("effective_safety_stock", Integer.class),
                rs.getBigDecimal("effective_reorder_demand"), rs.getInt("reorder_pack_size")), branchId, productId)
                .stream().findFirst();
    }

    public boolean configure(Long branchId, Long productId, Integer manualReorderPoint, int packSize) {
        return jdbc.update("""
                UPDATE inventory SET reorder_point=?, reorder_pack_size=? WHERE branch_id=? AND product_id=?
                """, manualReorderPoint, packSize, branchId, productId) == 1;
    }
}
