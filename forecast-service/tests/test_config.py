"""Unit tests kiểm tra tính hợp lệ và bất biến của module config (Issue #105).

Bao gồm các kiểm tra cho:
- Múi giờ hệ thống và tính timezone-aware của các mốc thời gian.
- Tính hợp lệ và bất biến của các tham số mùa vụ, ngày lễ, thứ trong tuần.
- Tính bất biến của tỷ lệ kênh, chi nhánh, đơn hoàn tất và đơn lỗi.
- Các tham số khuyến mại chi tiết (cấp đơn/cấp dòng, trần giảm giá, thời lượng, SKU).
- Cơ cấu ngành hàng theo chi nhánh, biên độ nhiễu và các ràng buộc nghiệp vụ (BR-03, BR-07, BR-10, BR-14).
"""
from __future__ import annotations

from datetime import date, datetime
import math
from pathlib import Path
import sys
import unittest
from zoneinfo import ZoneInfo

# Đảm bảo có thể import module config từ thư mục scripts mà không cần cài đặt package
SCRIPTS_DIR = Path(__file__).resolve().parent.parent / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import config  # noqa: E402


class TestConfig(unittest.TestCase):
    """Kiểm tra tính hợp lệ và các bất biến của cấu hình tham số sinh dữ liệu."""

    def test_timezone_and_date_boundaries(self):
        """Kiểm tra timezone Asia/Ho_Chi_Minh và tính timezone-aware của các mốc thời gian."""
        self.assertIsInstance(config.TIMEZONE, ZoneInfo)
        self.assertEqual(config.TIMEZONE_NAME, "Asia/Ho_Chi_Minh")

        # Kiểm tra START_DATE, END_DATE, MAX_EVENT_DATETIME
        for dt_field, name in [
            (config.START_DATE, "START_DATE"),
            (config.END_DATE, "END_DATE"),
            (config.MAX_EVENT_DATETIME, "MAX_EVENT_DATETIME"),
        ]:
            self.assertIsInstance(dt_field, datetime, f"{name} phải là kiểu datetime")
            self.assertIsNotNone(dt_field.tzinfo, f"{name} phải là timezone-aware")
            self.assertEqual(
                str(dt_field.tzinfo),
                "Asia/Ho_Chi_Minh",
                f"{name} phải thuộc múi giờ Asia/Ho_Chi_Minh",
            )

        # Bất biến thứ tự thời gian
        self.assertLess(config.START_DATE, config.END_DATE)
        self.assertEqual(config.MAX_EVENT_DATETIME, config.END_DATE)

        # Giá trị nghiệp vụ đã chốt cho khoảng 12 tháng lịch sử
        self.assertEqual(config.START_DATE.year, 2025)
        self.assertEqual(config.START_DATE.month, 11)
        self.assertEqual(config.START_DATE.day, 1)
        self.assertEqual(config.END_DATE.year, 2026)
        self.assertEqual(config.END_DATE.month, 10)
        self.assertEqual(config.END_DATE.day, 31)

    def test_tet_and_seasonality_invariants(self):
        """Kiểm tra mốc Tết Bính Ngọ và các tham số mùa vụ trước/trong Tết."""
        # Tết Bính Ngọ rơi vào 17/02/2026
        self.assertEqual(config.TET_DATE, date(2026, 2, 17))
        self.assertGreaterEqual(config.TET_DATE, config.START_DATE.date())
        self.assertLessEqual(config.TET_DATE, config.END_DATE.date())

        # Giai đoạn trước Tết
        self.assertLess(config.TET_PRE_START, config.TET_PRE_END)
        self.assertLess(config.TET_PRE_END, config.TET_DATE)
        pre_min, pre_max = config.TET_PRE_DEMAND_LIFT_RANGE
        self.assertGreater(pre_min, 1.0, "Nhu cầu trước Tết phải tăng (> 1.0)")
        self.assertLessEqual(pre_min, pre_max)

        # Giai đoạn trong Tết (Mùng 1 - Mùng 3)
        self.assertEqual(config.TET_HOLIDAY_START, config.TET_DATE)
        self.assertLessEqual(config.TET_HOLIDAY_START, config.TET_HOLIDAY_END)
        self.assertGreater(config.TET_HOLIDAY_DEMAND_FACTOR, 0.0)
        self.assertLess(
            config.TET_HOLIDAY_DEMAND_FACTOR,
            0.5,
            "Nhu cầu mùng 1-3 Tết phải giảm sâu (< 0.5)",
        )

    def test_day_of_week_factors_invariants(self):
        """Kiểm tra hệ số ngày trong tuần: đủ 7 ngày, tổng = 7.00, trung bình = 1.000."""
        factors = config.DAY_OF_WEEK_FACTORS
        self.assertEqual(len(factors), 7, "Phải có đủ 7 ngày trong tuần")
        self.assertSetEqual(set(factors.keys()), set(range(7)))

        # Mọi hệ số phải dương
        for day, factor in factors.items():
            self.assertGreater(factor, 0.0, f"Hệ số ngày {day} phải dương")

        # Kiểm tra toán học: Tổng = 7.00 và Trung bình = 1.00 chính xác
        total_factor = sum(factors.values())
        self.assertTrue(
            math.isclose(total_factor, 7.0, rel_tol=1e-9),
            f"Tổng hệ số 7 ngày phải bằng 7.0, thực tế: {total_factor}",
        )
        avg_factor = total_factor / 7.0
        self.assertTrue(
            math.isclose(avg_factor, 1.0, rel_tol=1e-9),
            f"Trung bình cộng hệ số phải bằng 1.0, thực tế: {avg_factor}",
        )

        # Kiểm tra nghiệp vụ: cuối tuần (T7, CN) có hệ số cao hơn ngày thường (T2-T5)
        weekday_avg = sum(factors[d] for d in range(4)) / 4.0
        weekend_avg = (factors[5] + factors[6]) / 2.0
        self.assertGreater(
            weekend_avg,
            weekday_avg,
            "Nhu cầu cuối tuần phải cao hơn ngày thường",
        )

    def test_special_holidays_invariants(self):
        """Kiểm tra các ngày lễ lớn trong năm: nằm trong khoảng lịch sử, hệ số > 1.0."""
        required_holidays = {
            "CHRISTMAS_2025",
            "REUNIFICATION_LABOR_2026",
            "NATIONAL_DAY_2026",
            "VIETNAMESE_WOMENS_DAY_2026",
        }
        holidays = config.SPECIAL_HOLIDAYS
        self.assertTrue(
            required_holidays.issubset(set(holidays.keys())),
            "Phải bao gồm đầy đủ các ngày lễ theo feedback SV01",
        )

        for key, h in holidays.items():
            self.assertIn("name", h)
            self.assertIn("start", h)
            self.assertIn("end", h)
            self.assertIn("demand_factor", h)
            self.assertLessEqual(h["start"], h["end"], f"Ngày bắt đầu lễ {key} phải <= kết thúc")
            self.assertGreaterEqual(
                h["start"],
                config.START_DATE.date(),
                f"Lễ {key} phải nằm sau START_DATE",
            )
            self.assertLessEqual(
                h["end"],
                config.END_DATE.date(),
                f"Lễ {key} phải nằm trước END_DATE",
            )
            self.assertGreater(
                h["demand_factor"],
                1.0,
                f"Hệ số ngày lễ {key} phải lớn hơn 1.0",
            )

    def test_branch_category_weights_and_noise(self):
        """Kiểm tra phân bổ ngành hàng theo chi nhánh và biên độ nhiễu ngẫu nhiên."""
        weights = config.BRANCH_CATEGORY_DEMAND_WEIGHTS
        self.assertSetEqual(set(weights.keys()), {"GM-001", "GM-002", "GM-003"})

        for branch, cat_weights in weights.items():
            for cat, w in cat_weights.items():
                self.assertGreater(w, 0.0, f"Trọng số ngành hàng {cat} tại {branch} phải dương")

        # Nhiễu ngẫu nhiên đối xứng hoặc hợp lý quanh 0
        noise_min, noise_max = config.DEMAND_NOISE_RANGE
        self.assertLess(noise_min, 0.0, "Nhiễu tối thiểu phải âm")
        self.assertGreater(noise_max, 0.0, "Nhiễu tối đa phải dương")
        self.assertLessEqual(noise_min, noise_max)
        self.assertLess(abs(noise_min), 0.5, "Nhiễu không được vượt quá 50%")
        self.assertLess(abs(noise_max), 0.5, "Nhiễu không được vượt quá 50%")

    def test_channel_ratios_and_online_completed_count(self):
        """Kiểm tra tỷ lệ kênh bán và số lượng đơn online COMPLETED mục tiêu (3.000 đơn)."""
        ratios = config.CHANNEL_RATIOS
        self.assertSetEqual(set(ratios.keys()), {"POS", "WEB", "MARKET"})
        self.assertEqual(ratios["POS"], 0.70)
        self.assertEqual(ratios["WEB"], 0.20)
        self.assertEqual(ratios["MARKET"], 0.10)
        self.assertTrue(math.isclose(sum(ratios.values()), 1.0, rel_tol=1e-9))

        # 10.000 * (0.20 WEB + 0.10 MARKET) = 3.000 đơn online COMPLETED / PROCESSED
        self.assertEqual(config.TOTAL_PROCESSED_ORDERS, 10_000)
        self.assertEqual(config.TOTAL_COMPLETED_ORDERS, config.TOTAL_PROCESSED_ORDERS)
        self.assertEqual(config.PROCESSED_ONLINE_ORDERS, 3_000)
        self.assertEqual(config.COMPLETED_ONLINE_ORDERS, config.PROCESSED_ONLINE_ORDERS)
        self.assertEqual(config.MAX_DEMAND_LIFT_CAP, 3.0)

        expected_online = int(
            config.TOTAL_PROCESSED_ORDERS * (ratios["WEB"] + ratios["MARKET"])
        )
        self.assertEqual(config.COMPLETED_ONLINE_ORDERS, 3_000)
        self.assertEqual(config.COMPLETED_ONLINE_ORDERS, expected_online)

    def test_branch_ratios_and_channel_routing(self):
        """Kiểm tra tỷ lệ chi nhánh (GM-001: 45%, GM-002: 35%, GM-003: 20%) và cấu hình kênh."""
        ratios = config.BRANCH_RATIOS
        self.assertSetEqual(set(ratios.keys()), {"GM-001", "GM-002", "GM-003"})
        self.assertEqual(ratios["GM-001"], 0.45)
        self.assertEqual(ratios["GM-002"], 0.35)
        self.assertEqual(ratios["GM-003"], 0.20)
        self.assertTrue(math.isclose(sum(ratios.values()), 1.0, rel_tol=1e-9))

        # Kênh phục vụ và chi nhánh mặc định theo BR-14
        for ch, branches in config.CHANNEL_SERVICE_BRANCHES.items():
            self.assertIn(ch, ratios.keys() | {"POS", "WEB", "MARKET"})
            for b in branches:
                self.assertIn(b, ratios.keys())

        for ch, default_b in config.CHANNEL_DEFAULT_BRANCH.items():
            self.assertIn(ch, {"WEB", "MARKET"})
            self.assertIn(default_b, ratios.keys())

        # Xác nhận chính xác mapping chi nhánh mặc định cho kênh online đã được SV01 xác nhận
        self.assertEqual(
            config.CHANNEL_DEFAULT_BRANCH,
            {"WEB": "GM-001", "MARKET": "GM-002"},
        )
        self.assertEqual(config.CHANNEL_DEFAULT_BRANCH["WEB"], "GM-001")
        self.assertEqual(config.CHANNEL_DEFAULT_BRANCH["MARKET"], "GM-002")

    def test_failed_online_orders_and_rates(self):
        """Kiểm tra các tham số đơn online thất bại (200 đơn: 80 CANCELLED, 80 EXPIRED, 40 REJECTED)."""
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

        # Thời hạn giữ chỗ / thanh toán đơn online: 15 phút (SRS 3.5 & BR-03)
        self.assertEqual(config.ONLINE_PAYMENT_TIMEOUT_MINUTES, 15)

    def test_sku_and_pareto_rules(self):
        """Kiểm tra tổng SKU (1.000) và quy luật Pareto 80/20 cho 200 SKU theo QUANTITY."""
        self.assertEqual(config.TOTAL_SKU_COUNT, 1_000)
        self.assertEqual(config.PARETO_TARGET_SKU_COUNT, 200)
        self.assertLess(config.PARETO_TARGET_SKU_COUNT, config.TOTAL_SKU_COUNT)
        self.assertEqual(config.PARETO_TARGET_METRIC, "QUANTITY")
        self.assertEqual(config.PARETO_TARGET_QUANTITY_SHARE, 0.80)

    def test_promotion_rules_and_details(self):
        """Kiểm tra cấu hình chiến dịch khuyến mãi chi tiết (kiểu, cấp độ, trần giảm, SKU, thời lượng)."""
        # Tần suất tháng
        p_min, p_max = config.PROMOTION_CAMPAIGNS_PER_MONTH_RANGE
        self.assertEqual((p_min, p_max), (1, 2))
        self.assertGreaterEqual(p_min, 0)
        self.assertLessEqual(p_min, p_max)

        self.assertTrue(config.HAS_TET_PROMOTION)

        # Mức tăng nhu cầu (demand lift)
        lift_min, lift_max = config.PROMOTION_DEMAND_LIFT_RANGE
        self.assertEqual((lift_min, lift_max), (0.30, 0.80))
        self.assertGreater(lift_min, 0.0)
        self.assertLessEqual(lift_min, lift_max)

        # Kiểu và cấp độ
        self.assertIn("PERCENT", config.PROMOTION_DISCOUNT_TYPES)
        self.assertIn("AMOUNT", config.PROMOTION_DISCOUNT_TYPES)
        self.assertIn("ORDER", config.PROMOTION_LEVELS)
        self.assertIn("ITEM", config.PROMOTION_LEVELS)

        # Tỷ lệ giảm phần trăm
        pct_min, pct_max = config.PROMOTION_PERCENT_DISCOUNT_RANGE
        self.assertGreater(pct_min, 0.0)
        self.assertLess(pct_max, 1.0)
        self.assertLessEqual(pct_min, pct_max)

        # Trần giảm giá và các mệnh giá giảm tiền
        self.assertGreater(config.PROMOTION_MAX_DISCOUNT_CAP, 0)
        for amt in config.PROMOTION_AMOUNT_DISCOUNT_OPTIONS:
            self.assertGreater(amt, 0)

        # Đơn tối thiểu
        min_ord_low, min_ord_high = config.PROMOTION_MIN_ORDER_AMOUNT_RANGE
        self.assertGreater(min_ord_low, 0)
        self.assertLessEqual(min_ord_low, min_ord_high)

        # Thời lượng chiến dịch
        dur_min, dur_max = config.PROMOTION_CAMPAIGN_DURATION_DAYS_RANGE
        self.assertGreater(dur_min, 0)
        self.assertLessEqual(dur_min, dur_max)
        self.assertGreaterEqual(config.TET_PROMOTION_DURATION_DAYS, dur_max)

        # Tỷ lệ và số lượng SKU cấp sản phẩm
        sku_ratio_min, sku_ratio_max = config.PROMOTION_ITEM_SKU_RATIO_RANGE
        self.assertGreater(sku_ratio_min, 0.0)
        self.assertLess(sku_ratio_max, 1.0)
        self.assertLessEqual(sku_ratio_min, sku_ratio_max)

        sku_cnt_min, sku_cnt_max = config.PROMOTION_ITEM_SKU_COUNT_RANGE
        self.assertGreater(sku_cnt_min, 0)
        self.assertLessEqual(sku_cnt_max, config.TOTAL_SKU_COUNT)
        self.assertLessEqual(sku_cnt_min, sku_cnt_max)

        # Kiểm tra tính nhất quán giữa tỷ lệ và số lượng SKU
        expected_cnt_min = int(config.TOTAL_SKU_COUNT * sku_ratio_min)
        expected_cnt_max = int(config.TOTAL_SKU_COUNT * sku_ratio_max)
        self.assertEqual(sku_cnt_min, expected_cnt_min)
        self.assertEqual(sku_cnt_max, expected_cnt_max)

        # Phạm vi áp dụng chi nhánh và kênh bán (tùy chọn thiết kế cho generator)
        # Lưu ý: ERD tổ chức qua bảng liên kết promotion_branch và promotion_channel;
        # các hằng số này là tùy chọn thiết kế cho generator, cần BA/SV01 xác nhận cách ánh xạ.
        self.assertIsInstance(config.PROMOTION_BRANCH_SCOPE_OPTIONS, tuple)
        self.assertSetEqual(set(config.PROMOTION_BRANCH_SCOPE_OPTIONS), {"ALL", "SPECIFIC"})

        self.assertIsInstance(config.PROMOTION_CHANNEL_SCOPE_OPTIONS, tuple)
        self.assertSetEqual(set(config.PROMOTION_CHANNEL_SCOPE_OPTIONS), {"ALL", "POS_ONLY", "ONLINE_ONLY"})

    def test_stockout_and_return_rules(self):
        """Kiểm tra cấu hình stockout (1–3 ngày, 2%–3%), trả hàng (1%–2%, 7 ngày theo BR-10)."""
        self.assertEqual(config.STOCKOUT_SIMULATION_MODE, "MIXED")

        # Thời lượng stockout (1–3 ngày)
        dur_min, dur_max = config.STOCKOUT_DURATION_DAYS_RANGE
        self.assertEqual((dur_min, dur_max), (1, 3))
        self.assertGreater(dur_min, 0)
        self.assertLessEqual(dur_min, dur_max)

        # Tỷ lệ ngày stockout (2%–3%)
        so_min, so_max = config.STOCKOUT_DAYS_RATIO_RANGE
        self.assertEqual((so_min, so_max), (0.02, 0.03))
        self.assertGreater(so_min, 0.0)
        self.assertLessEqual(so_min, so_max)

        # Tỷ lệ trả hàng (1%–2%)
        ret_min, ret_max = config.RETURN_RATE_RANGE
        self.assertEqual((ret_min, ret_max), (0.01, 0.02))
        self.assertGreater(ret_min, 0.0)
        self.assertLessEqual(ret_min, ret_max)

        # Tỷ lệ trả toàn bộ vs một phần
        self.assertEqual(config.FULL_RETURN_RATE_AMONG_RETURNS, 0.50)
        self.assertEqual(config.PARTIAL_RETURN_RATE_AMONG_RETURNS, 0.50)
        self.assertEqual(
            config.FULL_RETURN_RATE_AMONG_RETURNS + config.PARTIAL_RETURN_RATE_AMONG_RETURNS,
            1.0,
        )

        # Cửa sổ trả hàng trong vòng 7 ngày kể từ khi đơn hoàn tất (BR-10)
        self.assertEqual(config.RETURN_WINDOW_DAYS, 7)

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
        """Kiểm tra random seed cố định là 2026 đảm bảo khả năng tái lập."""
        self.assertEqual(config.RANDOM_SEED, 2026)


if __name__ == "__main__":
    unittest.main()
