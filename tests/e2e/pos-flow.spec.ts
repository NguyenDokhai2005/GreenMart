/**
 * Playwright E2E – Luồng bán tại quầy (UC-01, US-01)
 * 
 * Chạy: npx playwright test pos-flow.spec.ts
 * 
 * NOTE (Tuần 1): Skeleton – các step có TODO sẽ hoàn thiện
 * sau khi frontend sẵn sàng (tuần 2).
 */

import { test, expect, Page } from '@playwright/test';

const BASE_URL = process.env.BASE_URL || 'http://localhost:5173';

// ─── Helpers ────────────────────────────────────────────────────────────────

async function login(page: Page, username: string, password: string) {
  await page.goto(`${BASE_URL}/login`);
  await page.fill('[data-testid="username"]', username);
  await page.fill('[data-testid="password"]', password);
  await page.click('[data-testid="login-btn"]');
  await expect(page).not.toHaveURL(/\/login/);
}

// ─── Tests ──────────────────────────────────────────────────────────────────

test.describe('UC-01 · Bán tại quầy (POS)', () => {

  test.beforeEach(async ({ page }) => {
    await login(page, 'cashier_cn1', 'Cashier@123');
  });

  test('TC-POS-01 · Bán 1 sản phẩm – thanh toán tiền mặt thành công', async ({ page }) => {
    // TODO: hoàn thiện khi frontend có data-testid
    await page.goto(`${BASE_URL}/pos`);

    // Quét / tìm sản phẩm
    await page.fill('[data-testid="product-search"]', 'SKU-001');
    await page.keyboard.press('Enter');

    // Kiểm tra dòng đã thêm vào giỏ
    await expect(page.locator('[data-testid="cart-item"]')).toHaveCount(1);

    // Chọn thanh toán tiền mặt
    await page.click('[data-testid="pay-cash-btn"]');
    await page.fill('[data-testid="cash-tendered"]', '999999');
    await page.click('[data-testid="complete-order-btn"]');

    // Kiểm tra hiển thị hóa đơn
    await expect(page.locator('[data-testid="invoice-code"]')).toBeVisible();
    await expect(page.locator('[data-testid="invoice-status"]')).toHaveText('COMPLETED');
  });

  test('TC-POS-07 · Tiền đưa < tổng → không cho hoàn tất', async ({ page }) => {
    await page.goto(`${BASE_URL}/pos`);

    await page.fill('[data-testid="product-search"]', 'SKU-001');
    await page.keyboard.press('Enter');

    await page.click('[data-testid="pay-cash-btn"]');
    // Nhập tiền = 1 đồng
    await page.fill('[data-testid="cash-tendered"]', '1');

    // Nút hoàn tất phải bị disable hoặc báo lỗi
    const completeBtn = page.locator('[data-testid="complete-order-btn"]');
    await expect(completeBtn).toBeDisabled();
  });

  test('TC-POS-08 · Sản phẩm không tồn tại → báo lỗi', async ({ page }) => {
    await page.goto(`${BASE_URL}/pos`);

    await page.fill('[data-testid="product-search"]', 'SKU-NONEXISTENT-999');
    await page.keyboard.press('Enter');

    await expect(page.locator('[data-testid="product-not-found-msg"]')).toBeVisible();
    await expect(page.locator('[data-testid="cart-item"]')).toHaveCount(0);
  });

});

test.describe('UC-02 · Đơn online – xem danh sách', () => {

  test.beforeEach(async ({ page }) => {
    await login(page, 'manager_cn1', 'Manager@123');
  });

  test('Danh sách đơn online hiển thị đúng', async ({ page }) => {
    await page.goto(`${BASE_URL}/orders`);

    // TODO: kiểm tra danh sách render khi có dữ liệu
    await expect(page.locator('[data-testid="orders-table"]')).toBeVisible();
  });

});
