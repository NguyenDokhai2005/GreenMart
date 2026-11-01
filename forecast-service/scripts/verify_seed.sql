-- ==============================================================================
-- verify_seed.sql
-- Script kiểm đếm và xác minh dữ liệu seed AC-01 độc lập (Issue #108)
--
-- Đặc điểm:
-- 1. Tự assert bằng khối PL/pgSQL DO $$ ... RAISE EXCEPTION ... $$;
-- 2. Kiểm tra CỨNG (Hard checks): Ném exception làm psql thất bại nếu sai lệch.
-- 3. Kiểm tra MỀM (Soft checks / Informational): In ra NOTICE thống kê, không làm thất bại.
-- ==============================================================================

\set ON_ERROR_STOP on

DO $$
DECLARE
    -- 1. Biến kiểm tra cứng
    v_branch_cnt        INT;
    v_product_cnt       INT;
    v_processed_orders  INT;
    v_completed_orders  INT;
    v_returned_orders   INT;
    v_pos_orders        INT;
    v_web_orders        INT;
    v_market_orders     INT;
    v_b1_orders         INT;
    v_b2_orders         INT;
    v_b3_orders         INT;
    v_cancelled_orders  INT;
    v_expired_orders    INT;
    v_rejected_orders   INT;
    v_inv_neg_cnt       INT;
    v_milk_on_hand      INT;
    v_milk_reserved     INT;
    v_milk_rop          INT;

    -- 2. Biến thông tin mềm
    v_cust_cnt          INT;
    v_user_cnt          INT;
    v_promo_cnt         INT;
    v_return_doc_cnt    INT;
    v_return_item_cnt   INT;
    v_stock_doc_cnt     INT;
    v_stock_item_cnt    INT;
    v_inv_tx_cnt        INT;
    v_inv_out_cnt       INT;
    v_inv_low_cnt       INT;
    v_inv_ok_cnt        INT;
    v_audit_log_cnt     INT;
BEGIN
    RAISE NOTICE '======================================================================';
    RAISE NOTICE 'BẮT ĐẦU KIỂM ĐẾM VÀ XÁC MINH DỮ LIỆU SEED AC-01 (Issue #108)';
    RAISE NOTICE '======================================================================';

    -- --------------------------------------------------------------------------
    -- A. CÁC KIỂM TRA CỨNG (BẮT BUỘC ĐẠT 100%, NÉM NGOẠI LỆ NẾU LỆCH)
    -- --------------------------------------------------------------------------

    -- 1. Đủ 3 chi nhánh hoạt động (TC-AC01-01)
    SELECT count(*) INTO v_branch_cnt FROM branch WHERE is_active = true;
    IF v_branch_cnt <> 3 THEN
        RAISE EXCEPTION 'THẤT BẠI [TC-AC01-01]: Số chi nhánh hoạt động = %, kỳ vọng = 3', v_branch_cnt;
    END IF;
    RAISE NOTICE '[PASS] TC-AC01-01: Đủ 3 chi nhánh hoạt động (GM-001, GM-002, GM-003).';

    -- 2. Đủ 1.000 sản phẩm (TC-AC01-02)
    SELECT count(*) INTO v_product_cnt FROM product;
    IF v_product_cnt <> 1000 THEN
        RAISE EXCEPTION 'THẤT BẠI [TC-AC01-02]: Số sản phẩm = %, kỳ vọng = 1000', v_product_cnt;
    END IF;
    RAISE NOTICE '[PASS] TC-AC01-02: Đủ 1.000 sản phẩm trong danh mục.';

    -- 3. Đủ 10.000 đơn đã xử lý (TC-AC01-03: COMPLETED + RETURNED)
    SELECT count(*) INTO v_processed_orders FROM sales_order WHERE status IN ('COMPLETED', 'RETURNED');
    SELECT count(*) INTO v_completed_orders FROM sales_order WHERE status = 'COMPLETED';
    SELECT count(*) INTO v_returned_orders FROM sales_order WHERE status = 'RETURNED';
    IF v_processed_orders <> 10000 THEN
        RAISE EXCEPTION 'THẤT BẠI [TC-AC01-03]: Số đơn đã xử lý = %, kỳ vọng = 10000 (gồm COMPLETED + RETURNED)', v_processed_orders;
    END IF;
    RAISE NOTICE '[PASS] TC-AC01-03: Đủ 10.000 đơn đã xử lý (% COMPLETED + % RETURNED).', v_completed_orders, v_returned_orders;

    -- 4. Cơ cấu kênh cho 10.000 đơn đã xử lý (POS: 7.000, WEB: 2.000, MARKET: 1.000)
    SELECT count(*) INTO v_pos_orders
    FROM sales_order o JOIN sales_channel c ON o.channel_id = c.id
    WHERE c.code = 'POS' AND o.status IN ('COMPLETED', 'RETURNED');

    SELECT count(*) INTO v_web_orders
    FROM sales_order o JOIN sales_channel c ON o.channel_id = c.id
    WHERE c.code = 'WEB' AND o.status IN ('COMPLETED', 'RETURNED');

    SELECT count(*) INTO v_market_orders
    FROM sales_order o JOIN sales_channel c ON o.channel_id = c.id
    WHERE c.code = 'MARKET' AND o.status IN ('COMPLETED', 'RETURNED');

    IF v_pos_orders <> 7000 OR v_web_orders <> 2000 OR v_market_orders <> 1000 THEN
        RAISE EXCEPTION 'THẤT BẠI: Cơ cấu kênh POS/WEB/MARKET = (% / % / %), kỳ vọng = (7000 / 2000 / 1000)',
            v_pos_orders, v_web_orders, v_market_orders;
    END IF;
    RAISE NOTICE '[PASS] Cơ cấu kênh: POS = %, WEB = %, MARKET = % (chuẩn 70%% / 20%% / 10%%).',
        v_pos_orders, v_web_orders, v_market_orders;

    -- 5. Cơ cấu chi nhánh cho 10.000 đơn đã xử lý (GM-001: 4.500, GM-002: 3.500, GM-003: 2.000)
    SELECT count(*) INTO v_b1_orders
    FROM sales_order o JOIN branch b ON o.branch_id = b.id
    WHERE b.code = 'GM-001' AND o.status IN ('COMPLETED', 'RETURNED');

    SELECT count(*) INTO v_b2_orders
    FROM sales_order o JOIN branch b ON o.branch_id = b.id
    WHERE b.code = 'GM-002' AND o.status IN ('COMPLETED', 'RETURNED');

    SELECT count(*) INTO v_b3_orders
    FROM sales_order o JOIN branch b ON o.branch_id = b.id
    WHERE b.code = 'GM-003' AND o.status IN ('COMPLETED', 'RETURNED');

    IF v_b1_orders <> 4500 OR v_b2_orders <> 3500 OR v_b3_orders <> 2000 THEN
        RAISE EXCEPTION 'THẤT BẠI: Cơ cấu chi nhánh GM-001/002/003 = (% / % / %), kỳ vọng = (4500 / 3500 / 2000)',
            v_b1_orders, v_b2_orders, v_b3_orders;
    END IF;
    RAISE NOTICE '[PASS] Cơ cấu chi nhánh: GM-001 = %, GM-002 = %, GM-003 = % (chuẩn 45%% / 35%% / 20%%).',
        v_b1_orders, v_b2_orders, v_b3_orders;

    -- 6. 200 đơn online lỗi phân bổ chuẩn (80 CANCELLED, 80 EXPIRED, 40 REJECTED)
    SELECT count(*) INTO v_cancelled_orders FROM sales_order WHERE status = 'CANCELLED';
    SELECT count(*) INTO v_expired_orders FROM sales_order WHERE status = 'EXPIRED';
    SELECT count(*) INTO v_rejected_orders FROM sales_order WHERE status = 'REJECTED';

    IF v_cancelled_orders <> 80 OR v_expired_orders <> 80 OR v_rejected_orders <> 40 THEN
        RAISE EXCEPTION 'THẤT BẠI: 200 đơn online lỗi CANCELLED/EXPIRED/REJECTED = (% / % / %), kỳ vọng = (80 / 80 / 40)',
            v_cancelled_orders, v_expired_orders, v_rejected_orders;
    END IF;
    RAISE NOTICE '[PASS] Đơn online lỗi: % CANCELLED, % EXPIRED, % REJECTED (tổng 200 đơn).',
        v_cancelled_orders, v_expired_orders, v_rejected_orders;

    -- 7. Tồn kho không âm và reserved <= on_hand
    SELECT count(*) INTO v_inv_neg_cnt
    FROM inventory
    WHERE on_hand < 0 OR reserved < 0 OR (on_hand - reserved) < 0 OR reserved > on_hand;

    IF v_inv_neg_cnt > 0 THEN
        RAISE EXCEPTION 'THẤT BẠI: Phát hiện % dòng tồn kho bị âm hoặc vi phạm reserved <= on_hand', v_inv_neg_cnt;
    END IF;
    RAISE NOTICE '[PASS] Toàn vẹn tồn kho: Toàn bộ 3.000 dòng có on_hand >= 0, 0 <= reserved <= on_hand, khả dụng >= 0.';

    -- 8. Mốc neo Sữa tươi TH True Milk 1L tại GM-001 (TC-DASH-01)
    SELECT i.on_hand, i.reserved, i.reorder_point
    INTO v_milk_on_hand, v_milk_reserved, v_milk_rop
    FROM inventory i
    JOIN product p ON i.product_id = p.id
    JOIN branch b ON i.branch_id = b.id
    WHERE p.sku = 'SUA-TH-1L' AND b.code = 'GM-001';

    IF v_milk_on_hand <> 35 OR v_milk_reserved <> 0 OR v_milk_rop <> 34 THEN
        RAISE EXCEPTION 'THẤT BẠI [TC-DASH-01]: Mốc neo SUA-TH-1L tại GM-001 on_hand=%, reserved=%, rop=%, kỳ vọng 35/0/34',
            v_milk_on_hand, v_milk_reserved, v_milk_rop;
    END IF;
    RAISE NOTICE '[PASS] Mốc neo demo TC-DASH-01: SUA-TH-1L tại GM-001 on_hand = 35, reserved = 0, ROP = 34.';

    -- --------------------------------------------------------------------------
    -- B. CÁC THÔNG TIN MỀM (THỐNG KÊ BỔ SUNG, KHÔNG NÉM NGOẠI LỆ)
    -- --------------------------------------------------------------------------
    SELECT count(*) INTO v_cust_cnt FROM customer;
    SELECT count(*) INTO v_user_cnt FROM app_user;
    SELECT count(*) INTO v_promo_cnt FROM promotion;
    SELECT count(*) INTO v_return_doc_cnt FROM return_document;
    SELECT count(*) INTO v_return_item_cnt FROM return_item;
    SELECT count(*) INTO v_stock_doc_cnt FROM stock_document;
    SELECT count(*) INTO v_stock_item_cnt FROM stock_document_item;
    SELECT count(*) INTO v_inv_tx_cnt FROM inventory_transaction;
    SELECT count(*) INTO v_audit_log_cnt FROM audit_log;

    SELECT count(*) INTO v_inv_out_cnt FROM inventory WHERE on_hand - reserved = 0;
    SELECT count(*) INTO v_inv_low_cnt FROM inventory WHERE on_hand - reserved > 0 AND on_hand - reserved <= reorder_point;
    SELECT count(*) INTO v_inv_ok_cnt FROM inventory WHERE on_hand - reserved > reorder_point;

    RAISE NOTICE '----------------------------------------------------------------------';
    RAISE NOTICE 'THỐNG KÊ BỔ TRỢ DỮ LIỆU ĐÃ NẠP:';
    RAISE NOTICE '- Khách hàng: % bản ghi', v_cust_cnt;
    RAISE NOTICE '- Tài khoản demo: % bản ghi', v_user_cnt;
    RAISE NOTICE '- Chiến dịch khuyến mại: % bản ghi', v_promo_cnt;
    RAISE NOTICE '- Phiếu đổi trả (return_document): % phiếu (% dòng hàng trả)', v_return_doc_cnt, v_return_item_cnt;
    RAISE NOTICE '- Phiếu nhập kho đầu kỳ (stock_document): % phiếu (% dòng hàng)', v_stock_doc_cnt, v_stock_item_cnt;
    RAISE NOTICE '- Sổ giao dịch kho (inventory_transaction): % giao dịch IMPORT', v_inv_tx_cnt;
    RAISE NOTICE '- Phân bổ trạng thái tồn kho: % dòng OUT, % dòng LOW, % dòng OK', v_inv_out_cnt, v_inv_low_cnt, v_inv_ok_cnt;
    RAISE NOTICE '- Nhật ký kiểm toán đơn lỗi (audit_log): % bản ghi', v_audit_log_cnt;
    RAISE NOTICE '======================================================================';
    RAISE NOTICE 'KẾT LUẬN: TOÀN BỘ RÀNG BUỘC KIỂM ĐẾM AC-01 ĐẠT 100%%';
    RAISE NOTICE '======================================================================';
END $$;
