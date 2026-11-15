-- Issue #62: match the ERD access paths and protect audit history.
CREATE INDEX idx_order_branch_created_at ON sales_order(branch_id, created_at DESC);
CREATE INDEX idx_order_status_reserved_until ON sales_order(status, reserved_until);
CREATE INDEX idx_inv_tx_branch_product_created_at
    ON inventory_transaction(branch_id, product_id, created_at DESC);
CREATE INDEX idx_daily_sales_product_branch_date ON daily_sales(product_id, branch_id, sale_date);

-- forecast_result already has a UNIQUE index starting with (run_id, branch_id, product_id).
-- Keep the existing partial index on RESERVED orders for the expiry job.

CREATE FUNCTION reject_audit_log_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only'
        USING ERRCODE = '55000';
END;
$$;

-- This also rejects deleting a user when the FK would null out their audit attribution.
CREATE TRIGGER audit_log_append_only
BEFORE UPDATE OR DELETE ON audit_log
FOR EACH ROW EXECUTE FUNCTION reject_audit_log_mutation();
