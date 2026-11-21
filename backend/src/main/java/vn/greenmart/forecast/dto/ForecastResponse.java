package vn.greenmart.forecast.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record ForecastResponse(
        long runId, LocalDate cutoffDate, OffsetDateTime generatedAt, String modelVersion,
        BranchRef branch, long productId, String sku, String productName, String unit,
        List<ForecastHorizon> horizons) {
    public record BranchRef(long id, String code, String name) {}
    public record ForecastHorizon(int horizon, BigDecimal totalPredictedQty, List<ForecastPoint> points) {}
    public record ForecastPoint(LocalDate date, BigDecimal predictedQty, Integer actualQty) {}
}
