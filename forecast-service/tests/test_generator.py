"""Unit tests toàn diện cho bộ sinh dữ liệu mẫu GreenMart (Chặng 1, 2, 3, 4)."""

from __future__ import annotations

from collections import defaultdict
from datetime import date, datetime, timedelta
import hashlib
import io
import json
import math
from pathlib import Path
import sys
import unittest

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))
SCRIPTS_DIR = PROJECT_ROOT / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from scripts import config, generate_data


class TestGeneratorStage1(unittest.TestCase):
    """Kiểm tra tính đúng đắn của dữ liệu sinh ở Chặng 1."""

    def test_branches_count_and_codes(self):
        """TC-AC01-01: Đủ đúng 3 chi nhánh với mã GM-001, GM-002, GM-003."""
        branches = generate_data.generate_branches()
        self.assertEqual(len(branches), 3)

        codes = [b.code for b in branches]
        self.assertEqual(codes, ["GM-001", "GM-002", "GM-003"])

        for b in branches:
            self.assertTrue(b.is_active)
            self.assertTrue(len(b.name) > 0)
            self.assertTrue(len(b.address) > 0)

    def test_category_hierarchy_and_groups(self):
        """Kiểm tra cây danh mục 2 cấp: 3 danh mục cha, 15 danh mục con, ánh xạ chuẩn 3 nhóm ngành."""
        categories = generate_data.generate_categories()
        self.assertEqual(len(categories), 18)

        parents = [c for c in categories if c.parent_id is None]
        children = [c for c in categories if c.parent_id is not None]

        self.assertEqual(len(parents), 3)
        self.assertEqual(len(children), 15)

        parent_groups = {p.group_code for p in parents}
        expected_groups = {"READY_TO_EAT_BEVERAGE", "FRESH_FOOD", "DRY_FOOD_FMCG"}
        self.assertSetEqual(parent_groups, expected_groups)

        parent_ids = {p.id for p in parents}
        for child in children:
            self.assertIn(child.parent_id, parent_ids)
            parent = next(p for p in parents if p.id == child.parent_id)
            self.assertEqual(child.group_code, parent.group_code)

    def test_products_count_and_pareto(self):
        """TC-AC01-02: Đủ đúng 1.000 sản phẩm và phân định đúng 200 SKU top (Pareto 80/20)."""
        products, top_sku_ids = generate_data.generate_products()

        self.assertEqual(len(products), config.TOTAL_SKU_COUNT)
        self.assertEqual(len(products), 1_000)
        self.assertEqual(len(top_sku_ids), config.PARETO_TARGET_SKU_COUNT)
        self.assertEqual(len(top_sku_ids), 200)

        ids = [p.id for p in products]
        skus = [p.sku for p in products]
        barcodes = [p.barcode for p in products]

        self.assertEqual(len(set(ids)), 1_000)
        self.assertEqual(len(set(skus)), 1_000)
        self.assertEqual(len(set(barcodes)), 1_000)

        for p in products:
            self.assertGreater(p.sale_price, 0)
            self.assertGreater(p.cost_price, 0)
            self.assertGreater(p.sale_price, p.cost_price, f"Lãi gộp sản phẩm {p.sku} phải dương")
            self.assertEqual(p.is_top_sku, p.id in top_sku_ids)
            self.assertTrue(p.is_active)

    def test_reproduced_examples_from_docs(self):
        """Tái hiện chính xác các sản phẩm ví dụ."""
        products, top_sku_ids = generate_data.generate_products()
        products_dict = {p.id: p for p in products}

        # ID 101: Gạo ST25 5kg
        self.assertIn(101, products_dict)
        rice = products_dict[101]
        self.assertEqual(rice.sku, "GAO-ST25-5KG")
        self.assertEqual(rice.name, "Gạo ST25 túi 5kg")
        self.assertEqual(rice.unit, "túi")
        self.assertEqual(rice.sale_price, 180_000)
        self.assertEqual(rice.cost_price, 140_000)
        self.assertIn(101, top_sku_ids)

        # ID 205: Sữa tươi TH True Milk 1L
        self.assertIn(205, products_dict)
        milk = products_dict[205]
        self.assertEqual(milk.sku, "SUA-TH-1L")
        self.assertEqual(milk.barcode, "8935217400013")
        self.assertEqual(milk.name, "Sữa tươi TH True Milk 1L")
        self.assertEqual(milk.unit, "hộp")
        self.assertEqual(milk.sale_price, 29_000)
        self.assertEqual(milk.cost_price, 22_000)
        self.assertIn(205, top_sku_ids)

    def test_demo_users_and_openapi_alignment(self):
        """Kiểm tra danh sách 12 tài khoản demo và khớp thu ngân ID 7, không bỏ trống ID 6."""
        users = generate_data.generate_demo_users()
        self.assertEqual(len(users), 12)

        user_ids = [u.id for u in users]
        self.assertEqual(user_ids, list(range(1, 13)))

        user_6 = next(u for u in users if u.id == 6)
        self.assertEqual(user_6.role, "OWNER")
        self.assertEqual(user_6.username, "owner.pho")

        user_7 = next(u for u in users if u.id == 7)
        self.assertEqual(user_7.username, "thungan.gm001")
        self.assertEqual(user_7.full_name, "Nguyễn Thu Ngân")
        self.assertEqual(user_7.role, "CASHIER")
        self.assertEqual(user_7.branch_ids, [1])

        for u in users:
            self.assertTrue(u.password_hash.startswith("$2a$10$"))


class TestGeneratorStage2(unittest.TestCase):
    """Kiểm tra tính đúng đắn của dữ liệu sinh ở Chặng 2 (Khách, Khuyến mại, Stockout, 10.000 đơn)."""

    @classmethod
    def setUpClass(cls):
        cls.branches = generate_data.generate_branches()
        cls.categories = generate_data.generate_categories()
        cls.products, cls.top_sku_ids = generate_data.generate_products()
        cls.customers = generate_data.generate_customers(count=1500)
        cls.promotions = generate_data.generate_promotions(cls.products)
        cls.stockout_schedule = generate_data.get_stockout_schedule(cls.products, cls.top_sku_ids)
        cls.orders = generate_data.generate_orders_and_items(
            products=cls.products,
            top_sku_ids=cls.top_sku_ids,
            customers=cls.customers,
            promotions=cls.promotions,
            stockout_schedule=cls.stockout_schedule,
        )

    def test_customers_integrity(self):
        """Kiểm tra 1.500 khách hàng và tái hiện đúng khách hàng ID 125 Trần Thị Mai."""
        self.assertEqual(len(self.customers), 1500)

        cust_dict = {c.id: c for c in self.customers}
        self.assertIn(125, cust_dict)
        c125 = cust_dict[125]
        self.assertEqual(c125.name, "Trần Thị Mai")
        self.assertEqual(c125.phone, "0901234567")

        phones = [c.phone for c in self.customers]
        self.assertEqual(len(set(phones)), len(phones))

    def test_promotions_and_examples(self):
        """Kiểm tra các chương trình khuyến mại trong 12 tháng và mã mẫu GAO-15K, DON200K-10."""
        promo_dict = {p.id: p for p in self.promotions}

        # ID 11: GAO-15K (giảm 15.000 đ)
        self.assertIn(11, promo_dict)
        p11 = promo_dict[11]
        self.assertEqual(p11.code, "GAO-15K")
        self.assertEqual(p11.type, "AMOUNT")
        self.assertEqual(p11.level, "ITEM")
        self.assertEqual(p11.value, 15_000)
        self.assertIn(101, p11.product_ids)

        # ID 12: DON200K-10 (giảm 10%)
        self.assertIn(12, promo_dict)
        p12 = promo_dict[12]
        self.assertEqual(p12.code, "DON200K-10")
        self.assertEqual(p12.type, "PERCENT")
        self.assertEqual(p12.level, "ORDER")
        self.assertEqual(p12.value, 10)
        self.assertEqual(p12.min_order, 200_000)
        self.assertEqual(p12.max_discount, 30_000)

        self.assertGreater(p11.used_count, 0)
        self.assertGreater(p12.used_count, 0)

        # 12 chiến dịch ITEM hàng tháng: mỗi chiến dịch 20-50 SKU
        item_promos = [p for p in self.promotions if p.level == "ITEM" and p.id != 11]
        self.assertEqual(len(item_promos), 12)
        for p in item_promos:
            self.assertGreaterEqual(len(p.product_ids), config.PROMOTION_ITEM_SKU_COUNT_RANGE[0])
            self.assertLessEqual(len(p.product_ids), config.PROMOTION_ITEM_SKU_COUNT_RANGE[1])
            self.assertGreater(p.used_count, 0)

    def test_stockout_schedule_and_filtering(self):
        """BƯỚC 0: Kiểm tra lịch stockout 2-3% ngày cho 200 top SKU và chặn bán khi stockout."""
        pair_counts: dict[tuple[int, int], int] = {}
        for (b_id, p_id, d), mode in self.stockout_schedule.items():
            pair_counts[(b_id, p_id)] = pair_counts.get((b_id, p_id), 0) + 1

        self.assertEqual(len(pair_counts), 600, "Đủ đúng 600 cặp (3 chi nhánh × 200 top SKU)")
        for (b_id, p_id), cnt in pair_counts.items():
            # 7 đến 11 ngày / 365 ngày (1.92% đến 3.01%)
            self.assertGreaterEqual(cnt, 7)
            self.assertLessEqual(cnt, 11)

        # Kiểm tra không bán FULL_DAY và chỉ bán trước 11:00 với MID_DAY
        for o in self.orders:
            o_date = o.created_at.date()
            b_id = o.branch_id
            for it in o.items:
                stock_mode = self.stockout_schedule.get((b_id, it.product_id, o_date))
                self.assertNotEqual(stock_mode, "FULL_DAY", f"SKU {it.product_id} không được bán trong ngày FULL_DAY tại CN {b_id}")
                if stock_mode == "MID_DAY":
                    self.assertLess(o.created_at.hour, 11, f"SKU {it.product_id} hết hàng sau 11:00 nhưng lại xuất hiện trong đơn lúc {o.created_at.hour}h")

    def test_order_quotas_channels_and_branches(self):
        """TC-AC01-03: Đủ đúng 10.000 đơn và tỷ lệ Kênh 70/20/10 và Chi nhánh 45/35/20 đồng thời."""
        self.assertEqual(len(self.orders), config.TOTAL_PROCESSED_ORDERS)
        self.assertEqual(len(self.orders), 10_000)

        ch_counts: dict[str, int] = {}
        for o in self.orders:
            ch_counts[o.channel_code] = ch_counts.get(o.channel_code, 0) + 1

        self.assertEqual(ch_counts["POS"], 7_000)
        self.assertEqual(ch_counts["WEB"], 2_000)
        self.assertEqual(ch_counts["MARKET"], 1_000)

        b_counts: dict[str, int] = {}
        for o in self.orders:
            b_counts[o.branch_code] = b_counts.get(o.branch_code, 0) + 1

        self.assertEqual(b_counts["GM-001"], 4_500)
        self.assertEqual(b_counts["GM-002"], 3_500)
        self.assertEqual(b_counts["GM-003"], 2_000)

    def test_order_check_constraints_and_pricing(self):
        """Kiểm tra mọi ràng buộc CHECK của V1 trên sales_order và order_item."""
        order_codes: set[str] = set()
        ext_order_ids: set[tuple[int, str]] = set()

        for o in self.orders:
            self.assertNotIn(o.code, order_codes, f"Trùng mã đơn {o.code}")
            order_codes.add(o.code)

            if o.channel_code in ("WEB", "MARKET"):
                self.assertIsNotNone(o.external_order_id)
                self.assertNotIn((o.channel_id, o.external_order_id), ext_order_ids)
                ext_order_ids.add((o.channel_id, o.external_order_id))
                self.assertIsNotNone(o.customer_id)
                self.assertIsNone(o.created_by)

                self.assertIsNotNone(o.payment)
                self.assertIsNotNone(o.payment.paid_at)
                diff_minutes = (o.payment.paid_at - o.created_at).total_seconds() / 60.0
                self.assertLessEqual(diff_minutes, 15.0)

            # Ràng buộc tiền tệ
            self.assertGreaterEqual(o.subtotal, 0)
            self.assertGreaterEqual(o.discount, 0)
            self.assertGreaterEqual(o.total, 0)
            self.assertLessEqual(o.discount, o.subtotal)
            self.assertEqual(o.total, o.subtotal - o.discount)

            # Ràng buộc số lượng dòng hàng
            self.assertGreaterEqual(len(o.items), config.ORDER_ITEMS_RANGE[0])
            self.assertLessEqual(len(o.items), config.ORDER_ITEMS_RANGE[1])

            calc_subtotal = 0
            for it in o.items:
                self.assertGreater(it.quantity, 0)
                self.assertGreaterEqual(it.unit_price, 0)
                self.assertGreaterEqual(it.discount, 0)
                self.assertGreaterEqual(it.line_total, 0)
                self.assertEqual(
                    it.line_total,
                    it.quantity * it.unit_price - it.discount,
                )
                calc_subtotal += it.quantity * it.unit_price

            self.assertEqual(o.subtotal, calc_subtotal)

            self.assertIsNotNone(o.payment)
            self.assertEqual(o.payment.amount, o.total)
            self.assertEqual(o.payment.status, "PAID")

    def test_promotion_application_integrity(self):
        """BƯỚC 0: Kiểm tra chi tiết quy tắc áp dụng khuyến mại (tối đa 1 KM/cấp, floor, used_count)."""
        promo_map = {p.id: p for p in self.promotions}
        item_promo_usage: dict[int, int] = {}
        order_promo_usage: dict[int, int] = {}

        for o in self.orders:
            # 1. Tối đa 1 KM cấp dòng trên mỗi order_item
            for it in o.items:
                if it.promotion_id:
                    self.assertIn(it.promotion_id, promo_map)
                    p = promo_map[it.promotion_id]
                    self.assertEqual(p.level, "ITEM")
                    self.assertIn(it.product_id, p.product_ids)
                    self.assertIn(o.branch_id, p.branch_ids)
                    self.assertIn(o.channel_code, p.channel_codes)
                    item_promo_usage[p.id] = item_promo_usage.get(p.id, 0) + 1

            # 2. Tối đa 1 KM cấp đơn trên sales_order
            if o.promotion_id:
                self.assertIn(o.promotion_id, promo_map)
                p = promo_map[o.promotion_id]
                self.assertEqual(p.level, "ORDER")
                self.assertIn(o.branch_id, p.branch_ids)
                self.assertIn(o.channel_code, p.channel_codes)
                order_promo_usage[p.id] = order_promo_usage.get(p.id, 0) + 1

                subtotal_after_item_disc = sum(it.line_total for it in o.items)
                if p.min_order:
                    self.assertGreaterEqual(subtotal_after_item_disc, p.min_order)

                order_disc = o.discount - sum(it.discount for it in o.items)
                if p.type == "PERCENT":
                    expected_disc = math.floor(subtotal_after_item_disc * p.value / 100)
                    if p.max_discount:
                        expected_disc = min(expected_disc, p.max_discount)
                    self.assertEqual(order_disc, expected_disc)

        # 3. used_count khớp đúng số lần dùng
        for p in self.promotions:
            actual_used = item_promo_usage.get(p.id, 0) if p.level == "ITEM" else order_promo_usage.get(p.id, 0)
            self.assertEqual(p.used_count, actual_used, f"used_count của KM {p.code} ({p.used_count}) không khớp thực tế ({actual_used})")

    def test_pareto_by_quantity_sold(self):
        """Kiểm tra quy luật Pareto: 200 SKU top chiếm xấp xỉ 80% tổng sản lượng bán (79%–81%)."""
        stats = generate_data.compute_sales_statistics(self.orders, self.products, self.top_sku_ids)
        share = stats["pareto_share"]
        self.assertGreaterEqual(share, 0.79, f"Tỷ lệ Pareto thực tế {share:.4f} phải >= 79%")
        self.assertLessEqual(share, 0.81, f"Tỷ lệ Pareto thực tế {share:.4f} phải <= 81%")

    def test_branch_category_demand_leaning(self):
        """Kiểm tra độ nghiêng ngành hàng đặc thù theo chi nhánh (BRANCH_CATEGORY_DEMAND_WEIGHTS).

        - GM-001 (Q1, công sở): nghiêng READY_TO_EAT_BEVERAGE.
        - GM-002 (Bình Thạnh, hộ gia đình): nghiêng FRESH_FOOD.
        - GM-003 (Tân Bình, phụ trợ): nghiêng DRY_FOOD_FMCG.
        - Quota 10.000 đơn, tỷ lệ kênh 70/20/10, chi nhánh 45/35/20 được giữ nguyên.
        """
        branch_group_qty = defaultdict(lambda: defaultdict(int))
        prod_map = {p.id: p for p in self.products}
        for o in self.orders:
            for it in o.items:
                p = prod_map[it.product_id]
                branch_group_qty[o.branch_code][p.group_code] += it.quantity

        # 1. GM-001 nghiêng READY_TO_EAT_BEVERAGE
        gm001 = branch_group_qty["GM-001"]
        self.assertGreater(gm001["READY_TO_EAT_BEVERAGE"], gm001["FRESH_FOOD"])
        self.assertGreater(gm001["READY_TO_EAT_BEVERAGE"], gm001["DRY_FOOD_FMCG"])

        # 2. GM-002 nghiêng FRESH_FOOD
        gm002 = branch_group_qty["GM-002"]
        self.assertGreater(gm002["FRESH_FOOD"], gm002["READY_TO_EAT_BEVERAGE"])
        self.assertGreater(gm002["FRESH_FOOD"], gm002["DRY_FOOD_FMCG"])

        # 3. GM-003 nghiêng DRY_FOOD_FMCG
        gm003 = branch_group_qty["GM-003"]
        self.assertGreater(gm003["DRY_FOOD_FMCG"], gm003["READY_TO_EAT_BEVERAGE"])
        self.assertGreater(gm003["DRY_FOOD_FMCG"], gm003["FRESH_FOOD"])

        # 4. Quotas
        self.assertEqual(len(self.orders), 10_000)
        ch_counts = defaultdict(int)
        b_counts = defaultdict(int)
        for o in self.orders:
            ch_counts[o.channel_code] += 1
            b_counts[o.branch_code] += 1
        self.assertEqual(ch_counts["POS"], 7_000)
        self.assertEqual(ch_counts["WEB"], 2_000)
        self.assertEqual(ch_counts["MARKET"], 1_000)
        self.assertEqual(b_counts["GM-001"], 4_500)
        self.assertEqual(b_counts["GM-002"], 3_500)
        self.assertEqual(b_counts["GM-003"], 2_000)

    def test_promotion_demand_lift(self):
        """Kiểm tra mức tăng sản lượng (demand lift) của các SKU khuyến mại trong đợt so với ngoài đợt.

        So sánh sản lượng bán trung bình ngày của các SKU KM trong thời gian chiến dịch
        với các ngày ngoài chiến dịch (cùng chi nhánh, cùng nhóm thứ), loại trừ dịp Tết Bính Ngọ.
        Mức tăng sản lượng tổng hợp trên tất cả chiến dịch ITEM phải đạt trong khoảng 30%–80%.
        """
        daily_sales = defaultdict(int)
        for o in self.orders:
            d = o.created_at.date()
            for it in o.items:
                daily_sales[(o.branch_code, it.product_id, d)] += it.quantity

        item_promos = [p for p in self.promotions if p.level == "ITEM" and p.id != 11]
        all_dates = sorted({o.created_at.date() for o in self.orders})
        tet_start_d = config.TET_PRE_START
        tet_end_d = config.TET_HOLIDAY_END

        total_in_qty = 0
        total_in_expected_out = 0

        for p in item_promos:
            p_start = p.start_at.date()
            p_end = p.end_at.date()
            p_skus = set(p.product_ids)

            in_days = [p_start + timedelta(days=i) for i in range((p_end - p_start).days + 1)]
            out_days = [
                d for d in all_dates
                if (d < p_start or d > p_end) and not (tet_start_d <= d <= tet_end_d)
            ]

            out_dow_sum = defaultdict(lambda: defaultdict(float))
            out_dow_days = defaultdict(lambda: defaultdict(int))
            for d in out_days:
                dow = d.weekday()
                for b_code in ["GM-001", "GM-002", "GM-003"]:
                    for pid in p_skus:
                        out_dow_sum[(b_code, dow)][pid] += daily_sales[(b_code, pid, d)]
                    out_dow_days[(b_code, dow)]["cnt"] += 1

            for d in in_days:
                if tet_start_d <= d <= tet_end_d:
                    continue
                dow = d.weekday()
                for b_code in ["GM-001", "GM-002", "GM-003"]:
                    days_cnt = out_dow_days[(b_code, dow)]["cnt"]
                    if days_cnt > 0:
                        for pid in p_skus:
                            actual_q = daily_sales[(b_code, pid, d)]
                            expected_q = out_dow_sum[(b_code, dow)][pid] / days_cnt
                            total_in_qty += actual_q
                            total_in_expected_out += expected_q

        self.assertGreater(total_in_expected_out, 0)
        agg_lift = (total_in_qty / total_in_expected_out) - 1.0
        self.assertGreaterEqual(agg_lift, 0.30, f"Lift tổng hợp {agg_lift*100:.2f}% thấp hơn 30%")
        self.assertLessEqual(agg_lift, 0.80, f"Lift tổng hợp {agg_lift*100:.2f}% cao hơn 80%")

    def test_time_boundaries_and_business_hours(self):
        """Kiểm tra toàn bộ mốc thời gian: không vượt quá 31/10/2026, giờ mở cửa 07:00 - 21:59."""
        branch_cashiers = {"GM-001": 7, "GM-002": 8, "GM-003": 9}
        for o in self.orders:
            self.assertGreaterEqual(o.created_at, config.START_DATE)
            self.assertLessEqual(o.created_at, config.MAX_EVENT_DATETIME)
            self.assertGreaterEqual(o.completed_at, o.created_at)

            # Giờ mở cửa: từ 07:00 đến 21:59 (hour < 22)
            self.assertGreaterEqual(o.created_at.hour, 7)
            self.assertLess(o.created_at.hour, 22)

            if o.channel_code == "POS":
                self.assertEqual(o.created_by, branch_cashiers[o.branch_code])


class TestGeneratorStage3(unittest.TestCase):
    """Kiểm tra tính đúng đắn của Chặng 3 (Phiếu trả hàng, Đơn online thất bại, Audit Log)."""

    @classmethod
    def setUpClass(cls):
        cls.products, cls.top_sku_ids = generate_data.generate_products()
        cls.customers = generate_data.generate_customers(count=1500)
        cls.promotions = generate_data.generate_promotions(cls.products)
        cls.stockout_schedule = generate_data.get_stockout_schedule(cls.products, cls.top_sku_ids)
        cls.processed_orders = generate_data.generate_orders_and_items(
            products=cls.products,
            top_sku_ids=cls.top_sku_ids,
            customers=cls.customers,
            promotions=cls.promotions,
            stockout_schedule=cls.stockout_schedule,
        )
        cls.returns, cls.return_items = generate_data.generate_returns(cls.processed_orders, cls.products)
        cls.failed_orders, cls.audit_logs = generate_data.generate_failed_online_orders(
            products=cls.products,
            customers=cls.customers,
            order_id_start=len(cls.processed_orders) + 1,
            item_id_start=sum(len(o.items) for o in cls.processed_orders) + 1,
            payment_id_start=len(cls.processed_orders) + 1,
        )

    def test_processed_orders_status_split(self):
        """TC-AC01-03: 10.000 đơn đã xử lý gồm 9.925 COMPLETED và 75 RETURNED."""
        self.assertEqual(len(self.processed_orders), 10_000)
        completed_cnt = sum(1 for o in self.processed_orders if o.status == "COMPLETED")
        returned_cnt = sum(1 for o in self.processed_orders if o.status == "RETURNED")

        self.assertEqual(completed_cnt, 9_925)
        self.assertEqual(returned_cnt, 75)
        self.assertEqual(completed_cnt + returned_cnt, 10_000)

    def test_returns_integrity_and_ba_refund_formula(self):
        """Kiểm tra 150 phiếu trả hàng, SoD và công thức hoàn tiền BA §5.2."""
        self.assertEqual(len(self.returns), 150)

        for r in self.returns:
            self.assertEqual(r.status, "APPROVED")
            self.assertNotEqual(r.created_by, r.approved_by, "BR-13 SoD: người lập không tự duyệt phiếu")
            self.assertGreater(r.refund_amount, 0)
            self.assertLessEqual(r.created_at, config.MAX_EVENT_DATETIME)

            # Tìm đơn hàng gốc
            ord = next(o for o in self.processed_orders if o.id == r.order_id)
            diff_days = (r.created_at - ord.completed_at).total_seconds() / (24 * 3600)
            self.assertLessEqual(diff_days, 7.0, "Thời hạn đổi trả không quá 7 ngày")

            if ord.status == "RETURNED":
                self.assertEqual(r.refund_amount, ord.total, "Đơn trả toàn bộ thì hoàn 100% số tiền đơn")
            else:
                self.assertLess(r.refund_amount, ord.total, "Đơn trả một phần thì hoàn ít hơn tổng tiền đơn")

    def test_ba_5_2_exact_numerical_example(self):
        """Kiểm chứng số học chính xác ví dụ BA §5.2: mua 3 gói rau (54k, KM đơn 22.3k trên 223k) trả 1 gói = 16.200 đ."""
        qty_buy = 3
        qty_return = 1
        line_total = 54_000
        order_discount = 22_300
        subtotal_after_item_disc = 223_000

        allocated_order_disc = (order_discount * line_total) / subtotal_after_item_disc
        line_net = line_total - allocated_order_disc
        refund = math.floor((qty_return / qty_buy) * line_net)

        self.assertEqual(refund, 16_200, "Ví dụ chuẩn BA §5.2 phải ra chính xác 16.200 đ")

    def test_fresh_food_return_condition_damaged(self):
        """Kiểm tra sản phẩm tươi sống (FRESH_FOOD) khi đổi trả bắt buộc condition = 'DAMAGED'."""
        prod_map = {p.id: p for p in self.products}
        order_item_map = {}
        for o in self.processed_orders:
            for it in o.items:
                order_item_map[it.id] = it

        for ri in self.return_items:
            it = order_item_map[ri.order_item_id]
            prod = prod_map[it.product_id]
            if prod.group_code == "FRESH_FOOD":
                self.assertEqual(ri.condition, "DAMAGED", f"Hàng tươi sống {prod.sku} khi trả lại phải có condition là DAMAGED")

    def test_failed_online_orders_specifications(self):
        """Kiểm tra 200 đơn online lỗi: 80 CANCELLED, 80 EXPIRED, 40 REJECTED."""
        self.assertEqual(len(self.failed_orders), 200)

        cancelled = [o for o in self.failed_orders if o.status == "CANCELLED"]
        expired = [o for o in self.failed_orders if o.status == "EXPIRED"]
        rejected = [o for o in self.failed_orders if o.status == "REJECTED"]

        self.assertEqual(len(cancelled), 80)
        self.assertEqual(len(expired), 80)
        self.assertEqual(len(rejected), 40)

        # 40 CANCELLED chưa thanh toán (payment None), 40 đã hoàn (payment REFUNDED)
        cancelled_unpaid = [o for o in cancelled if o.payment is None]
        cancelled_refunded = [o for o in cancelled if o.payment is not None and o.payment.status == "REFUNDED"]
        self.assertEqual(len(cancelled_unpaid), 40)
        self.assertEqual(len(cancelled_refunded), 40)

        # 80 EXPIRED giữ chỗ 15 phút, payment PENDING
        for o in expired:
            self.assertIsNotNone(o.reserved_until)
            diff = (o.reserved_until - o.created_at).total_seconds() / 60.0
            self.assertEqual(diff, 15.0)
            self.assertIsNotNone(o.payment)
            self.assertEqual(o.payment.status, "PENDING")
            self.assertIsNone(o.payment.paid_at)

        # 40 REJECTED gán branch mặc định (WEB -> GM-001, MARKET -> GM-002), payment None
        for o in rejected:
            self.assertIsNone(o.payment)
            self.assertIsNone(o.reserved_until)
            if o.channel_code == "WEB":
                self.assertEqual(o.branch_id, 1)
            elif o.channel_code == "MARKET":
                self.assertEqual(o.branch_id, 2)

        # Audit logs: 80 CANCELLED + 40 REJECTED = 120 logs
        self.assertEqual(len(self.audit_logs), 120)
        actions = [l.action for l in self.audit_logs]
        self.assertEqual(actions.count("CANCEL_ORDER"), 80)
        self.assertEqual(actions.count("REJECT_ORDER"), 40)

    def test_return_item_quantities_and_amounts(self):
        """Kiểm tra chi tiết số lượng và tiền hoàn của 150 phiếu trả hàng (75 full, 75 partial).

        Yêu cầu:
        - Đơn RETURNED (trả toàn bộ): SL trả = SL mua của TỪNG dòng, tiền hoàn = total đơn.
        - Đơn trả một phần: SL trả <= SL mua của từng dòng, tiền hoàn < total đơn.
        - Tổng số dòng return_item = 510 (>= 300, gồm 400 dòng full + 110 dòng partial).
        """
        order_map = {o.id: o for o in self.processed_orders}
        order_item_map = {it.id: it for o in self.processed_orders for it in o.items}

        self.assertEqual(len(self.returns), 150)
        self.assertEqual(len(self.return_items), 510)
        self.assertGreaterEqual(len(self.return_items), 300)

        full_returns = [r for r in self.returns if order_map[r.order_id].status == "RETURNED"]
        partial_returns = [r for r in self.returns if order_map[r.order_id].status == "COMPLETED"]

        self.assertEqual(len(full_returns), 75)
        self.assertEqual(len(partial_returns), 75)

        # 1. Đơn trả toàn bộ:
        for r in full_returns:
            ord = order_map[r.order_id]
            self.assertEqual(r.refund_amount, ord.total, "Đơn RETURNED phải hoàn đúng 100% total của đơn")
            self.assertEqual(len(r.items), len(ord.items), "Đơn RETURNED phải trả toàn bộ số dòng của đơn")
            for ri in r.items:
                oit = order_item_map[ri.order_item_id]
                self.assertEqual(
                    ri.quantity,
                    oit.quantity,
                    f"Đơn RETURNED: SL trả ({ri.quantity}) phải bằng đúng SL mua ({oit.quantity}) của dòng {oit.id}",
                )

        # 2. Đơn trả một phần:
        for r in partial_returns:
            ord = order_map[r.order_id]
            self.assertLess(r.refund_amount, ord.total, "Đơn trả một phần phải hoàn ít hơn total của đơn")
            self.assertLessEqual(len(r.items), len(ord.items))
            for ri in r.items:
                oit = order_item_map[ri.order_item_id]
                self.assertGreaterEqual(ri.quantity, 1)
                self.assertLessEqual(
                    ri.quantity,
                    oit.quantity,
                    f"Đơn trả một phần: SL trả ({ri.quantity}) phải <= SL mua ({oit.quantity})",
                )


class TestGeneratorStage4(unittest.TestCase):
    """Kiểm tra xuất file SQL, file JSON và tính tái lập (Reproducibility)."""

    def test_sql_and_json_export_exist_and_valid(self):
        """Chặng 4: Xuất file seed_data.sql và stockout_schedule.json thành công."""
        data_dir = PROJECT_ROOT / "data"
        sql_path = data_dir / "seed_data.sql"
        json_path = data_dir / "stockout_schedule.json"

        # Nếu chưa tồn tại, chạy export
        if not sql_path.exists() or not json_path.exists():
            generate_data.main()

        self.assertTrue(sql_path.exists(), "File seed_data.sql phải tồn tại")
        self.assertTrue(json_path.exists(), "File stockout_schedule.json phải tồn tại")
        self.assertGreater(sql_path.stat().st_size, 1_000_000, "File SQL phải > 1 MB")

        # Kiểm tra nội dung SQL
        with open(sql_path, "r", encoding="utf-8") as f:
            head = f.read(2048)
            self.assertIn("[CẢNH BÁO: DỮ LIỆU SẼ BỊ PHÁ HỦY", head)
            self.assertIn("UPDATE sales_channel SET default_branch_id = NULL;", head)
            self.assertIn("TRUNCATE TABLE", head)
            self.assertIn("DELETE FROM branch;", head)

        # Kiểm tra JSON
        with open(json_path, "r", encoding="utf-8") as f:
            records = json.load(f)
            self.assertGreater(len(records), 4000)
            self.assertIn("branch_code", records[0])
            self.assertIn("mode", records[0])

    def test_reproducibility_deterministic_seed(self):
        """TC-AC01-04: Hai lần sinh dữ liệu độc lập với cùng seed cho kết quả giống nhau 100%."""
        p1, top1 = generate_data.generate_products()
        p2, top2 = generate_data.generate_products()
        self.assertEqual([p.sku for p in p1], [p.sku for p in p2])
        self.assertEqual(top1, top2)

        s1 = generate_data.get_stockout_schedule(p1, top1)
        s2 = generate_data.get_stockout_schedule(p2, top2)
        self.assertEqual(s1, s2)

    def test_golden_sha256_checksums(self):
        """Khóa mốc SHA-256 vàng in-memory (newline='\\n', utf-8) để bảo vệ refactor không đổi dữ liệu.

        Yêu cầu:
        - Tự sinh trực tiếp trong bộ nhớ (in-memory) bằng io.StringIO, không phụ thuộc vào file trên disk.
        - Đảm bảo newline='\\n' và utf-8 để SHA-256 hoàn toàn đồng nhất giữa Windows và Linux CI.
        """
        branches = generate_data.generate_branches()
        categories = generate_data.generate_categories()
        products, top_sku_ids = generate_data.generate_products()
        users = generate_data.generate_demo_users()
        customers = generate_data.generate_customers(1500)
        promotions = generate_data.generate_promotions(products)
        stockout_schedule = generate_data.get_stockout_schedule(products, top_sku_ids)
        orders = generate_data.generate_orders_and_items(products, top_sku_ids, customers, promotions, stockout_schedule)
        returns, return_items = generate_data.generate_returns(orders, products)
        failed_orders, audit_logs = generate_data.generate_failed_online_orders(
            products=products, customers=customers,
            order_id_start=len(orders) + 1,
            item_id_start=sum(len(o.items) for o in orders) + 1,
            payment_id_start=len(orders) + 1
        )
        all_orders = orders + failed_orders

        # 1. In-memory JSON
        buf_json = io.StringIO()
        generate_data.export_stockout_schedule(stockout_schedule, products, stream=buf_json)
        json_bytes = buf_json.getvalue().encode("utf-8")
        actual_json_sha = hashlib.sha256(json_bytes).hexdigest()

        # 2. In-memory SQL
        buf_sql = io.StringIO()
        generate_data.export_to_sql(
            branches=branches, categories=categories, products=products,
            users=users, customers=customers, promotions=promotions,
            orders=all_orders, returns=returns, audit_logs=audit_logs,
            stream=buf_sql
        )
        sql_bytes = buf_sql.getvalue().encode("utf-8")
        actual_sql_sha = hashlib.sha256(sql_bytes).hexdigest()

        GOLDEN_SQL_SHA256 = "7b9784eb29d0ef5cee728ebcdb0b801d3db75f9753b8b6be46733657e597695b"
        GOLDEN_JSON_SHA256 = "d0226a3d258fa87649db164118a5d600052de8f422e94e5beecb8a3e05d1dc91"

        self.assertEqual(actual_sql_sha, GOLDEN_SQL_SHA256, "Mã SHA-256 in-memory của SQL không được thay đổi")
        self.assertEqual(actual_json_sha, GOLDEN_JSON_SHA256, "Mã SHA-256 in-memory của JSON không được thay đổi")


if __name__ == "__main__":
    unittest.main()
