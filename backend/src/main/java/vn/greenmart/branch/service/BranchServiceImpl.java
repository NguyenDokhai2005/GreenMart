package vn.greenmart.branch.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import vn.greenmart.branch.dto.BranchResponse;
import vn.greenmart.branch.dto.BranchSaleInfo;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BranchServiceImpl implements BranchService {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;

    @Override
    public List<BranchResponse> list(Boolean active) {
        var branchIds = SecurityUtils.getAccessibleBranchIds(jdbcTemplate);
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }

        var params = new MapSqlParameterSource();
        String sql = "SELECT id, code, name, address, is_active FROM branch WHERE TRUE";
        if (branchIds != null) {
            sql += " AND id IN (:branchIds)";
            params.addValue("branchIds", branchIds);
        }
        if (active != null) {
            sql += " AND is_active = :active";
            params.addValue("active", active);
        }

        return namedJdbc.query(sql + " ORDER BY code", params, (rs, rowNum) -> new BranchResponse(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("address"), rs.getBoolean("is_active")));
    }

    @Override
    public BranchSaleInfo getSaleInfo(Long branchId) {
        return jdbcTemplate.query("SELECT id, code, name FROM branch WHERE id = ?",
                (rs, rowNum) -> new BranchSaleInfo(rs.getLong("id"), rs.getString("code"), rs.getString("name")),
                branchId
        ).stream().findFirst().orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy chi nhánh"));
    }
}
