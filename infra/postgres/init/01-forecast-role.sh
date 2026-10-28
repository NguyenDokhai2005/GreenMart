#!/bin/sh
set -eu
# psql variables quote identifiers and values safely. No passwords in SQL files.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 --set=forecast_user="$FORECAST_DB_USER" \
  --set=forecast_password="$FORECAST_DB_PASSWORD" <<'SQL'
CREATE ROLE :"forecast_user" LOGIN PASSWORD :'forecast_password';
GRANT CONNECT ON DATABASE :"DBNAME" TO :"forecast_user";
GRANT USAGE ON SCHEMA public TO :"forecast_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO :"forecast_user";
SQL
# SV03 migrations must grant INSERT/UPDATE on daily_sales, forecast_run and
# forecast_result (and required sequence usage) after creating those objects.
