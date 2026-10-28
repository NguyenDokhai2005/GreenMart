-- =============================================================
-- V3__reference_data.sql
-- Du lieu tham chieu: 3 kenh ban POS, WEB, MARKET
-- Phu trach: SV03 - Dat9161
-- =============================================================

-- Tao san 3 kenh ban de seed cua SV06 va code cua SV07 chi can doc
INSERT INTO sales_channel (code, name, safety_stock)
VALUES
    ('POS',    'Ban tai quay',           2),
    ('WEB',    'Kenh web truc tuyen',    2),
    ('MARKET', 'San thuong mai dien tu', 2);
