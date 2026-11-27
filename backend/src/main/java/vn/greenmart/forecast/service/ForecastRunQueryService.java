package vn.greenmart.forecast.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
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
    public record Run(
            long id,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            String modelVersion,
            BigDecimal mae,
            BigDecimal wape,
            BigDecimal baselineMae,
            BigDecimal baselineWape,
            boolean legacy,
            Integer metricHorizon,
            String aggregation,
            String postprocessVersion,
            String metricProtocolVersion,
            LocalDate cutoffDate,
            LocalDate evaluationCutoffDate,
            LocalDate testStartDate,
            LocalDate testEndDate,
            String stockoutMode,
            String baselineName,
            String dataSource,
            String dataSourceHash) {}

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
        var rows = jdbc.query(
                "SELECT id, status, started_at, finished_at, model_version, mae, wape, baseline_mae, baseline_wape, "
                        + "metric_horizon, aggregation, postprocess_version, metric_protocol_version, "
                        + "cutoff_date, evaluation_cutoff_date, test_start_date, test_end_date, "
                        + "stockout_mode, baseline_name, data_source, data_source_hash"
                        + where + " ORDER BY started_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, index) -> {
                    String st = rs.getString("status");
                    boolean done = "DONE".equals(st);
                    String protocolVer = done ? rs.getString("metric_protocol_version") : null;
                    boolean legacy = done && protocolVer == null;
                    return new Run(
                            rs.getLong("id"),
                            st,
                            rs.getObject("started_at", OffsetDateTime.class),
                            rs.getObject("finished_at", OffsetDateTime.class),
                            rs.getString("model_version"),
                            done ? rs.getBigDecimal("mae") : null,
                            done ? rs.getBigDecimal("wape") : null,
                            done ? rs.getBigDecimal("baseline_mae") : null,
                            done ? rs.getBigDecimal("baseline_wape") : null,
                            legacy,
                            done ? rs.getObject("metric_horizon", Integer.class) : null,
                            done ? rs.getString("aggregation") : null,
                            done ? rs.getString("postprocess_version") : null,
                            protocolVer,
                            done ? rs.getObject("cutoff_date", LocalDate.class) : null,
                            done ? rs.getObject("evaluation_cutoff_date", LocalDate.class) : null,
                            done ? rs.getObject("test_start_date", LocalDate.class) : null,
                            done ? rs.getObject("test_end_date", LocalDate.class) : null,
                            done ? rs.getString("stockout_mode") : null,
                            done ? rs.getString("baseline_name") : null,
                            done ? rs.getString("data_source") : null,
                            done ? rs.getString("data_source_hash") : null);
                }, status, status, pageable.getPageSize(), pageable.getOffset());
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }
}
