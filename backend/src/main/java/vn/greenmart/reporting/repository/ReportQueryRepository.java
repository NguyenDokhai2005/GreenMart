package vn.greenmart.reporting.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.greenmart.reporting.dto.ProductSalesResponse;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class ReportQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public record DailyRevenueRow(String bucket, long grossRevenue, long orderCount) {}
    public record DailyReturnRow(String bucket, long returnAmount, long returnCount) {}

    public List<DailyRevenueRow> querySales(
            LocalDate from,
            LocalDate to,
            String groupBy,
            List<Long> allowedBranches,
            Long branchId,
            Long channelId
    ) {
        OffsetDateTime fromTs = from.atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
        OffsetDateTime toTsExclusive = to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();

        String dateFormat = "month".equalsIgnoreCase(groupBy) ? "YYYY-MM" : "YYYY-MM-DD";
        String dateExpression = "week".equalsIgnoreCase(groupBy)
                ? "date_trunc('week', o.completed_at AT TIME ZONE 'Asia/Ho_Chi_Minh')"
                : "o.completed_at AT TIME ZONE 'Asia/Ho_Chi_Minh'";

        var params = new MapSqlParameterSource()
                .addValue("fromTs", fromTs)
                .addValue("toTs", toTsExclusive);

        var where = new ArrayList<String>();
        where.add("o.status = 'COMPLETED'");
        where.add("o.completed_at >= :fromTs");
        where.add("o.completed_at < :toTs");

        if (branchId != null) {
            where.add("o.branch_id = :branchId");
            params.addValue("branchId", branchId);
        } else if (allowedBranches != null) {
            if (allowedBranches.isEmpty()) {
                return Collections.emptyList();
            }
            where.add("o.branch_id IN (:allowedBranches)");
            params.addValue("allowedBranches", allowedBranches);
        }

        if (channelId != null) {
            where.add("o.channel_id = :channelId");
            params.addValue("channelId", channelId);
        }

        String sql = String.format("""
                SELECT 
                    to_char(%s, '%s') AS bucket,
                    COALESCE(SUM(o.total), 0) AS gross_revenue,
                    COUNT(o.id) AS order_count
                FROM sales_order o
                WHERE %s
                GROUP BY 1
                ORDER BY bucket ASC
                """, dateExpression, dateFormat, String.join(" AND ", where));

        return jdbc.query(sql, params, (rs, rowNum) -> new DailyRevenueRow(
                rs.getString("bucket"),
                rs.getLong("gross_revenue"),
                rs.getLong("order_count")
        ));
    }

    public List<DailyReturnRow> queryReturns(
            LocalDate from,
            LocalDate to,
            String groupBy,
            List<Long> allowedBranches,
            Long branchId,
            Long channelId
    ) {
        OffsetDateTime fromTs = from.atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
        OffsetDateTime toTsExclusive = to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();

        String dateFormat = "month".equalsIgnoreCase(groupBy) ? "YYYY-MM" : "YYYY-MM-DD";
        String dateExpression = "week".equalsIgnoreCase(groupBy)
                ? "date_trunc('week', rd.approved_at AT TIME ZONE 'Asia/Ho_Chi_Minh')"
                : "rd.approved_at AT TIME ZONE 'Asia/Ho_Chi_Minh'";

        var params = new MapSqlParameterSource()
                .addValue("fromTs", fromTs)
                .addValue("toTs", toTsExclusive);

        var where = new ArrayList<String>();
        where.add("rd.status = 'APPROVED'");
        where.add("rd.approved_at >= :fromTs");
        where.add("rd.approved_at < :toTs");

        if (branchId != null) {
            where.add("rd.branch_id = :branchId");
            params.addValue("branchId", branchId);
        } else if (allowedBranches != null) {
            if (allowedBranches.isEmpty()) {
                return Collections.emptyList();
            }
            where.add("rd.branch_id IN (:allowedBranches)");
            params.addValue("allowedBranches", allowedBranches);
        }

        if (channelId != null) {
            where.add("o.channel_id = :channelId");
            params.addValue("channelId", channelId);
        }

        String sql = String.format("""
                SELECT 
                    to_char(%s, '%s') AS bucket,
                    COALESCE(SUM(rd.refund_amount), 0) AS return_amount,
                    COUNT(rd.id) AS return_count
                FROM return_document rd
                JOIN sales_order o ON rd.order_id = o.id
                WHERE %s
                GROUP BY 1
                ORDER BY bucket ASC
                """, dateExpression, dateFormat, String.join(" AND ", where));

        return jdbc.query(sql, params, (rs, rowNum) -> new DailyReturnRow(
                rs.getString("bucket"),
                rs.getLong("return_amount"),
                rs.getLong("return_count")
        ));
    }

    public List<ProductSalesResponse> queryTopProducts(
            LocalDate from,
            LocalDate to,
            List<Long> allowedBranches,
            Long branchId,
            Long channelId,
            int limit,
            boolean isAscending
    ) {
        OffsetDateTime fromTs = from.atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
        OffsetDateTime toTsExclusive = to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();

        var params = new MapSqlParameterSource()
                .addValue("fromTs", fromTs)
                .addValue("toTs", toTsExclusive)
                .addValue("limit", limit);

        var salesWhere = new ArrayList<String>();
        salesWhere.add("o.status = 'COMPLETED'");
        salesWhere.add("o.completed_at >= :fromTs");
        salesWhere.add("o.completed_at < :toTs");

        var returnsWhere = new ArrayList<String>();
        returnsWhere.add("rd.status = 'APPROVED'");
        returnsWhere.add("rd.approved_at >= :fromTs");
        returnsWhere.add("rd.approved_at < :toTs");

        if (branchId != null) {
            salesWhere.add("o.branch_id = :branchId");
            returnsWhere.add("rd.branch_id = :branchId");
            params.addValue("branchId", branchId);
        } else if (allowedBranches != null) {
            if (allowedBranches.isEmpty()) {
                return Collections.emptyList();
            }
            salesWhere.add("o.branch_id IN (:allowedBranches)");
            returnsWhere.add("rd.branch_id IN (:allowedBranches)");
            params.addValue("allowedBranches", allowedBranches);
        }

        if (channelId != null) {
            salesWhere.add("o.channel_id = :channelId");
            returnsWhere.add("o.channel_id = :channelId");
            params.addValue("channelId", channelId);
        }

        String orderDirection = isAscending ? "ASC" : "DESC";

        String sql = String.format("""
                WITH sales AS (
                    SELECT 
                        oi.product_id,
                        SUM(oi.quantity) AS sold_qty,
                        SUM(oi.line_total) AS gross_rev
                    FROM sales_order o
                    JOIN order_item oi ON oi.order_id = o.id
                    WHERE %s
                    GROUP BY oi.product_id
                ),
                returns AS (
                    SELECT 
                        oi.product_id,
                        SUM(ri.quantity) AS ret_qty,
                        SUM(ri.quantity * oi.unit_price) AS ret_amount
                    FROM return_document rd
                    JOIN sales_order o ON rd.order_id = o.id
                    JOIN return_item ri ON ri.return_id = rd.id
                    JOIN order_item oi ON ri.order_item_id = oi.id
                    WHERE %s
                    GROUP BY oi.product_id
                )
                SELECT 
                    p.id AS product_id,
                    p.sku,
                    p.name,
                    c.name AS category_name,
                    COALESCE(s.sold_qty, 0) AS sold_quantity,
                    COALESCE(r.ret_qty, 0) AS returned_quantity,
                    (COALESCE(s.sold_qty, 0) - COALESCE(r.ret_qty, 0)) AS net_quantity,
                    (COALESCE(s.gross_rev, 0) - COALESCE(r.ret_amount, 0)) AS net_revenue
                FROM product p
                LEFT JOIN category c ON p.category_id = c.id
                LEFT JOIN sales s ON s.product_id = p.id
                LEFT JOIN returns r ON r.product_id = p.id
                WHERE p.is_active = true
                ORDER BY net_quantity %s, net_revenue %s, p.id ASC
                LIMIT :limit
                """, String.join(" AND ", salesWhere), String.join(" AND ", returnsWhere), orderDirection, orderDirection);

        return jdbc.query(sql, params, (rs, rowNum) -> new ProductSalesResponse(
                rs.getLong("product_id"),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("category_name"),
                rs.getLong("sold_quantity"),
                rs.getLong("returned_quantity"),
                rs.getLong("net_quantity"),
                rs.getLong("net_revenue")
        ));
    }
}
