-- Run as the seed database owner after loading inventory.
-- Reconcile existing open alerts and preserve the history of closed alerts.
WITH classified AS (
    SELECT branch_id, product_id,
           CASE WHEN on_hand = reserved THEN 'OUT'
                WHEN on_hand - reserved <= reorder_point THEN 'LOW'
           END AS severity
    FROM inventory
), closed AS (
    UPDATE stock_alert a
    SET closed_at = clock_timestamp()
    FROM classified i
    WHERE a.branch_id = i.branch_id AND a.product_id = i.product_id
      AND a.closed_at IS NULL AND i.severity IS NULL
)
INSERT INTO stock_alert (branch_id, product_id, severity)
SELECT branch_id, product_id, severity
FROM classified
WHERE severity IS NOT NULL
ON CONFLICT (branch_id, product_id) WHERE closed_at IS NULL
DO UPDATE SET severity = EXCLUDED.severity
WHERE stock_alert.severity <> EXCLUDED.severity;
