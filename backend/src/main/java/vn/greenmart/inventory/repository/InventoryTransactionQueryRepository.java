package vn.greenmart.inventory.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.greenmart.inventory.domain.InventoryReferenceType;
import vn.greenmart.inventory.domain.InventoryTransactionType;
import vn.greenmart.inventory.dto.InventoryTransactionFilter;
import vn.greenmart.inventory.dto.InventoryTransactionResponse;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Read-only ledger projection. References are joined by type and permitted document branch. */
@Repository
@RequiredArgsConstructor
public class InventoryTransactionQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public Page<InventoryTransactionResponse> search(InventoryTransactionFilter filter,
            List<Long> allowedBranches, Pageable pageable) {
        if (allowedBranches != null && allowedBranches.isEmpty()) return Page.empty(pageable);
        var params = new MapSqlParameterSource();
        var where = new ArrayList<String>();
        if (allowedBranches != null) {
            where.add("t.branch_id IN (:allowed)");
            params.addValue("allowed", allowedBranches);
        }
        if (filter.branchId() != null) {
            where.add("t.branch_id=:branchId"); params.addValue("branchId", filter.branchId());
        }
        if (filter.productId() != null) {
            where.add("t.product_id=:productId"); params.addValue("productId", filter.productId());
        }
        if (filter.type() != null) {
            where.add("t.type=:type"); params.addValue("type", filter.type().name());
        }
        if (filter.refType() != null) {
            where.add("t.ref_type=:refType"); params.addValue("refType", filter.refType().name());
        }
        if (filter.refId() != null) {
            where.add("t.ref_id=:refId"); params.addValue("refId", filter.refId());
        }
        if (filter.from() != null) {
            where.add("t.created_at>=:from");
            params.addValue("from", filter.from().atStartOfDay(BUSINESS_ZONE).toOffsetDateTime());
        }
        if (filter.to() != null) {
            where.add("t.created_at<:until");
            params.addValue("until", filter.to().plusDays(1).atStartOfDay(BUSINESS_ZONE).toOffsetDateTime());
        }
        String predicate = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        long total = jdbc.queryForObject("SELECT count(*) FROM inventory_transaction t" + predicate, params, Long.class);
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        var rows = jdbc.query("""
                SELECT t.*,p.sku,COALESCE(o.code,s.code,r.code) AS ref_code,
                       u.id AS actor_id,u.full_name AS actor_name
                FROM inventory_transaction t
                JOIN product p ON p.id=t.product_id
                LEFT JOIN sales_order o ON t.ref_type='ORDER' AND o.id=t.ref_id AND o.branch_id=t.branch_id
                LEFT JOIN stock_document s ON t.ref_type='STOCK_DOCUMENT' AND s.id=t.ref_id
                    AND (s.branch_id=t.branch_id OR s.to_branch_id=t.branch_id)
                LEFT JOIN return_document r ON t.ref_type='RETURN_DOCUMENT' AND r.id=t.ref_id AND r.branch_id=t.branch_id
                LEFT JOIN app_user u ON u.id=t.created_by
                """ + predicate + " ORDER BY t.created_at DESC,t.id DESC LIMIT :limit OFFSET :offset", params,
                (rs, row) -> {
                    Long actorId = rs.getObject("actor_id", Long.class);
                    return new InventoryTransactionResponse(rs.getLong("id"), rs.getLong("branch_id"),
                            rs.getLong("product_id"), rs.getString("sku"), InventoryTransactionType.valueOf(rs.getString("type")),
                            rs.getInt("delta_on_hand"), rs.getInt("on_hand_after"), rs.getInt("delta_reserved"), rs.getInt("reserved_after"),
                            InventoryReferenceType.valueOf(rs.getString("ref_type")), rs.getLong("ref_id"), rs.getString("ref_code"),
                            actorId == null ? null : new InventoryTransactionResponse.UserRef(actorId, rs.getString("actor_name")),
                            rs.getObject("created_at", OffsetDateTime.class));
                });
        return new PageImpl<>(rows, pageable, total);
    }
}
