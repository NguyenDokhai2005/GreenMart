-- #425. V20 is reserved for #430/PR #503; integrate/deploy V20 before V21.
-- NULL means the legacy/default threshold of 1 for ITEM; ORDER has no threshold.
-- No rewrite of historical promotion counters, order snapshots, or seed prices.
ALTER TABLE promotion ADD COLUMN min_quantity integer;
ALTER TABLE promotion ADD CONSTRAINT ck_promotion_min_quantity
    CHECK (min_quantity IS NULL OR (level='ITEM' AND min_quantity>=1));
