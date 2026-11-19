package vn.greenmart.forecast.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.greenmart.forecast.dto.ForecastResponse.BranchRef;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ForecastQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public record ForecastRow(long runId, OffsetDateTime generatedAt, String modelVersion,
                              BranchRef branch, long productId, String sku, String productName, String unit,
                              int horizon, LocalDate date, BigDecimal predictedQty, Integer actualQty) {}

    public List<ForecastRow> find(long branchId, long productId, Long runId) {
        var parameters = new MapSqlParameterSource()
                .addValue("branchId", branchId)
                .addValue("productId", productId)
                .addValue("runId", runId, Types.BIGINT);
        return jdbc.query("""
                WITH selected_run AS (
                    SELECT id, finished_at, model_version
                    FROM forecast_run
                    WHERE status = 'DONE' AND (:runId IS NULL OR id = :runId)
                    ORDER BY finished_at DESC NULLS LAST, id DESC
                    LIMIT 1
                )
                SELECT r.id AS run_id, r.finished_at, r.model_version,
                       b.id AS branch_id, b.code AS branch_code, b.name AS branch_name,
                       p.id AS product_id, p.sku, p.name AS product_name, p.unit,
                       f.horizon, f.target_date, f.predicted_qty, ds.qty_net
                FROM selected_run r
                JOIN forecast_result f ON f.run_id = r.id
                JOIN branch b ON b.id = f.branch_id
                JOIN product p ON p.id = f.product_id
                LEFT JOIN daily_sales ds ON ds.branch_id = f.branch_id AND ds.product_id = f.product_id
                                        AND ds.sale_date = f.target_date
                WHERE f.branch_id = :branchId AND f.product_id = :productId
                ORDER BY f.horizon, f.target_date
                """, parameters, (rs, row) -> new ForecastRow(
                        rs.getLong("run_id"), rs.getObject("finished_at", OffsetDateTime.class),
                        rs.getString("model_version"),
                        new BranchRef(rs.getLong("branch_id"), rs.getString("branch_code"), rs.getString("branch_name")),
                        rs.getLong("product_id"), rs.getString("sku"), rs.getString("product_name"), rs.getString("unit"),
                        rs.getInt("horizon"), rs.getObject("target_date", LocalDate.class),
                        rs.getBigDecimal("predicted_qty"), rs.getObject("qty_net", Integer.class)));
    }
}
