-- #423: Standardize forecast_run metric metadata and H7/H30 breakdown storage.
-- Existing pre-V22 DONE rows keep their original scalar values and NULL metadata (treated as legacy = true).
-- RUNNING and FAILED rows keep all metric and metadata columns as NULL.

ALTER TABLE forecast_run
    ADD COLUMN metric_horizon          INT CHECK (metric_horizon IS NULL OR metric_horizon IN (7, 30)),
    ADD COLUMN aggregation             VARCHAR(64) CHECK (
        aggregation IS NULL OR aggregation IN (
            'HORIZON_AGGREGATE_POSTPROC',
            'HORIZON_AGGREGATE_RAW_MODEL',
            'ROLLING_H7_AGGREGATE_POSTPROC',
            'WEEKLY_AGGREGATE_POSTPROC',
            'DAILY_CELL_RAW',
            'DAILY_CELL_RAW_UNFILTERED',
            'DAILY_CELL_POSTPROC'
        )
    ),
    ADD COLUMN postprocess_version     VARCHAR(64),
    ADD COLUMN metric_protocol_version VARCHAR(64),
    ADD COLUMN cutoff_date             DATE,
    ADD COLUMN evaluation_cutoff_date  DATE,
    ADD COLUMN test_start_date         DATE,
    ADD COLUMN test_end_date           DATE,
    ADD COLUMN stockout_mode           VARCHAR(32) CHECK (
        stockout_mode IS NULL OR stockout_mode IN ('EXCLUDED_CLEAN', 'INCLUDED_RAW')
    ),
    ADD COLUMN baseline_name           VARCHAR(32) CHECK (
        baseline_name IS NULL OR baseline_name IN ('weekly_naive', 'rolling_mean_28')
    ),
    ADD COLUMN data_source             VARCHAR(64),
    ADD COLUMN data_source_hash        VARCHAR(64),
    ADD COLUMN metrics_breakdown       JSONB;
