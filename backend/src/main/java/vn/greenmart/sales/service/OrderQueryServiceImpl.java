package vn.greenmart.sales.service;

import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.sales.dto.BranchRef;
import vn.greenmart.sales.dto.CustomerRef;
import vn.greenmart.sales.dto.OrderSummaryResponse;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class OrderQueryServiceImpl implements OrderQueryService {

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
            JOIN customer c ON c.id = o.customer_id
        """ + whereClause + " ORDER BY o.created_at DESC LIMIT ? OFFSET ?";

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(pageSize);
        queryParams.add(pageable.getOffset());

        List<OrderSummaryResponse> content = jdbcTemplate.query(querySql, (rs, rowNum) -> {
            Timestamp createdTs = rs.getTimestamp("created_at");
            Timestamp completedTs = rs.getTimestamp("completed_at");

            return new OrderSummaryResponse(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("channel_code"),
                rs.getString("external_order_id"),
                new BranchRef(rs.getLong("branch_id"), rs.getString("branch_code"), rs.getString("branch_name")),
                new CustomerRef(rs.getLong("customer_id"), rs.getString("customer_phone"), rs.getString("customer_name")),
                rs.getString("status"),
                rs.getInt("item_count"),
                rs.getLong("total"),
                createdTs != null ? createdTs.toInstant() : null,
                completedTs != null ? completedTs.toInstant() : null
            );
        }, queryParams.toArray());

        return new PageResponse<>(content, pageNumber, pageSize, total, totalPages);
    }
}
