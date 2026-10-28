-- =============================================================
-- V3__reference_data.sql
-- Du lieu tham chieu: 3 kenh ban POS, WEB, MARKET
-- Phu trach: SV03 - Dat9161
-- =============================================================

-- Tao san 3 kenh ban de seed cua SV06 va code cua SV07 chi can doc
-- Luu y: default_branch_id va channel_branch cho WEB, MARKET
-- se duoc SV06 gan trong seed (#106) vi chua co chi nhanh luc nay.
-- @hongthienhao luu y khi lam issue #106.
INSERT INTO sales_channel (code, name, safety_stock)
VALUES
    ('POS',    'Bán tại quầy', 2),
    ('WEB',    'Website',      2),
    ('MARKET', 'Sàn TMĐT',     2);
