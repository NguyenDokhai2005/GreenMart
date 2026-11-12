-- Lưu số tiền khách đưa cho giao dịch thanh toán (phục vụ tính tiền thối khi in lại hóa đơn)
ALTER TABLE payment ADD COLUMN cash_received BIGINT;

-- Lưu giảm giá tay và lý do giảm giá tay trên đơn hàng
ALTER TABLE sales_order ADD COLUMN manual_discount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sales_order ADD COLUMN manual_discount_reason VARCHAR(255);
