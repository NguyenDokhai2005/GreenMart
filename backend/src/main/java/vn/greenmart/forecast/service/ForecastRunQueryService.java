package vn.greenmart.forecast.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.ValidationErrorDetail;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.common.pagination.PageUtils;

@Service
@RequiredArgsConstructor
public class ForecastRunQueryService {
    private final JdbcTemplate jdbc;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Run(long id, String status, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                      String modelVersion, BigDecimal mae, BigDecimal wape,
                      BigDecimal baselineMae, BigDecimal baselineWape) {}

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResponse<Run> list(String status, int page, int size) {
        var pageable = PageUtils.createPageable(page, size, (String) null,
                Sort.by(Sort.Direction.DESC, "startedAt"), Set.of("startedAt"));
        if (status != null && !Set.of("RUNNING", "DONE", "FAILED").contains(status)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Trạng thái lượt chạy không hợp lệ",
                    List.of(new ValidationErrorDetail("status", "Chỉ nhận RUNNING, DONE hoặc FAILED")));
        }
        String where = " FROM forecast_run WHERE (?::text IS NULL OR status=?)";
        long total = jdbc.queryForObject("SELECT count(*)" + where, Long.class, status, status);
        var rows = jdbc.query("SELECT *" + where + " ORDER BY started_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, index) -> {
                    boolean done = "DONE".equals(rs.getString("status"));
                    return new Run(rs.getLong("id"), rs.getString("status"),
                            rs.getObject("started_at", OffsetDateTime.class),
                            rs.getObject("finished_at", OffsetDateTime.class), rs.getString("model_version"),
                            done ? rs.getBigDecimal("mae") : null, done ? rs.getBigDecimal("wape") : null,
                            done ? rs.getBigDecimal("baseline_mae") : null, done ? rs.getBigDecimal("baseline_wape") : null);
                }, status, status, pageable.getPageSize(), pageable.getOffset());
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }
}
