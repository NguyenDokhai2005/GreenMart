ALTER TABLE sales_order
    ADD COLUMN idempotency_key VARCHAR(64),
    ADD COLUMN request_hash VARCHAR(64);

CREATE UNIQUE INDEX uq_sales_order_creator_idempotency
    ON sales_order (created_by, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
