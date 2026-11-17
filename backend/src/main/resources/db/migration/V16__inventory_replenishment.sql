-- reorder_point remains the manually configured fallback.
ALTER TABLE inventory
    ADD COLUMN forecast_reorder_point INT CHECK (forecast_reorder_point >= 0),
    ADD COLUMN reorder_safety_stock INT CHECK (reorder_safety_stock >= 0),
    ADD COLUMN reorder_demand NUMERIC CHECK (reorder_demand >= 0),
    ADD COLUMN reorder_plan_date DATE,
    ADD COLUMN reorder_pack_size INT NOT NULL DEFAULT 1 CHECK (reorder_pack_size > 0),
    ADD CONSTRAINT ck_inventory_forecast_plan_complete CHECK (
        num_nonnulls(forecast_reorder_point, reorder_safety_stock, reorder_demand, reorder_plan_date) IN (0, 4)
    );

-- All readers fall back at midnight even if the scheduled refresh was missed.
CREATE VIEW inventory_replenishment AS
SELECT i.*,
       CASE WHEN dated.is_current THEN i.forecast_reorder_point ELSE i.reorder_point END AS effective_reorder_point,
       CASE WHEN dated.is_current THEN i.reorder_safety_stock END AS effective_safety_stock,
       CASE WHEN dated.is_current THEN i.reorder_demand END AS effective_reorder_demand
FROM inventory i
CROSS JOIN LATERAL (
    SELECT i.reorder_plan_date = (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS is_current
) dated;
