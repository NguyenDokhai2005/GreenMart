-- V17__customer_is_active_and_restrict_delete.sql

-- 1. Them cot is_active (mac dinh TRUE cho toan bo khach hang cu)
ALTER TABLE customer
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

-- 2. Chan xoa cung khach hang da co don: doi FK sales_order.customer_id sang ON DELETE RESTRICT
ALTER TABLE sales_order
    DROP CONSTRAINT IF EXISTS sales_order_customer_id_fkey;

ALTER TABLE sales_order
    ADD CONSTRAINT sales_order_customer_id_fkey
        FOREIGN KEY (customer_id) REFERENCES customer(id) ON DELETE RESTRICT;
