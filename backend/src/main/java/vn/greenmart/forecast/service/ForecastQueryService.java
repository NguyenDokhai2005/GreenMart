package vn.greenmart.forecast.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.forecast.dto.ForecastResponse;
import vn.greenmart.forecast.dto.ForecastResponse.ForecastHorizon;
import vn.greenmart.forecast.dto.ForecastResponse.ForecastPoint;
import vn.greenmart.forecast.repository.ForecastQueryRepository;
import vn.greenmart.forecast.repository.ForecastQueryRepository.ForecastRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ForecastQueryService {
    private final ForecastQueryRepository repository;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public ForecastResponse get(Long branchId, Long productId, Integer horizon, Long runId) {
        SecurityUtils.requireRole("OWNER", "BRANCH_MANAGER");
        if (branchId == null || branchId <= 0 || productId == null || productId <= 0
                || (runId != null && runId <= 0) || (horizon != null && horizon != 7 && horizon != 30)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        var allowedBranches = SecurityUtils.getAccessibleBranchIds(jdbc);
        if (allowedBranches != null && !allowedBranches.contains(branchId)) {
            throw AppException.branchForbidden(branchId);
        }

        var rows = repository.find(branchId, productId, runId);
        if (rows.isEmpty()) throw new AppException(ErrorCode.NOT_FOUND);
        var metadata = rows.getFirst();
        // Python writes D+1 as the first target date. Derive D from the saved run,
        // before filtering horizons, so later daily_sales updates cannot move it.
        var cutoffDate = rows.stream().map(ForecastRow::date)
                .min(LocalDate::compareTo).orElseThrow().minusDays(1);
        Map<Integer, List<ForecastPoint>> grouped = new LinkedHashMap<>();
        for (var row : rows) {
            if (horizon == null || row.horizon() == horizon) {
                grouped.computeIfAbsent(row.horizon(), key -> new ArrayList<>())
                        .add(new ForecastPoint(row.date(), row.predictedQty(), row.actualQty()));
            }
        }
        if (grouped.isEmpty()) throw new AppException(ErrorCode.NOT_FOUND);
        var horizons = grouped.entrySet().stream().map(entry -> new ForecastHorizon(
                entry.getKey(), entry.getValue().stream().map(ForecastPoint::predictedQty)
                        .reduce(BigDecimal.ZERO, BigDecimal::add), List.copyOf(entry.getValue()))).toList();
        return new ForecastResponse(metadata.runId(), cutoffDate, metadata.generatedAt(), metadata.modelVersion(),
                metadata.branch(), metadata.productId(), metadata.sku(), metadata.productName(), metadata.unit(), horizons);
    }
}
