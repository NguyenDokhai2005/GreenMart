package vn.greenmart.forecast.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Typed DTO matching ForecastMetricsBreakdown in docs/api/openapi.yaml (#423).
 */
public record ForecastMetricsBreakdown(
        String schemaVersion,
        String metricProtocolVersion,
        String modelVersion,
        String postprocessVersion,
        LocalDate cutoffDate,
        LocalDate evaluationCutoffDate,
        LocalDate testStartDate,
        LocalDate testEndDate,
        String dataSource,
        String dataSourceHash,
        int shortHistorySeriesCount,
        HorizonBreakdown h7,
        HorizonBreakdown h30,
        List<String> benchmarkLimitations) {

    public record MetricValues(Double mae, Double wape, Double zeroPredRatio) {}

    public record BaselinesComparison(
            MetricValues weeklyNaive,
            MetricValues rollingMean28,
            MetricValues zeroForecast) {}

    public record SegmentMetrics(
            String segment,
            int sampleCount,
            int excludedStockoutCount,
            int totalDailyCellsBeforeFilter,
            double coverageRatio,
            double totalActual,
            String bestBaselineName,
            MetricValues model,
            MetricValues bestBaseline,
            BaselinesComparison baselines) {}

    public record ViewSegments(
            SegmentMetrics overall,
            SegmentMetrics top200,
            SegmentMetrics slowMoving,
            SegmentMetrics zeroDemand,
            SegmentMetrics nonZeroDemand) {}

    public record EvaluationView(
            int horizon,
            String aggregation,
            String stockoutMode,
            boolean postprocessed,
            String postprocessVersion,
            double slowThreshold,
            List<LocalDate> evaluationOrigins,
            LocalDate targetStartDate,
            LocalDate targetEndDate,
            ViewSegments segments) {}

    public record HorizonBreakdown(
            int horizon,
            EvaluationView servingAggregatePostproc,
            EvaluationView servingAggregateRawModel,
            EvaluationView weeklyAggregatePostproc,
            EvaluationView rawDailyClean,
            EvaluationView rawDailyUnfiltered,
            EvaluationView rollingH7AggregatePostproc) {}
}
