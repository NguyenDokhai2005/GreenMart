package vn.greenmart.inventory.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.pagination.DateFilterUtils;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.common.pagination.PageUtils;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.inventory.domain.StockDocumentStatus;
import vn.greenmart.inventory.domain.StockDocumentType;
import vn.greenmart.inventory.dto.StockDocumentResponse.BranchRef;
import vn.greenmart.inventory.dto.StockDocumentResponse.UserRef;
import vn.greenmart.inventory.dto.StockDocumentSummary;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockDocumentQueryService {
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "d.id", "createdAt", "d.created_at", "code", "d.code", "type", "d.type",
            "status", "d.status", "approvedAt", "d.approved_at", "receivedAt", "d.received_at");

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<StockDocumentSummary> list(Long branchId, StockDocumentType type,
            List<StockDocumentStatus> statuses, LocalDate from, LocalDate to, String q,
            Integer page, Integer size, String[] sort) {
        SecurityUtils.requireRole("OWNER", "BRANCH_MANAGER", "STOREKEEPER");
        var branches = SecurityUtils.getAccessibleBranchIds(jdbc);
        if (branchId != null && branchId <= 0) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "branchId phải lớn hơn 0");
        }
        if (branchId != null && branches != null && !branches.contains(branchId)) {
            throw new AppException(ErrorCode.BRANCH_FORBIDDEN);
        }
        var dates = DateFilterUtils.toOffsetDateTimeRange(from, to);
        var pageable = PageUtils.createPageable(page, size, sort, Sort.by(Sort.Direction.DESC, "createdAt"),
                SORT_COLUMNS.keySet());
        if (branches != null && branches.isEmpty()) {
            return PageResponse.from(org.springframework.data.domain.Page.empty(pageable));
        }

        var params = new MapSqlParameterSource();
        var predicates = new ArrayList<String>();
        if (branches != null) {
            predicates.add("(d.branch_id IN (:branches) OR d.to_branch_id IN (:branches))");
            params.addValue("branches", branches);
        }
        if (branchId != null) {
            predicates.add("(d.branch_id = :branchId OR d.to_branch_id = :branchId)");
            params.addValue("branchId", branchId);
        }
        if (type != null) {
            predicates.add("d.type = :type");
            params.addValue("type", type.name());
        }
        if (statuses != null && !statuses.isEmpty()) {
            predicates.add("d.status IN (:statuses)");
            params.addValue("statuses", statuses.stream().map(StockDocumentStatus::name).toList());
        }
        if (dates.startInclusive() != null) {
            predicates.add("d.created_at >= :from");
            params.addValue("from", dates.startInclusive());
        }
        if (dates.endExclusive() != null) {
            predicates.add("d.created_at < :until");
            params.addValue("until", dates.endExclusive());
        }
        if (q != null && !q.isBlank()) {
            predicates.add("(strpos(lower(d.code), lower(:q)) > 0 OR strpos(lower(d.supplier), lower(:q)) > 0)");
            params.addValue("q", q.trim());
        }
        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        long total = namedJdbc.queryForObject("SELECT count(*) FROM stock_document d" + where, params, Long.class);
        String order = pageable.getSort().stream()
                .map(entry -> SORT_COLUMNS.get(entry.getProperty()) + " " + entry.getDirection())
                .collect(Collectors.joining(", "));
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        var rows = namedJdbc.query("""
                SELECT d.*, b.code AS branch_code, b.name AS branch_name,
                       dest.code AS destination_code, dest.name AS destination_name, u.full_name,
                       items.item_count, items.shortage
                FROM stock_document d
                JOIN branch b ON b.id = d.branch_id
                LEFT JOIN branch dest ON dest.id = d.to_branch_id
                JOIN app_user u ON u.id = d.created_by
                CROSS JOIN LATERAL (
                    SELECT count(*) AS item_count, coalesce(bool_or(received_quantity < quantity), false) AS shortage
                    FROM stock_document_item WHERE document_id = d.id
                ) items
                """ + where + " ORDER BY " + order + ", d.id DESC LIMIT :limit OFFSET :offset", params,
                (rs, row) -> {
                    Long destination = rs.getObject("to_branch_id", Long.class);
                    var status = StockDocumentStatus.valueOf(rs.getString("status"));
                    return new StockDocumentSummary(rs.getLong("id"), rs.getString("code"),
                            StockDocumentType.valueOf(rs.getString("type")),
                            new BranchRef(rs.getLong("branch_id"), rs.getString("branch_code"), rs.getString("branch_name")),
                            destination == null ? null : new BranchRef(destination,
                                    rs.getString("destination_code"), rs.getString("destination_name")),
                            status, rs.getInt("item_count"),
                            status == StockDocumentStatus.RECEIVED && rs.getBoolean("shortage"),
                            new UserRef(rs.getLong("created_by"), rs.getString("full_name")),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getObject("approved_at", OffsetDateTime.class),
                            rs.getObject("received_at", OffsetDateTime.class));
                });
        return PageResponse.from(new org.springframework.data.domain.PageImpl<>(rows, pageable, total));
    }
}
