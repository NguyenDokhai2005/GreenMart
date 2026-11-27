"""Pydantic schemas for forecast API (#114).

Responses use camelCase naming convention via alias_generator=to_camel.
Errors use standard structure: {"code": "...", "message": "..."}.
"""

from __future__ import annotations

from datetime import date, datetime
from typing import List, Literal, Optional

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    """Base model with camelCase serialization and alias population support."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
    )


ForecastAggregationType = Literal[
    "HORIZON_AGGREGATE_POSTPROC",
    "HORIZON_AGGREGATE_RAW_MODEL",
    "ROLLING_H7_AGGREGATE_POSTPROC",
    "WEEKLY_AGGREGATE_POSTPROC",
    "DAILY_CELL_RAW",
    "DAILY_CELL_RAW_UNFILTERED",
    "DAILY_CELL_POSTPROC",
]
ForecastStockoutModeType = Literal["EXCLUDED_CLEAN", "INCLUDED_RAW"]
ForecastBaselineNameType = Literal["weekly_naive", "rolling_mean_28"]


class ForecastMetricValues(CamelModel):
    """Metric values for a single model/baseline evaluation slice."""

    mae: Optional[float] = None
    wape: Optional[float] = None
    zero_pred_ratio: Optional[float] = None


class ForecastBaselinesComparison(CamelModel):
    """All baseline benchmarks evaluated under the exact same slice and rules."""

    weekly_naive: ForecastMetricValues
    rolling_mean_28: ForecastMetricValues
    zero_forecast: ForecastMetricValues


class ForecastSegmentMetrics(CamelModel):
    """Detailed metrics and coverage for a single segment."""

    segment: Literal["overall", "top_200", "slow_moving", "zero_demand", "non_zero_demand"]
    sample_count: int
    excluded_stockout_count: int
    total_daily_cells_before_filter: int
    coverage_ratio: float
    total_actual: float
    best_baseline_name: ForecastBaselineNameType
    model: ForecastMetricValues
    best_baseline: ForecastMetricValues
    baselines: ForecastBaselinesComparison


class ForecastViewSegments(CamelModel):
    """All required segment breakdowns for an evaluation view."""

    overall: ForecastSegmentMetrics
    top_200: ForecastSegmentMetrics
    slow_moving: ForecastSegmentMetrics
    zero_demand: ForecastSegmentMetrics
    non_zero_demand: ForecastSegmentMetrics


class ForecastEvaluationView(CamelModel):
    """One aggregation/postprocess evaluation view for a horizon."""

    horizon: Literal[7, 30]
    aggregation: ForecastAggregationType
    stockout_mode: ForecastStockoutModeType
    postprocessed: bool
    postprocess_version: Optional[str] = None
    slow_threshold: float
    evaluation_origins: List[date]
    target_start_date: date
    target_end_date: date
    segments: ForecastViewSegments


class ForecastHorizonBreakdown(CamelModel):
    """Full comparison views for a single forecast horizon."""

    horizon: Literal[7, 30]
    serving_aggregate_postproc: ForecastEvaluationView
    serving_aggregate_raw_model: ForecastEvaluationView
    weekly_aggregate_postproc: ForecastEvaluationView
    raw_daily_clean: ForecastEvaluationView
    raw_daily_unfiltered: ForecastEvaluationView
    rolling_h7_aggregate_postproc: Optional[ForecastEvaluationView] = None


class ForecastMetricsBreakdown(CamelModel):
    """Typed breakdown of H7/H30 metrics, provenance, and limitations."""

    schema_version: str
    metric_protocol_version: str
    model_version: str
    postprocess_version: str
    cutoff_date: date
    evaluation_cutoff_date: date
    test_start_date: date
    test_end_date: date
    data_source: str
    data_source_hash: str
    short_history_series_count: int
    h7: Optional[ForecastHorizonBreakdown] = None
    h30: Optional[ForecastHorizonBreakdown] = None
    benchmark_limitations: List[str]


class ForecastRunCreatedResponse(CamelModel):
    """Response returned immediately by POST /forecast/run (HTTP 202 Accepted)."""

    run_id: int
    status: Literal["RUNNING", "DONE", "FAILED"]
    started_at: datetime


class ForecastRunDetailResponse(CamelModel):
    """Detailed response returned by GET /forecast/runs/{runId} (HTTP 200 OK)."""

    run_id: int
    status: Literal["RUNNING", "DONE", "FAILED"]
    started_at: datetime
    finished_at: Optional[datetime] = None
    model_version: str
    mae: Optional[float] = None
    wape: Optional[float] = None
    baseline_mae: Optional[float] = None
    baseline_wape: Optional[float] = None
    legacy: bool = False
    metric_horizon: Optional[Literal[7, 30]] = None
    aggregation: Optional[ForecastAggregationType] = None
    postprocess_version: Optional[str] = None
    metric_protocol_version: Optional[str] = None
    cutoff_date: Optional[date] = None
    evaluation_cutoff_date: Optional[date] = None
    test_start_date: Optional[date] = None
    test_end_date: Optional[date] = None
    stockout_mode: Optional[ForecastStockoutModeType] = None
    baseline_name: Optional[ForecastBaselineNameType] = None
    data_source: Optional[str] = None
    data_source_hash: Optional[str] = None
    metrics_breakdown: Optional[ForecastMetricsBreakdown] = None


class ErrorResponse(BaseModel):
    """Standard error response structure."""

    code: str
    message: str
