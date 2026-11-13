package vn.greenmart.sales.service;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.sales.dto.BranchRef;
import vn.greenmart.sales.dto.CustomerRef;
import vn.greenmart.sales.dto.OrderSummaryResponse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class OrderQueryServiceImpl implements OrderQueryService {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final Map<String, String> SORT_COLUMN_MAP = Map.of(
            "id", "o.id",
            "code", "o.code",
            "total", "o.total",
            "status", "o.status",
            "createdAt", "o.created_at",
            "completedAt", "o.completed_at"
    );

    private final JdbcTemplate jdbcTemplate;

    public OrderQueryServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PageResponse<OrderSummaryResponse> findByCustomer(Long customerId, Collection<Long> branchIds, Pageable pageable) {
        if (branchIds != null && branchIds.isEmpty()) {
            return new PageResponse<>(Collections.emptyList(), pageable.getPageNumber(), pageable.getPageSize(), 0L, 0);
        }

        StringBuilder whereClause = new StringBuilder(" WHERE o.customer_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(customerId);

        if (branchIds != null) {
            String inSql = String.join(",", Collections.nCopies(branchIds.size(), "?"));
            whereClause.append(" AND o.branch_id IN (").append(inSql).append(") ");
            params.addAll(branchIds);
        }

        // 1. Đếm tổng số đơn
        String countSql = "SELECT COUNT(*) FROM sales_order o " + whereClause;
        Long totalElements = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        long total = (totalElements != null) ? totalElements : 0L;

        int pageSize = pageable.getPageSize();
        int pageNumber = pageable.getPageNumber();
        int totalPages = (int) Math.ceil((double) total / pageSize);

        if (total == 0) {
            return new PageResponse<>(Collections.emptyList(), pageNumber, pageSize, 0L, 0);
        }

        // 2. Lấy dữ liệu phân trang
        String querySql = """
            SELECT o.id, o.code, sc.code AS channel_code, o.external_order_id,
                   b.id AS branch_id, b.code AS branch_code, b.name AS branch_name,
                   c.id AS customer_id, c.phone AS customer_phone, c.name AS customer_name,
                   o.status, o.total, o.created_at, o.completed_at,
                   COALESCE((SELECT SUM(oi.quantity) FROM order_item oi WHERE oi.order_id = o.id), 0) AS item_count
            FROM sales_order o
            JOIN sales_channel sc ON sc.id = o.channel_id
            JOIN branch b ON b.id = o.branch_id
            LEFT JOIN customer c ON c.id = o.customer_id
        """ + whereClause + " ORDER BY o.created_at DESC, o.id DESC LIMIT ? OFFSET ?";

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(pageSize);
        queryParams.add(pageable.getOffset());

        List<OrderSummaryResponse> content = jdbcTemplate.query(querySql, (rs, rowNum) -> mapRowToOrderSummary(rs), queryParams.toArray());

        return new PageResponse<>(content, pageNumber, pageSize, total, totalPages);
    }

    @Override
    public PageResponse<OrderSummaryResponse> searchOrders(
            Long branchId,
            Collection<Long> accessibleBranchIds,
            String channel,
            List<String> statuses,
            LocalDate from,
            LocalDate to,
            Long customerId,
            String q,
            Pageable pageable
    ) {
        int pageSize = pageable.getPageSize();
        int pageNumber = pageable.getPageNumber();

        if (accessibleBranchIds != null && accessibleBranchIds.isEmpty()) {
            return new PageResponse<>(Collections.emptyList(), pageNumber, pageSize, 0L, 0);
        }

        StringBuilder fromAndJoin = new StringBuilder("""
            FROM sales_order o
            JOIN sales_channel sc ON sc.id = o.channel_id
            JOIN branch b ON b.id = o.branch_id
            LEFT JOIN customer c ON c.id = o.customer_id
        """);

        StringBuilder whereClause = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (branchId != null) {
            whereClause.append(" AND o.branch_id = ? ");
            params.add(branchId);
        } else if (accessibleBranchIds != null) {
            String inSql = String.join(",", Collections.nCopies(accessibleBranchIds.size(), "?"));
            whereClause.append(" AND o.branch_id IN (").append(inSql).append(") ");
            params.addAll(accessibleBranchIds);
        }

        if (channel != null && !channel.isBlank()) {
            whereClause.append(" AND sc.code = ? ");
            params.add(channel.trim().toUpperCase());
        }

        if (statuses != null && !statuses.isEmpty()) {
            String inSql = String.join(",", Collections.nCopies(statuses.size(), "?"));
            whereClause.append(" AND o.status IN (").append(inSql).append(") ");
            params.addAll(statuses);
        }

        if (from != null) {
            Instant fromInstant = from.atStartOfDay(VN_ZONE).toInstant();
            whereClause.append(" AND o.created_at >= ? ");
            params.add(Timestamp.from(fromInstant));
        }

        if (to != null) {
            Instant toExclusive = to.plusDays(1).atStartOfDay(VN_ZONE).toInstant();
            whereClause.append(" AND o.created_at < ? ");
            params.add(Timestamp.from(toExclusive));
        }

        if (customerId != null) {
            whereClause.append(" AND o.customer_id = ? ");
            params.add(customerId);
        }

        if (q != null && !q.trim().isEmpty()) {
            String escaped = q.trim()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
            String kw = "%" + escaped + "%";
            whereClause.append(" AND (o.code ILIKE ? ESCAPE '\\' OR COALESCE(o.external_order_id, '') ILIKE ? ESCAPE '\\' OR COALESCE(c.phone, '') ILIKE ? ESCAPE '\\') ");
            params.add(kw);
            params.add(kw);
            params.add(kw);
        }

        String countSql = "SELECT COUNT(*) " + fromAndJoin + whereClause;
        Long totalElements = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        long total = (totalElements != null) ? totalElements : 0L;
        int totalPages = (int) Math.ceil((double) total / pageSize);

        if (total == 0) {
            return new PageResponse<>(Collections.emptyList(), pageNumber, pageSize, 0L, 0);
        }

        String orderByClause = buildOrderByClause(pageable.getSort());

        String querySql = """
            SELECT o.id, o.code, sc.code AS channel_code, o.external_order_id,
                   b.id AS branch_id, b.code AS branch_code, b.name AS branch_name,
                   c.id AS customer_id, c.phone AS customer_phone, c.name AS customer_name,
                   o.status, o.total, o.created_at, o.completed_at,
                   COALESCE((SELECT SUM(oi.quantity) FROM order_item oi WHERE oi.order_id = o.id), 0) AS item_count
        """ + fromAndJoin + whereClause + orderByClause + " LIMIT ? OFFSET ?";

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(pageSize);
        queryParams.add(pageable.getOffset());

        List<OrderSummaryResponse> content = jdbcTemplate.query(
                querySql,
                (rs, rowNum) -> mapRowToOrderSummary(rs),
                queryParams.toArray()
        );

        return new PageResponse<>(content, pageNumber, pageSize, total, totalPages);
    }

    private String buildOrderByClause(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return " ORDER BY o.created_at DESC, o.id DESC ";
        }
        List<String> clauses = new ArrayList<>();
        for (Sort.Order order : sort) {
            String col = SORT_COLUMN_MAP.getOrDefault(order.getProperty(), "o.created_at");
            String dir = order.isAscending() ? "ASC" : "DESC";
            clauses.add(col + " " + dir);
        }
        clauses.add("o.id DESC");
        return " ORDER BY " + String.join(", ", clauses) + " ";
    }

    private OrderSummaryResponse mapRowToOrderSummary(ResultSet rs) throws SQLException {
        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp completedTs = rs.getTimestamp("completed_at");

        Long custId = rs.getObject("customer_id", Long.class);
        CustomerRef customerRef = (custId != null)
                ? new CustomerRef(custId, rs.getString("customer_phone"), rs.getString("customer_name"))
                : null;

        return new OrderSummaryResponse(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("channel_code"),
                rs.getString("external_order_id"),
                new BranchRef(rs.getLong("branch_id"), rs.getString("branch_code"), rs.getString("branch_name")),
                customerRef,
                rs.getString("status"),
                rs.getInt("item_count"),
                rs.getLong("total"),
                createdTs != null ? createdTs.toInstant() : null,
                completedTs != null ? completedTs.toInstant() : null
        );
    }
}
