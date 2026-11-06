package vn.greenmart.branch.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.greenmart.branch.dto.BranchSaleInfo;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class BranchServiceImpl implements BranchService {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public BranchSaleInfo getSaleInfo(Long branchId) {
        return jdbcTemplate.query("SELECT id, code, name FROM branch WHERE id = ?",
                (rs, rowNum) -> new BranchSaleInfo(rs.getLong("id"), rs.getString("code"), rs.getString("name")),
                branchId
        ).stream().findFirst().orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy chi nhánh"));
    }
}
