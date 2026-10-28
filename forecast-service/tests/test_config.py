"""Unit tests kiểm tra tính hợp lệ của module config (Issue #105).

Bao gồm các kiểm tra cho toàn bộ tham số thiết kế đã được đối chiếu và xác nhận
bởi Lead/SV01.
"""

from datetime import date, datetime
import math
from pathlib import Path
import sys
import unittest

# Đảm bảo có thể import module config từ thư mục scripts mà không cần cài đặt package
SCRIPTS_DIR = Path(__file__).resolve().parent.parent / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import config  # noqa: E402


class TestConfig(unittest.TestCase):
    """Kiểm tra các giá trị cấu hình tham số sinh dữ liệu theo đặc tả SV01/Lead."""

    def test_date_range_and_event_boundary(self):
        """Kiểm tra khoảng thời gian lịch sử và mốc chặn sự kiện tối đa."""
        self.assertIsInstance(config.START_DATE, datetime)
        self.assertIsInstance(config.END_DATE, datetime)
        self.assertEqual(config.START_DATE, datetime(2025, 11, 1, 0, 0, 0))
        self.assertEqual(config.END_DATE, datetime(2026, 10, 31, 23, 59, 59))
        self.assertLess(config.START_DATE, config.END_DATE)

        # Không có sự kiện nào vượt quá 31/10/2026 23:59:59
        self.assertEqual(config.MAX_EVENT_DATETIME, datetime(2026, 10, 31, 23, 59, 59))
        self.assertEqual(config.MAX_EVENT_DATETIME, config.END_DATE)

    def test_tet_date(self):
        """Kiểm tra ngày Tết Bính Ngọ và vị trí trong khoảng thời gian lịch sử."""
        self.assertEqual(config.TET_DATE, date(2026, 2, 17))
        self.assertGreaterEqual(config.TET_DATE, config.START_DATE.date())
        self.assertLessEqual(config.TET_DATE, config.END_DATE.date())

    def test_total_completed_orders(self):
        """Kiểm tra số lượng đơn COMPLETED là đúng 10.000."""
        self.assertIsInstance(config.TOTAL_COMPLETED_ORDERS, int)
        self.assertEqual(config.TOTAL_COMPLETED_ORDERS, 10_000)

    def test_channel_ratios_and_online_completed_count(self):
        """Kiểm tra tỷ lệ kênh bán và số lượng đơn online COMPLETED mục tiêu (3.000 đơn)."""
        ratios = config.CHANNEL_RATIOS
        self.assertSetEqual(set(ratios.keys()), {"POS", "WEB", "MARKET"})
        self.assertEqual(ratios["POS"], 0.70)
        self.assertEqual(ratios["WEB"], 0.20)
        self.assertEqual(ratios["MARKET"], 0.10)
        self.assertTrue(math.isclose(sum(ratios.values()), 1.0, rel_tol=1e-9))

        # 10.000 * (0.20 WEB + 0.10 MARKET) = 3.000 đơn online COMPLETED
        self.assertEqual(config.COMPLETED_ONLINE_ORDERS, 3_000)

    def test_branch_ratios(self):
        """Kiểm tra tỷ lệ chi nhánh: GM-001 (45%), GM-002 (35%), GM-003 (20%) và tổng bằng 1.0."""
        ratios = config.BRANCH_RATIOS
        self.assertSetEqual(set(ratios.keys()), {"GM-001", "GM-002", "GM-003"})
        self.assertEqual(ratios["GM-001"], 0.45)
        self.assertEqual(ratios["GM-002"], 0.35)
        self.assertEqual(ratios["GM-003"], 0.20)
        self.assertTrue(math.isclose(sum(ratios.values()), 1.0, rel_tol=1e-9))

    def test_failed_online_orders_and_rates(self):
        """Kiểm tra các tham số đơn online thất bại: 200 đơn (80 CANCELLED, 80 EXPIRED, 40 REJECTED)."""
        self.assertEqual(config.FAILED_ONLINE_BASE, "COMPLETED_ONLINE")
        self.assertEqual(config.FAILED_ONLINE_TARGET_TOTAL, 200)

        by_status = config.FAILED_ONLINE_BY_STATUS
        self.assertSetEqual(set(by_status.keys()), {"CANCELLED", "EXPIRED", "REJECTED"})
        self.assertEqual(by_status["CANCELLED"], 80)
        self.assertEqual(by_status["EXPIRED"], 80)
        self.assertEqual(by_status["REJECTED"], 40)
        self.assertEqual(sum(by_status.values()), config.FAILED_ONLINE_TARGET_TOTAL)

        # Tỷ lệ thất bại = 200 / 3000 ≈ 6.67%
        expected_rate = 200 / 3000
        self.assertTrue(math.isclose(config.FAILED_ONLINE_RATE, expected_rate, rel_tol=1e-9))
        self.assertAlmostEqual(config.FAILED_ONLINE_RATE * 100, 6.67, places=2)

        # Thời hạn giữ chỗ / thanh toán đơn online: 15 phút
        self.assertEqual(config.ONLINE_PAYMENT_TIMEOUT_MINUTES, 15)

    def test_sku_and_pareto_rules(self):
        """Kiểm tra tổng SKU (1.000) và quy luật Pareto 80/20 cho 200 SKU theo QUANTITY."""
        self.assertEqual(config.TOTAL_SKU_COUNT, 1_000)
        self.assertEqual(config.PARETO_TARGET_SKU_COUNT, 200)
        self.assertLessEqual(config.PARETO_TARGET_SKU_COUNT, config.TOTAL_SKU_COUNT)
        self.assertEqual(config.PARETO_TARGET_METRIC, "QUANTITY")
        self.assertEqual(config.PARETO_TARGET_QUANTITY_SHARE, 0.80)

    def test_promotion_rules(self):
        """Kiểm tra cấu hình chiến dịch khuyến mãi (1–2 đợt/tháng, có Tết, tăng 30%–80%)."""
        p_min, p_max = config.PROMOTION_CAMPAIGNS_PER_MONTH_RANGE
        self.assertEqual(p_min, 1)
        self.assertEqual(p_max, 2)
        self.assertGreaterEqual(p_min, 0)
        self.assertLessEqual(p_min, p_max)

        self.assertTrue(config.HAS_TET_PROMOTION)

        lift_min, lift_max = config.PROMOTION_DEMAND_LIFT_RANGE
        self.assertEqual(lift_min, 0.30)
        self.assertEqual(lift_max, 0.80)
        self.assertGreaterEqual(lift_min, 0.0)
        self.assertLessEqual(lift_min, lift_max)

    def test_stockout_and_return_rules(self):
        """Kiểm tra xác minh đầy đủ cận dưới/cận trên stockout (1–3 ngày, 2%–3%), trả hàng (1%–2%, 7 ngày)."""
        self.assertEqual(config.STOCKOUT_SIMULATION_MODE, "MIXED")

        # Xác minh đầy đủ cận dưới và cận trên của khoảng thời gian stockout (1–3 ngày)
        dur_min, dur_max = config.STOCKOUT_DURATION_DAYS_RANGE
        self.assertEqual(dur_min, 1)
        self.assertEqual(dur_max, 3)
        self.assertLessEqual(dur_min, dur_max)

        # Tỷ lệ ngày xảy ra stockout ở nhóm SKU bán nhanh (2%–3%)
        so_min, so_max = config.STOCKOUT_DAYS_RATIO_RANGE
        self.assertEqual(so_min, 0.02)
        self.assertEqual(so_max, 0.03)
        self.assertGreaterEqual(so_min, 0.0)
        self.assertLessEqual(so_min, so_max)

        # Tỷ lệ trả hàng (1%–2%)
        ret_min, ret_max = config.RETURN_RATE_RANGE
        self.assertEqual(ret_min, 0.01)
        self.assertEqual(ret_max, 0.02)
        self.assertGreaterEqual(ret_min, 0.0)
        self.assertLessEqual(ret_min, ret_max)

        # Cửa sổ trả hàng trong vòng 7 ngày kể từ khi đơn hoàn tất
        self.assertIsInstance(config.RETURN_WINDOW_DAYS, int)
        self.assertEqual(config.RETURN_WINDOW_DAYS, 7)
        self.assertGreater(config.RETURN_WINDOW_DAYS, 0)

        # Không phát sinh trả hàng sau 31/10/2026
        self.assertFalse(config.ALLOW_RETURNS_AFTER_END_DATE)

    def test_cart_ranges(self):
        """Kiểm tra quy cách giỏ hàng: 3–8 dòng/đơn, 1–5 cái/dòng."""
        items_min, items_max = config.ORDER_ITEMS_RANGE
        self.assertEqual((items_min, items_max), (3, 8))
        self.assertGreater(items_min, 0)
        self.assertLessEqual(items_min, items_max)

        qty_min, qty_max = config.ITEM_QUANTITY_RANGE
        self.assertEqual((qty_min, qty_max), (1, 5))
        self.assertGreater(qty_min, 0)
        self.assertLessEqual(qty_min, qty_max)

    def test_random_seed(self):
        """Kiểm tra random seed cố định là 2026."""
        self.assertEqual(config.RANDOM_SEED, 2026)


if __name__ == "__main__":
    unittest.main()
