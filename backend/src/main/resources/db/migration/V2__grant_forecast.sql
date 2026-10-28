-- =============================================================
-- V2__grant_forecast.sql
-- Cap quyen ghi cho tai khoan du bao (forecast_user)
-- Phu trach: SV03 - Dat9161
-- =============================================================

-- Tao role neu chua ton tai (idempotent: Testcontainers, DB moi khong co init script)
-- Trong Compose that, infra/postgres/init/01-forecast-role.sh da tao role co LOGIN
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${forecast_user}') THEN
    CREATE ROLE "${forecast_user}" NOLOGIN;
  END IF;
END $$;

-- forecast_user can doc/ghi daily_sales, forecast_run, forecast_result
GRANT INSERT, UPDATE, DELETE
    ON daily_sales, forecast_run, forecast_result
    TO "${forecast_user}";

-- Cap quyen dung sequence de INSERT forecast_run va forecast_result co id tu tang
-- (daily_sales dung composite PK, khong co sequence rieng)
GRANT USAGE, SELECT
    ON SEQUENCE forecast_run_id_seq, forecast_result_id_seq
    TO "${forecast_user}";
