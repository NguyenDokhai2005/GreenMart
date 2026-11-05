-- Keep the existing reason column as the full note, separate from the reason code.
ALTER TABLE stock_document ADD COLUMN export_reason VARCHAR(10);
UPDATE stock_document
SET export_reason = CASE WHEN reason IN ('EXPIRED','DAMAGED','LOST','OTHER') THEN reason ELSE 'OTHER' END
WHERE type = 'EXPORT';
UPDATE stock_document
SET reason = 'Phiếu xuất hủy trước V7, chưa có ghi chú phân loại'
WHERE type = 'EXPORT' AND export_reason = 'OTHER' AND (reason IS NULL OR btrim(reason) = '');
ALTER TABLE stock_document ADD CONSTRAINT chk_stock_export_reason CHECK (
    (type = 'EXPORT' AND export_reason IS NOT NULL AND export_reason IN ('EXPIRED','DAMAGED','LOST','OTHER'))
    OR (type <> 'EXPORT' AND export_reason IS NULL)
);
ALTER TABLE stock_document ADD CONSTRAINT chk_stock_export_other_note CHECK (
    export_reason IS DISTINCT FROM 'OTHER' OR (reason IS NOT NULL AND length(btrim(reason)) > 0)
);

-- Historical export costs cannot be recovered from today's product.cost_price.
-- Legacy rows remain NULL; new approvals capture this value within their transaction.
ALTER TABLE stock_document_item ADD COLUMN export_cost_price BIGINT
    CHECK (export_cost_price >= 0);
COMMENT ON COLUMN stock_document_item.export_cost_price IS
    'Product cost at EXPORT approval; NULL for non-EXPORT, drafts, or legacy rows without a historical snapshot';
