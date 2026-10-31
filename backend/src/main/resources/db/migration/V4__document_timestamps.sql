-- Document lifecycle timestamps (BR-10, BR-18), issue #215.
-- Historical timestamps below are fallback values, not recovered event times.
ALTER TABLE return_document
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN approved_at TIMESTAMPTZ;
ALTER TABLE stock_document
    ADD COLUMN approved_at TIMESTAMPTZ,
    ADD COLUMN received_at TIMESTAMPTZ;

UPDATE return_document SET approved_at = created_at
WHERE status = 'APPROVED' AND approved_at IS NULL;
UPDATE stock_document SET approved_at = created_at
WHERE status IN ('APPROVED', 'IN_TRANSIT', 'RECEIVED') AND approved_at IS NULL;
UPDATE stock_document SET received_at = approved_at
WHERE status = 'RECEIVED' AND received_at IS NULL;

ALTER TABLE return_document
    ADD CONSTRAINT chk_return_approved_at
        CHECK ((status = 'APPROVED') = (approved_at IS NOT NULL)),
    ADD CONSTRAINT chk_return_time_order
        CHECK (approved_at IS NULL OR approved_at >= created_at);
ALTER TABLE stock_document
    ADD CONSTRAINT chk_stock_approved_at
        CHECK ((status <> 'DRAFT' OR approved_at IS NULL)
           AND (status NOT IN ('APPROVED', 'IN_TRANSIT', 'RECEIVED') OR approved_at IS NOT NULL)),
    ADD CONSTRAINT chk_stock_received_at
        CHECK ((status = 'RECEIVED') = (received_at IS NOT NULL)),
    ADD CONSTRAINT chk_stock_time_order
        CHECK ((approved_at IS NULL OR approved_at >= created_at)
           AND (received_at IS NULL OR received_at >= approved_at));
