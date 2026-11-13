package vn.greenmart.inventory.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.inventory.dto.StockAlertResponse;
import vn.greenmart.inventory.dto.StockStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StockAlertQueryService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    @Transactional(readOnly = true)
    public List<StockAlertResponse> listOpen(Long branchId) {
        SecurityUtils.requireRole("OWNER", "BRANCH_MANAGER");
        if (branchId != null && branchId <= 0) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "branchId phải lớn hơn 0");
        }
        var branches = SecurityUtils.getAccessibleBranchIds(jdbc);
        if (branchId != null) {
            if (branches != null && !branches.contains(branchId)) {
                throw new AppException(ErrorCode.BRANCH_FORBIDDEN);
            }
            branches = List.of(branchId);
        }
        if (branches != null && branches.isEmpty()) return List.of();

        String scope = branches == null ? "" : " AND a.branch_id IN (:branches)";
        Map<String, ?> params = branches == null ? Map.of() : Map.of("branches", branches);
        return namedJdbc.query("""
                SELECT a.id,a.branch_id,a.product_id,p.sku,p.name,a.severity,
                       i.on_hand-i.reserved AS available,i.reorder_point,a.opened_at
                FROM stock_alert a
                JOIN inventory i ON i.branch_id=a.branch_id AND i.product_id=a.product_id
                JOIN product p ON p.id=a.product_id
                WHERE a.closed_at IS NULL
                """ + scope + " ORDER BY CASE a.severity WHEN 'OUT' THEN 0 ELSE 1 END, a.opened_at DESC, a.id DESC",
                params, (rs, row) -> new StockAlertResponse(
                        rs.getLong("id"), rs.getLong("branch_id"), rs.getLong("product_id"),
                        rs.getString("sku"), rs.getString("name"), StockStatus.valueOf(rs.getString("severity")),
                        rs.getInt("available"), rs.getObject("reorder_point", Integer.class),
                        rs.getObject("opened_at", OffsetDateTime.class)));
    }
}
