-- #330 / #308: undo the broad grants issued by the original PostgreSQL init script.
-- Flyway and the init script run as the same database owner in Compose.
DO $$
DECLARE
    forecast_role text := '${forecast_user}';
    app_schema text := current_schema();
BEGIN
    IF forecast_role = current_user THEN
        RAISE EXCEPTION 'Forecast must use a different role from the migration owner';
    END IF;

    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I REVOKE SELECT ON TABLES FROM %I',
                   app_schema, forecast_role);
    EXECUTE format('REVOKE SELECT ON ALL TABLES IN SCHEMA %I FROM %I', app_schema, forecast_role);
END $$;

-- Source tables used by the daily sales pipeline and its optional ledger stockout provider.
GRANT SELECT ON branch, product, sales_order, order_item, return_document, return_item,
                promotion, promotion_branch, promotion_product, inventory_transaction
    TO "${forecast_user}";

-- V2 already grants Forecast DML on its output tables and sequence access.
GRANT SELECT ON daily_sales, forecast_run, forecast_result TO "${forecast_user}";
