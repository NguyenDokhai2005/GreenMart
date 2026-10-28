-- =============================================================
-- V2__grant_forecast.sql
-- Cap quyen ghi cho tai khoan du bao (forecast_user)
-- Phu trach: SV03 - Dat9161
-- =============================================================

-- forecast_user can doc/ghi daily_sales, forecast_run, forecast_result
GRANT INSERT, UPDATE, DELETE
    ON daily_sales, forecast_run, forecast_result
    TO "${forecast_user}";

-- Cap quyen dung sequence de INSERT forecast_run va forecast_result co id tu tang
-- (daily_sales dung composite PK, khong co sequence rieng)
GRANT USAGE, SELECT
    ON SEQUENCE forecast_run_id_seq, forecast_result_id_seq
    TO "${forecast_user}";
