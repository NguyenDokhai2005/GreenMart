"""Unit tests và Integration tests cho module daily_sales (Task #109).

Kiểm thử toàn diện 19 ca kiểm thử logic nghiệp vụ (thuần túy trong bộ nhớ, không cần DB)
và 1 ca integration test kiểm tra Idempotent trên PostgreSQL (tự skip nếu không có DB).
"""

from datetime import date, datetime, timedelta, timezone
import math
import os
import unittest
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd
import pytest

from app.pipelines.daily_sales import (
    build_daily_sales,
    StockoutScheduleProvider,
    InventoryTransactionStockoutProvider,
    DailySalesPipeline,
)
from app.db.connection import (
    test_connection as check_db_connection,
    get_db_connection,
    get_connection_params,
)

TIMEZONE_VN = ZoneInfo("Asia/Ho_Chi_Minh")


def _make_sample_catalog():
    """Tạo danh mục nhỏ phục vụ kiểm thử (2 chi nhánh, 3 sản phẩm)."""
    df_branches = pd.DataFrame([
        {"id": 1, "code": "GM-001", "is_active": True},
        {"id": 2, "code": "GM-002", "is_active": True},
    ])
    df_products = pd.DataFrame([
        {"id": 101, "sku": "GAO-ST25-5KG", "sale_price": 120000, "is_active": True},
        {"id": 102, "sku": "RAU-CAI-500G", "sale_price": 18000, "is_active": True},
        {"id": 103, "sku": "SUA-TH-1L", "sale_price": 32000, "is_active": True},
    ])
    return df_branches, df_products


def _empty_dataframes():
    """Tạo tập DataFrame rỗng với đầy đủ tên cột chuẩn."""
    df_branches, df_products = _make_sample_catalog()
    df_orders = pd.DataFrame(columns=["id", "branch_id", "status", "completed_at", "subtotal", "discount", "total"])
    df_order_items = pd.DataFrame(columns=["id", "order_id", "product_id", "quantity", "unit_price", "discount", "line_total"])
    df_returns = pd.DataFrame(columns=["id", "order_id", "branch_id", "status", "approved_at", "refund_amount"])
    df_return_items = pd.DataFrame(columns=["id", "return_id", "order_item_id", "quantity", "condition"])
    df_promotions = pd.DataFrame(columns=["id", "code", "type", "level", "value", "start_at", "end_at", "status"])
    df_promo_branches = pd.DataFrame(columns=["promotion_id", "branch_id"])
    df_promo_products = pd.DataFrame(columns=["promotion_id", "product_id"])
    stockout_schedule = []
    return (
        df_orders,
        df_order_items,
        df_returns,
        df_return_items,
        df_promotions,
        df_promo_branches,
        df_promo_products,
        df_branches,
        df_products,
        stockout_schedule,
    )


class TestDailySalesPureLogic(unittest.TestCase):
    """Kiểm thử các quy tắc nghiệp vụ thông qua hàm thuần build_daily_sales (chạy trên CI không cần DB)."""

    def test_net_quantity_sales_and_return_different_days(self):
        """TC-01: Đơn COMPLETED ngày D, trả hàng ngày D+2 -> qty_sold ở ngày D, qty_returned ở D+2, qty_net đúng."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        # Đơn bán ngày 2026-03-01 lúc 10:00 (VN)
        dt_sale = datetime(2026, 3, 1, 10, 0, 0, tzinfo=TIMEZONE_VN)
        df_orders = pd.DataFrame([{
            "id": 1, "branch_id": 1, "status": "COMPLETED", "completed_at": dt_sale,
            "subtotal": 120000, "discount": 0, "total": 120000
        }])
        df_order_items = pd.DataFrame([{
            "id": 11, "order_id": 1, "product_id": 101, "quantity": 3,
            "unit_price": 40000, "discount": 0, "line_total": 120000
        }])

        # Phiếu trả duyệt ngày 2026-03-03 lúc 14:00 (VN)
        dt_ret = datetime(2026, 3, 3, 14, 0, 0, tzinfo=TIMEZONE_VN)
        df_returns = pd.DataFrame([{
            "id": 101, "order_id": 1, "branch_id": 1, "status": "APPROVED", "approved_at": dt_ret,
            "refund_amount": 40000
        }])
        df_return_items = pd.DataFrame([{
            "id": 201, "return_id": 101, "order_item_id": 11, "quantity": 1, "condition": "RESELLABLE"
        }])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 3, 1), date(2026, 3, 3)
        )

        # Kiểm tra ngày 2026-03-01 tại chi nhánh 1, sản phẩm 101
        r_d1 = res[(res["sale_date"] == date(2026, 3, 1)) & (res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertEqual(r_d1["qty_sold"], 3)
        self.assertEqual(r_d1["qty_returned"], 0)
        self.assertEqual(r_d1["qty_net"], 3)
        self.assertEqual(r_d1["revenue"], 120000)

        # Kiểm tra ngày 2026-03-03 tại chi nhánh 1, sản phẩm 101
        r_d3 = res[(res["sale_date"] == date(2026, 3, 3)) & (res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertEqual(r_d3["qty_sold"], 0)
        self.assertEqual(r_d3["qty_returned"], 1)
        self.assertEqual(r_d3["qty_net"], -1)
        self.assertEqual(r_d3["revenue"], 0)  # Có trả hàng nhưng không bán -> revenue >= 0 (bằng 0)

    def test_zero_sales_day_imputation(self):
        """TC-02: Phủ ngày 0 - 2 chi nhánh x 3 sản phẩm x 3 ngày = đúng 18 dòng với giá trị 0."""
        dfs = _empty_dataframes()
        res = build_daily_sales(*dfs, date(2026, 5, 1), date(2026, 5, 3))

        self.assertEqual(len(res), 18)
        self.assertTrue((res["qty_sold"] == 0).all())
        self.assertTrue((res["qty_returned"] == 0).all())
        self.assertTrue((res["qty_net"] == 0).all())
        self.assertTrue((res["revenue"] == 0).all())
        self.assertTrue((res["promo_flag"] == False).all())
        self.assertTrue((res["promo_discount_pct"] == 0.0).all())
        self.assertTrue((res["stockout"] == False).all())

    def test_failed_orders_excluded(self):
        """TC-03: Đơn CANCELLED, EXPIRED, REJECTED tuyệt đối không được tính vào qty_sold."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        dt_event = datetime(2026, 5, 1, 12, 0, 0, tzinfo=TIMEZONE_VN)
        df_orders = pd.DataFrame([
            {"id": 1, "branch_id": 1, "status": "CANCELLED", "completed_at": dt_event, "subtotal": 10000, "discount": 0, "total": 10000},
            {"id": 2, "branch_id": 1, "status": "EXPIRED", "completed_at": dt_event, "subtotal": 10000, "discount": 0, "total": 10000},
            {"id": 3, "branch_id": 1, "status": "REJECTED", "completed_at": dt_event, "subtotal": 10000, "discount": 0, "total": 10000},
            {"id": 4, "branch_id": 1, "status": "NEW", "completed_at": dt_event, "subtotal": 10000, "discount": 0, "total": 10000},
        ])
        df_order_items = pd.DataFrame([
            {"id": 11, "order_id": 1, "product_id": 101, "quantity": 5, "unit_price": 2000, "discount": 0, "line_total": 10000},
            {"id": 12, "order_id": 2, "product_id": 101, "quantity": 5, "unit_price": 2000, "discount": 0, "line_total": 10000},
            {"id": 13, "order_id": 3, "product_id": 101, "quantity": 5, "unit_price": 2000, "discount": 0, "line_total": 10000},
            {"id": 14, "order_id": 4, "product_id": 101, "quantity": 5, "unit_price": 2000, "discount": 0, "line_total": 10000},
        ])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 5, 1), date(2026, 5, 1)
        )
        r = res[(res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertEqual(r["qty_sold"], 0)
        self.assertEqual(r["revenue"], 0)

    def test_revenue_non_negative_constraint(self):
        """TC-04: Doanh thu áp dụng GREATEST(0, rev_sold - refund) không bao giờ bị âm."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        dt = datetime(2026, 6, 1, 10, 0, 0, tzinfo=TIMEZONE_VN)
        # Bán 1 cái thu 30.000 đ trong ngày 01/06
        df_orders = pd.DataFrame([
            {"id": 1, "branch_id": 1, "status": "COMPLETED", "completed_at": dt, "subtotal": 30000, "discount": 0, "total": 30000},
            {"id": 999, "branch_id": 1, "status": "COMPLETED", "completed_at": dt - timedelta(days=5), "subtotal": 60000, "discount": 0, "total": 60000},
        ])
        df_order_items = pd.DataFrame([
            {"id": 11, "order_id": 1, "product_id": 101, "quantity": 1, "unit_price": 30000, "discount": 0, "line_total": 30000},
            {"id": 9991, "order_id": 999, "product_id": 101, "quantity": 2, "unit_price": 30000, "discount": 0, "line_total": 60000},
        ])

        # Khách trả 2 cái của đơn cũ vào ngày 01/06, tiền hoàn 60.000 đ (nhiều hơn tiền bán 30.000 đ)
        df_returns = pd.DataFrame([{"id": 101, "order_id": 999, "branch_id": 1, "status": "APPROVED", "approved_at": dt, "refund_amount": 60000}])
        df_return_items = pd.DataFrame([{"id": 201, "return_id": 101, "order_item_id": 9991, "quantity": 2, "condition": "RESELLABLE"}])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 6, 1), date(2026, 6, 1)
        )
        r = res[(res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertEqual(r["qty_sold"], 1)
        self.assertEqual(r["qty_returned"], 2)
        self.assertEqual(r["qty_net"], -1)
        self.assertEqual(r["revenue"], 0)  # max(0, 30000 - 60000) = 0

    def test_stockout_flag_mapping(self):
        """TC-05: Cờ stockout gán đúng từ lịch schedule cho cả MID_DAY và FULL_DAY."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, _ = dfs

        stockout_schedule = [
            {"date": "2026-02-03", "branch_id": 1, "product_id": 101, "mode": "MID_DAY"},
            {"date": "2026-02-04", "branch_id": 1, "product_id": 101, "mode": "FULL_DAY"},
        ]

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockout_schedule,
            date(2026, 2, 3), date(2026, 2, 5)
        )

        r_mid = res[(res["sale_date"] == date(2026, 2, 3)) & (res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertTrue(r_mid["stockout"])

        r_full = res[(res["sale_date"] == date(2026, 2, 4)) & (res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertTrue(r_full["stockout"])

        r_none = res[(res["sale_date"] == date(2026, 2, 5)) & (res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertFalse(r_none["stockout"])

    def test_returned_order_counted_in_qty_sold(self):
        """TC-06: Đơn hàng có status='RETURNED' (bị trả toàn bộ) vẫn được tính vào qty_sold."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        dt = datetime(2026, 4, 10, 10, 0, 0, tzinfo=TIMEZONE_VN)
        df_orders = pd.DataFrame([{
            "id": 1, "branch_id": 1, "status": "RETURNED", "completed_at": dt,
            "subtotal": 36000, "discount": 0, "total": 36000
        }])
        df_order_items = pd.DataFrame([{
            "id": 11, "order_id": 1, "product_id": 102, "quantity": 2,
            "unit_price": 18000, "discount": 0, "line_total": 36000
        }])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 4, 10), date(2026, 4, 10)
        )
        r = res[(res["branch_id"] == 1) & (res["product_id"] == 102)].iloc[0]
        self.assertEqual(r["qty_sold"], 2)
        self.assertEqual(r["revenue"], 36000)

    def test_midnight_boundary_timezone(self):
        """TC-07: Đơn lúc 2026-04-06 00:30:00+07:00 (17:30 UTC 05/04) rơi vào ngày 06/04; ca 23:30+07 rơi vào 05/04."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        dt_after_midnight = datetime(2026, 4, 6, 0, 30, 0, tzinfo=TIMEZONE_VN)
        dt_before_midnight = datetime(2026, 4, 5, 23, 30, 0, tzinfo=TIMEZONE_VN)

        df_orders = pd.DataFrame([
            {"id": 1, "branch_id": 1, "status": "COMPLETED", "completed_at": dt_after_midnight, "subtotal": 10000, "discount": 0, "total": 10000},
            {"id": 2, "branch_id": 1, "status": "COMPLETED", "completed_at": dt_before_midnight, "subtotal": 10000, "discount": 0, "total": 10000},
        ])
        df_order_items = pd.DataFrame([
            {"id": 11, "order_id": 1, "product_id": 101, "quantity": 1, "unit_price": 10000, "discount": 0, "line_total": 10000},
            {"id": 12, "order_id": 2, "product_id": 101, "quantity": 2, "unit_price": 5000, "discount": 0, "line_total": 10000},
        ])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 4, 5), date(2026, 4, 6)
        )

        r_05 = res[(res["sale_date"] == date(2026, 4, 5)) & (res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertEqual(r_05["qty_sold"], 2)

        r_06 = res[(res["sale_date"] == date(2026, 4, 6)) & (res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertEqual(r_06["qty_sold"], 1)

    def test_pipeline_idempotency_pure_function(self):
        """TC-08a: Chạy build_daily_sales 2 lần trên cùng dữ liệu cho kết quả giống hệt 100%."""
        dfs = _empty_dataframes()
        res1 = build_daily_sales(*dfs, date(2026, 5, 1), date(2026, 5, 3))
        res2 = build_daily_sales(*dfs, date(2026, 5, 1), date(2026, 5, 3))
        pd.testing.assert_frame_equal(res1, res2)

    def test_cross_branch_return_assignment(self):
        """TC-09: Bán tại chi nhánh 1, nhưng khách trả tại chi nhánh 2 -> qty_returned tính cho chi nhánh 2."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        dt_sale = datetime(2026, 7, 1, 10, 0, 0, tzinfo=TIMEZONE_VN)
        dt_ret = datetime(2026, 7, 2, 10, 0, 0, tzinfo=TIMEZONE_VN)

        df_orders = pd.DataFrame([{"id": 1, "branch_id": 1, "status": "COMPLETED", "completed_at": dt_sale, "subtotal": 50000, "discount": 0, "total": 50000}])
        df_order_items = pd.DataFrame([{"id": 11, "order_id": 1, "product_id": 101, "quantity": 1, "unit_price": 50000, "discount": 0, "line_total": 50000}])

        # Trả tại branch_id = 2
        df_returns = pd.DataFrame([{"id": 101, "order_id": 1, "branch_id": 2, "status": "APPROVED", "approved_at": dt_ret, "refund_amount": 50000}])
        df_return_items = pd.DataFrame([{"id": 201, "return_id": 101, "order_item_id": 11, "quantity": 1, "condition": "RESELLABLE"}])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 7, 1), date(2026, 7, 2)
        )

        # Chi nhánh 1 ngày 02/07: không có trả
        r_b1 = res[(res["sale_date"] == date(2026, 7, 2)) & (res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertEqual(r_b1["qty_returned"], 0)

        # Chi nhánh 2 ngày 02/07: có trả 1 cái (qty_net = -1)
        r_b2 = res[(res["sale_date"] == date(2026, 7, 2)) & (res["branch_id"] == 2) & (res["product_id"] == 101)].iloc[0]
        self.assertEqual(r_b2["qty_returned"], 1)
        self.assertEqual(r_b2["qty_net"], -1)

    def test_returns_after_end_date_excluded(self):
        """TC-10: Phiếu trả hàng có ngày approved_at sau 31/10/2026 bị loại bỏ."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        dt_ret_after = datetime(2026, 11, 2, 10, 0, 0, tzinfo=TIMEZONE_VN)
        df_returns = pd.DataFrame([{"id": 101, "order_id": 1, "branch_id": 1, "status": "APPROVED", "approved_at": dt_ret_after, "refund_amount": 50000}])
        df_order_items = pd.DataFrame([{"id": 11, "order_id": 1, "product_id": 101, "quantity": 1, "unit_price": 50000, "discount": 0, "line_total": 50000}])
        df_return_items = pd.DataFrame([{"id": 201, "return_id": 101, "order_item_id": 11, "quantity": 1, "condition": "RESELLABLE"}])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 10, 30), date(2026, 10, 31)
        )
        self.assertEqual(res["qty_returned"].sum(), 0)

    def test_ended_promotions_still_flagged(self):
        """TC-11: Chương trình KM có status='ENDED' nhưng thời gian giao thoa vẫn được gán cờ promo_flag."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        df_promotions = pd.DataFrame([{
            "id": 10, "code": "KM-ENDED", "type": "PERCENT", "level": "ITEM", "value": 15,
            "start_at": datetime(2026, 1, 10, 0, 0, 0, tzinfo=TIMEZONE_VN),
            "end_at": datetime(2026, 1, 15, 23, 59, 59, tzinfo=TIMEZONE_VN),
            "status": "ENDED",
        }])
        df_p_b = pd.DataFrame([{"promotion_id": 10, "branch_id": 1}])
        df_p_p = pd.DataFrame([{"promotion_id": 10, "product_id": 101}])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 1, 12), date(2026, 1, 12)
        )
        r = res[(res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertTrue(r["promo_flag"])
        self.assertEqual(r["promo_discount_pct"], 15.0)

    def test_zero_sales_day_has_promo_flag_if_active(self):
        """TC-12: Ngày không bán được đơn nào (qty_sold=0) vẫn có cờ promo_flag và % giảm nếu đang có KM."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        df_promotions = pd.DataFrame([{
            "id": 10, "code": "KM-ZERO", "type": "PERCENT", "level": "ITEM", "value": 20,
            "start_at": datetime(2026, 8, 1, 0, 0, 0, tzinfo=TIMEZONE_VN),
            "end_at": datetime(2026, 8, 5, 23, 59, 59, tzinfo=TIMEZONE_VN),
            "status": "ACTIVE",
        }])
        df_p_b = pd.DataFrame([{"promotion_id": 10, "branch_id": 1}])
        df_p_p = pd.DataFrame([{"promotion_id": 10, "product_id": 102}])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 8, 2), date(2026, 8, 2)
        )
        r = res[(res["branch_id"] == 1) & (res["product_id"] == 102)].iloc[0]
        self.assertEqual(r["qty_sold"], 0)
        self.assertTrue(r["promo_flag"])
        self.assertEqual(r["promo_discount_pct"], 20.0)

    def test_draft_cancelled_returns_excluded(self):
        """TC-13: Phiếu trả DRAFT hoặc CANCELLED không được tính vào qty_returned."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        dt = datetime(2026, 8, 1, 10, 0, 0, tzinfo=TIMEZONE_VN)
        df_returns = pd.DataFrame([
            {"id": 101, "order_id": 1, "branch_id": 1, "status": "DRAFT", "approved_at": dt, "refund_amount": 50000},
            {"id": 102, "order_id": 1, "branch_id": 1, "status": "CANCELLED", "approved_at": dt, "refund_amount": 50000},
        ])
        df_order_items = pd.DataFrame([{"id": 11, "order_id": 1, "product_id": 101, "quantity": 2, "unit_price": 50000, "discount": 0, "line_total": 100000}])
        df_return_items = pd.DataFrame([
            {"id": 201, "return_id": 101, "order_item_id": 11, "quantity": 1, "condition": "RESELLABLE"},
            {"id": 202, "return_id": 102, "order_item_id": 11, "quantity": 1, "condition": "RESELLABLE"},
        ])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 8, 1), date(2026, 8, 1)
        )
        self.assertEqual(res["qty_returned"].sum(), 0)

    def test_refund_amount_formula_ba_5_2(self):
        """TC-14 (S4a): Công thức hoàn tiền chuẩn BA mục 5.2.
        Khách mua 3 bó rau (54.000 đ), KM đơn 22.300 đ trên tổng 223.000 đ.
        Khách trả 1 bó rau -> tiền hoàn đúng 16.200 đ!
        """
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        dt_sale = datetime(2026, 9, 1, 10, 0, 0, tzinfo=TIMEZONE_VN)
        dt_ret = datetime(2026, 9, 3, 10, 0, 0, tzinfo=TIMEZONE_VN)

        df_orders = pd.DataFrame([{
            "id": 1, "branch_id": 1, "status": "COMPLETED", "completed_at": dt_sale,
            "subtotal": 223000, "discount": 22300, "total": 200700
        }])
        df_order_items = pd.DataFrame([
            {"id": 11, "order_id": 1, "product_id": 102, "quantity": 3, "unit_price": 18000, "discount": 0, "line_total": 54000},
            {"id": 12, "order_id": 1, "product_id": 101, "quantity": 1, "unit_price": 169000, "discount": 0, "line_total": 169000},
        ])

        df_returns = pd.DataFrame([{
            "id": 101, "order_id": 1, "branch_id": 1, "status": "APPROVED", "approved_at": dt_ret,
            "refund_amount": 16200
        }])
        df_return_items = pd.DataFrame([
            {"id": 201, "return_id": 101, "order_item_id": 11, "quantity": 1, "condition": "RESELLABLE"}
        ])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 9, 1), date(2026, 9, 3)
        )

        r_sale = res[(res["sale_date"] == date(2026, 9, 1)) & (res["product_id"] == 102)].iloc[0]
        self.assertEqual(r_sale["revenue"], 48600)

        r_ret = res[(res["sale_date"] == date(2026, 9, 3)) & (res["product_id"] == 102)].iloc[0]
        self.assertEqual(r_ret["qty_returned"], 1)
        self.assertEqual(r_ret["qty_net"], -1)
        self.assertEqual(r_ret["revenue"], 0)

    def test_multiple_orders_same_day_aggregated(self):
        """TC-15 (S4b): Hai đơn bán cùng ngày được cộng dồn đúng qty_sold và revenue."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        dt1 = datetime(2026, 9, 1, 9, 0, 0, tzinfo=TIMEZONE_VN)
        dt2 = datetime(2026, 9, 1, 15, 0, 0, tzinfo=TIMEZONE_VN)

        df_orders = pd.DataFrame([
            {"id": 1, "branch_id": 1, "status": "COMPLETED", "completed_at": dt1, "subtotal": 40000, "discount": 0, "total": 40000},
            {"id": 2, "branch_id": 1, "status": "COMPLETED", "completed_at": dt2, "subtotal": 80000, "discount": 0, "total": 80000},
        ])
        df_order_items = pd.DataFrame([
            {"id": 11, "order_id": 1, "product_id": 101, "quantity": 1, "unit_price": 40000, "discount": 0, "line_total": 40000},
            {"id": 12, "order_id": 2, "product_id": 101, "quantity": 2, "unit_price": 40000, "discount": 0, "line_total": 80000},
        ])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 9, 1), date(2026, 9, 1)
        )
        r = res[(res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertEqual(r["qty_sold"], 3)
        self.assertEqual(r["revenue"], 120000)

    def test_multiple_item_promotions_max_discount_pct(self):
        """TC-16 (S4c): Nhiều KM ITEM cùng ngày cho 1 sản phẩm tại chi nhánh: lấy MAX % giảm giá."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        dt_start = datetime(2026, 9, 1, 0, 0, 0, tzinfo=TIMEZONE_VN)
        dt_end = datetime(2026, 9, 5, 23, 59, 59, tzinfo=TIMEZONE_VN)

        df_promotions = pd.DataFrame([
            {"id": 10, "code": "KM-10", "type": "PERCENT", "level": "ITEM", "value": 10, "start_at": dt_start, "end_at": dt_end, "status": "ACTIVE"},
            {"id": 11, "code": "KM-25", "type": "PERCENT", "level": "ITEM", "value": 25, "start_at": dt_start, "end_at": dt_end, "status": "ACTIVE"},
        ])
        df_p_b = pd.DataFrame([{"promotion_id": 10, "branch_id": 1}, {"promotion_id": 11, "branch_id": 1}])
        df_p_p = pd.DataFrame([{"promotion_id": 10, "product_id": 101}, {"promotion_id": 11, "product_id": 101}])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 9, 2), date(2026, 9, 2)
        )
        r = res[(res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertEqual(r["promo_discount_pct"], 25.0)

    def test_amount_promotion_converted_to_pct_cap_100(self):
        """TC-17 (S4d): KM kiểu AMOUNT quy đổi sang % theo sale_price và chặn trên <= 100.00."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        dt_start = datetime(2026, 9, 1, 0, 0, 0, tzinfo=TIMEZONE_VN)
        dt_end = datetime(2026, 9, 5, 23, 59, 59, tzinfo=TIMEZONE_VN)

        df_promotions = pd.DataFrame([
            {"id": 10, "code": "KM-AMOUNT-BIG", "type": "AMOUNT", "level": "ITEM", "value": 20000, "start_at": dt_start, "end_at": dt_end, "status": "ACTIVE"},
        ])
        df_p_b = pd.DataFrame([{"promotion_id": 10, "branch_id": 1}])
        df_p_p = pd.DataFrame([{"promotion_id": 10, "product_id": 102}])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 9, 2), date(2026, 9, 2)
        )
        r = res[(res["branch_id"] == 1) & (res["product_id"] == 102)].iloc[0]
        self.assertEqual(r["promo_discount_pct"], 100.0)

    def test_promotions_other_branch_or_product_not_flagged(self):
        """TC-18 (S4e): KM của chi nhánh khác hoặc sản phẩm khác không bị gán cờ nhầm."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        dt_start = datetime(2026, 9, 1, 0, 0, 0, tzinfo=TIMEZONE_VN)
        dt_end = datetime(2026, 9, 5, 23, 59, 59, tzinfo=TIMEZONE_VN)

        df_promotions = pd.DataFrame([
            {"id": 10, "code": "KM-B2-P103", "type": "PERCENT", "level": "ITEM", "value": 15, "start_at": dt_start, "end_at": dt_end, "status": "ACTIVE"},
        ])
        df_p_b = pd.DataFrame([{"promotion_id": 10, "branch_id": 2}])
        df_p_p = pd.DataFrame([{"promotion_id": 10, "product_id": 103}])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 9, 2), date(2026, 9, 2)
        )
        r_b1_p101 = res[(res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertFalse(r_b1_p101["promo_flag"])

        r_b2_p103 = res[(res["branch_id"] == 2) & (res["product_id"] == 103)].iloc[0]
        self.assertTrue(r_b2_p103["promo_flag"])
        self.assertEqual(r_b2_p103["promo_discount_pct"], 15.0)

    def test_damaged_goods_included_in_qty_returned(self):
        """TC-19 (S4f): Hàng trả tình trạng DAMAGED vẫn tính vào qty_returned."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        dt = datetime(2026, 9, 1, 10, 0, 0, tzinfo=TIMEZONE_VN)
        df_returns = pd.DataFrame([{"id": 101, "order_id": 1, "branch_id": 1, "status": "APPROVED", "approved_at": dt, "refund_amount": 18000}])
        df_order_items = pd.DataFrame([{"id": 11, "order_id": 1, "product_id": 102, "quantity": 1, "unit_price": 18000, "discount": 0, "line_total": 18000}])
        df_return_items = pd.DataFrame([{"id": 201, "return_id": 101, "order_item_id": 11, "quantity": 1, "condition": "DAMAGED"}])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 9, 1), date(2026, 9, 1)
        )
        r = res[(res["branch_id"] == 1) & (res["product_id"] == 102)].iloc[0]
        self.assertEqual(r["qty_returned"], 1)
        self.assertEqual(r["qty_net"], -1)

    def test_sale_before_start_date_return_within_range(self):
        """TC-20 (K2a): Đơn bán trước start_date nhưng phiếu trả nằm trong khoảng -> qty_returned và refund vẫn đúng."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        # Đơn bán ngày 2026-05-20 (trước khoảng lọc 2026-06-05 đến 2026-06-07)
        dt_sale = datetime(2026, 5, 20, 10, 0, 0, tzinfo=TIMEZONE_VN)
        df_orders = pd.DataFrame([{
            "id": 1, "branch_id": 1, "status": "COMPLETED", "completed_at": dt_sale,
            "subtotal": 200000, "discount": 0, "total": 200000
        }])
        df_order_items = pd.DataFrame([{
            "id": 11, "order_id": 1, "product_id": 101, "quantity": 5,
            "unit_price": 40000, "discount": 0, "line_total": 200000
        }])

        # Phiếu trả hàng duyệt ngày 2026-06-06 (nằm trong khoảng lọc)
        dt_ret = datetime(2026, 6, 6, 14, 0, 0, tzinfo=TIMEZONE_VN)
        df_returns = pd.DataFrame([{
            "id": 101, "order_id": 1, "branch_id": 1, "status": "APPROVED", "approved_at": dt_ret,
            "refund_amount": 80000
        }])
        df_return_items = pd.DataFrame([{
            "id": 201, "return_id": 101, "order_item_id": 11, "quantity": 2, "condition": "RESELLABLE"
        }])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 6, 5), date(2026, 6, 7)
        )

        # Ngày 2026-06-06 tại chi nhánh 1, sản phẩm 101:
        # Không có đơn bán mới -> qty_sold = 0, nhưng ghi nhận trả hàng: qty_returned = 2, qty_net = -2, revenue = 0
        r_ret_day = res[(res["sale_date"] == date(2026, 6, 6)) & (res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertEqual(r_ret_day["qty_sold"], 0)
        self.assertEqual(r_ret_day["qty_returned"], 2)
        self.assertEqual(r_ret_day["qty_net"], -2)
        self.assertEqual(r_ret_day["revenue"], 0)

        # Các ngày khác không có trả cũng không có bán -> đều bằng 0
        r_other_day = res[(res["sale_date"] == date(2026, 6, 5)) & (res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertEqual(r_other_day["qty_sold"], 0)
        self.assertEqual(r_other_day["qty_returned"], 0)
        self.assertEqual(r_other_day["qty_net"], 0)
        self.assertEqual(r_other_day["revenue"], 0)

    def test_stockout_and_promo_outside_range_ignored(self):
        """TC-21 (K2b): Lịch stockout và khuyến mại có ngày ngoài [start_date, end_date] bị bỏ qua, không gây lỗi."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        # Khoảng lọc: 2026-07-10 đến 2026-07-12
        d_start = date(2026, 7, 10)
        d_end = date(2026, 7, 12)

        # Lịch stockout: 1 sự kiện trước d_start, 1 sự kiện sau d_end, 1 sự kiện đúng ngày d_start + 1
        stockouts = [
            {"date": "2026-07-01", "branch_id": 1, "sku": "GAO-ST25-5KG"},  # Ngoài khoảng (trước)
            {"date": "2026-07-11", "branch_id": 2, "sku": "RAU-CAI-500G"},  # Trong khoảng
            {"date": "2026-07-25", "branch_id": 1, "sku": "SUA-TH-1L"},      # Ngoài khoảng (sau)
        ]

        # Khuyến mại: 1 cái trước d_start, 1 cái sau d_end, 1 cái giao trong khoảng
        dt_before_start = datetime(2026, 6, 1, 0, 0, 0, tzinfo=TIMEZONE_VN)
        dt_before_end = datetime(2026, 6, 30, 23, 59, 59, tzinfo=TIMEZONE_VN)

        dt_after_start = datetime(2026, 8, 1, 0, 0, 0, tzinfo=TIMEZONE_VN)
        dt_after_end = datetime(2026, 8, 31, 23, 59, 59, tzinfo=TIMEZONE_VN)

        dt_in_start = datetime(2026, 7, 10, 0, 0, 0, tzinfo=TIMEZONE_VN)
        dt_in_end = datetime(2026, 7, 11, 23, 59, 59, tzinfo=TIMEZONE_VN)

        df_promotions = pd.DataFrame([
            {"id": 1, "code": "KM-BEFORE", "type": "PERCENT", "level": "ITEM", "value": 10, "start_at": dt_before_start, "end_at": dt_before_end, "status": "ACTIVE"},
            {"id": 2, "code": "KM-IN-RANGE", "type": "PERCENT", "level": "ITEM", "value": 20, "start_at": dt_in_start, "end_at": dt_in_end, "status": "ACTIVE"},
            {"id": 3, "code": "KM-AFTER", "type": "PERCENT", "level": "ITEM", "value": 30, "start_at": dt_after_start, "end_at": dt_after_end, "status": "ACTIVE"},
        ])
        df_p_b = pd.DataFrame([{"promotion_id": 1, "branch_id": 1}, {"promotion_id": 2, "branch_id": 1}, {"promotion_id": 3, "branch_id": 1}])
        df_p_p = pd.DataFrame([{"promotion_id": 1, "product_id": 101}, {"promotion_id": 2, "product_id": 101}, {"promotion_id": 3, "product_id": 101}])

        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            d_start, d_end
        )

        # 1. Kiểm tra ngày 2026-07-10: KM-IN-RANGE có hiệu lực (20%), không bị ảnh hưởng bởi KM-BEFORE
        r_d10 = res[(res["sale_date"] == date(2026, 7, 10)) & (res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertTrue(r_d10["promo_flag"])
        self.assertEqual(r_d10["promo_discount_pct"], 20.0)
        self.assertFalse(r_d10["stockout"])

        # 2. Kiểm tra ngày 2026-07-11: branch 2, product 102 bị stockout
        r_d11_b2 = res[(res["sale_date"] == date(2026, 7, 11)) & (res["branch_id"] == 2) & (res["product_id"] == 102)].iloc[0]
        self.assertTrue(r_d11_b2["stockout"])

        # branch 1, product 101 không bị stockout (ngày 07-01 và 07-25 nằm ngoài khoảng)
        r_d11_b1 = res[(res["sale_date"] == date(2026, 7, 11)) & (res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertFalse(r_d11_b1["stockout"])

        # 3. Kiểm tra ngày 2026-07-12: không có KM nào (KM-IN-RANGE đã hết, KM-AFTER chưa tới)
        r_d12 = res[(res["sale_date"] == date(2026, 7, 12)) & (res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertFalse(r_d12["promo_flag"])
        self.assertEqual(r_d12["promo_discount_pct"], 0.0)
        self.assertFalse(r_d12["stockout"])

    def test_utc_timestamps_correctly_converted_to_vn_date(self):
        """TC-22 (K2c): Cột thời gian kiểu UTC (như psycopg2 trả về) được quy đổi đúng về ngày giờ Việt Nam."""
        dfs = _empty_dataframes()
        df_orders, df_order_items, df_returns, df_return_items, df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts = dfs

        # Đơn 1: Hoàn tất lúc 2026-08-01 17:30:00 UTC -> tương đương 2026-08-02 00:30:00 VN (+7)
        dt_sale_utc1 = datetime(2026, 8, 1, 17, 30, 0, tzinfo=timezone.utc)

        # Đơn 2: Hoàn tất lúc 2026-08-02 16:55:00 UTC -> tương đương 2026-08-02 23:55:00 VN (+7)
        dt_sale_utc2 = datetime(2026, 8, 2, 16, 55, 0, tzinfo=timezone.utc)

        df_orders = pd.DataFrame([
            {"id": 1, "branch_id": 1, "status": "COMPLETED", "completed_at": dt_sale_utc1, "subtotal": 120000, "discount": 0, "total": 120000},
            {"id": 2, "branch_id": 1, "status": "COMPLETED", "completed_at": dt_sale_utc2, "subtotal": 120000, "discount": 0, "total": 120000},
        ])
        df_order_items = pd.DataFrame([
            {"id": 11, "order_id": 1, "product_id": 101, "quantity": 1, "unit_price": 120000, "discount": 0, "line_total": 120000},
            {"id": 12, "order_id": 2, "product_id": 101, "quantity": 2, "unit_price": 120000, "discount": 0, "line_total": 120000},
        ])

        # Phiếu trả 1: Duyệt lúc 2026-08-02 17:05:00 UTC -> tương đương 2026-08-03 00:05:00 VN (+7)
        dt_ret_utc1 = datetime(2026, 8, 2, 17, 5, 0, tzinfo=timezone.utc)
        df_returns = pd.DataFrame([
            {"id": 101, "order_id": 1, "branch_id": 1, "status": "APPROVED", "approved_at": dt_ret_utc1, "refund_amount": 120000}
        ])
        df_return_items = pd.DataFrame([
            {"id": 201, "return_id": 101, "order_item_id": 11, "quantity": 1, "condition": "RESELLABLE"}
        ])

        # Khoảng lọc: 2026-08-02 đến 2026-08-03
        res = build_daily_sales(
            df_orders, df_order_items, df_returns, df_return_items,
            df_promotions, df_p_b, df_p_p, df_branches, df_products, stockouts,
            date(2026, 8, 2), date(2026, 8, 3)
        )

        # Cả đơn 1 (00:30 VN) và đơn 2 (23:55 VN) đều phải thuộc về ngày 2026-08-02 (tổng qty_sold = 1 + 2 = 3)
        r_d02 = res[(res["sale_date"] == date(2026, 8, 2)) & (res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertEqual(r_d02["qty_sold"], 3)
        self.assertEqual(r_d02["qty_returned"], 0)
        self.assertEqual(r_d02["qty_net"], 3)

        # Phiếu trả (00:05 VN) phải rơi vào ngày 2026-08-03 (qty_returned = 1)
        r_d03 = res[(res["sale_date"] == date(2026, 8, 3)) & (res["branch_id"] == 1) & (res["product_id"] == 101)].iloc[0]
        self.assertEqual(r_d03["qty_sold"], 0)
        self.assertEqual(r_d03["qty_returned"], 1)
        self.assertEqual(r_d03["qty_net"], -1)

    def test_stockout_schedule_provider_missing_file_raises_filenotfound(self):
        """TC-20: StockoutScheduleProvider ném FileNotFoundError nếu file không tồn tại và allow_missing=False."""
        from pathlib import Path
        missing_path = Path("non_existent_stockout_schedule_test_123.json")
        with self.assertRaises(FileNotFoundError) as ctx:
            StockoutScheduleProvider.load_schedule(missing_path, allow_missing=False)
        self.assertIn("Không tìm thấy file nguồn lịch stockout", str(ctx.exception))

    def test_stockout_schedule_provider_missing_file_allow_missing(self):
        """TC-21: StockoutScheduleProvider trả về [] nếu file không tồn tại nhưng allow_missing=True."""
        from pathlib import Path
        missing_path = Path("non_existent_stockout_schedule_test_123.json")
        result = StockoutScheduleProvider.load_schedule(missing_path, allow_missing=True)
        self.assertEqual(result, [])

    def test_stockout_schedule_provider_valid_file(self):
        """TC-22: StockoutScheduleProvider đọc đúng dữ liệu từ file JSON hợp lệ."""
        import tempfile
        import json
        from pathlib import Path

        sample_data = [{"date": "2026-08-15", "branch_id": 1, "product_id": 101}]
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
            json.dump(sample_data, f)
            temp_path = Path(f.name)

        try:
            data = StockoutScheduleProvider.load_schedule(temp_path)
            self.assertEqual(len(data), 1)
            self.assertEqual(data[0]["branch_id"], 1)
        finally:
            if temp_path.exists():
                temp_path.unlink()

    def test_db_connection_params_rejects_missing_forecast_user(self):
        """TC-23: get_connection_params báo lỗi nếu thiếu FORECAST_DB_USER, không lấy POSTGRES_USER."""
        from unittest.mock import patch
        with patch.dict(os.environ, {"POSTGRES_USER": "postgres_root"}, clear=True):
            with self.assertRaises(ValueError) as ctx:
                get_connection_params()
            self.assertIn("FORECAST_DB_USER", str(ctx.exception))

    def test_db_connection_params_rejects_missing_forecast_password(self):
        """TC-24: get_connection_params báo lỗi nếu thiếu FORECAST_DB_PASSWORD, không lấy POSTGRES_PASSWORD."""
        from unittest.mock import patch
        with patch.dict(os.environ, {"FORECAST_DB_USER": "forecast_ro", "POSTGRES_PASSWORD": "secret_root"}, clear=True):
            with self.assertRaises(ValueError) as ctx:
                get_connection_params()
            self.assertIn("FORECAST_DB_PASSWORD", str(ctx.exception))

    def test_db_connection_params_success_with_forecast_ro(self):
        """TC-25: get_connection_params lấy đúng tài khoản FORECAST_DB_*."""
        from unittest.mock import patch
        env_mock = {
            "FORECAST_DB_HOST": "dbhost",
            "FORECAST_DB_PORT": "5433",
            "POSTGRES_DB": "greenmart_test",
            "FORECAST_DB_USER": "ro_user",
            "FORECAST_DB_PASSWORD": "ro_password",
        }
        with patch.dict(os.environ, env_mock, clear=True):
            params = get_connection_params()
            self.assertEqual(params["host"], "dbhost")
            self.assertEqual(params["port"], 5433)
            self.assertEqual(params["dbname"], "greenmart_test")
            self.assertEqual(params["user"], "ro_user")
            self.assertEqual(params["password"], "ro_password")


@pytest.mark.integration
def test_upsert_integration_idempotent():
    """TC-23 (B5 / Mục 1.1): Integration test nạp DB và kiểm tra Idempotent.
    
    Chạy trên khoảng ngày hoàn toàn ngoài dữ liệu thật (2099-01-01..2099-01-03) để đảm bảo:
    1. Không ghi đè hay ảnh hưởng đến dữ liệu nghiệp vụ thật (2025-11-01..2026-10-31).
    2. Bọc trong try/finally với câu lệnh DELETE có điều kiện chỉ xóa đúng khoảng ngày 2099.
    3. Kiểm tra đầu test: Nếu đã có dữ liệu trong khoảng 2099 thì skip thay vì ghi đè.
    4. Tự động skip nếu không kết nối được PostgreSQL container.
    """
    if not check_db_connection():
        pytest.skip("Không thể kết nối PostgreSQL container - bỏ qua integration test.")

    d_start = date(2099, 1, 1)
    d_end = date(2099, 1, 3)

    # Kiểm tra an toàn đầu test: Nếu khoảng ngày 2099 đã có dữ liệu thì skip
    with get_db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT COUNT(*) FROM daily_sales WHERE sale_date BETWEEN %s AND %s;",
                (d_start, d_end),
            )
            existing_cnt = cur.fetchone()[0]
            if existing_cnt > 0:
                pytest.skip(f"Khoảng ngày test {d_start}..{d_end} đã có sẵn {existing_cnt} dòng, bỏ qua để tránh ghi đè.")

    from app.pipelines.daily_sales import DailySalesPipeline
    pipeline = DailySalesPipeline()

    try:
        # Chạy nạp lần 1: 3 ngày x 3 chi nhánh x 1000 sản phẩm = 9000 dòng (ngày 0)
        stats1 = pipeline.run(d_start, d_end, dry_run=False)
        assert stats1["total_rows"] == 9000

        with get_db_connection() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT COUNT(*) FROM daily_sales WHERE sale_date BETWEEN %s AND %s;",
                    (d_start, d_end),
                )
                cnt1 = cur.fetchone()[0]
                assert cnt1 == 9000

        # Chạy nạp lần 2: Kiểm tra tính idempotent (không sinh thêm dòng trùng lặp, không lỗi)
        stats2 = pipeline.run(d_start, d_end, dry_run=False)
        assert stats2["total_rows"] == 9000

        with get_db_connection() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT COUNT(*) FROM daily_sales WHERE sale_date BETWEEN %s AND %s;",
                    (d_start, d_end),
                )
                cnt2 = cur.fetchone()[0]
                assert cnt2 == 9000

    finally:
        # Dọn dẹp an toàn: Chỉ xóa ĐÚNG khoảng ngày 2099 của test, không chạm bất kỳ dữ liệu nào khác
        try:
            with get_db_connection() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        "DELETE FROM daily_sales WHERE sale_date BETWEEN %s AND %s;",
                        (d_start, d_end),
                    )
                conn.commit()
        except Exception:
            pass
