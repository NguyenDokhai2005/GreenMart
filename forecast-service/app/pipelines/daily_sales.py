"""Module pipeline tổng hợp và nạp bảng daily_sales cho forecast-service (Task #109).

Bao gồm:
1. Hàm thuần `build_daily_sales(...)`: Xử lý toàn bộ logic tính toán bằng pandas vectorized
   trong bộ nhớ (tốc độ cao, không truy cập DB bên trong).
2. Lớp `StockoutScheduleProvider`: Provider đọc lịch stockout từ JSON (dễ dàng thay thế sau này).
3. Lớp `DailySalesPipeline`: Đọc dữ liệu từ PostgreSQL qua SELECT tham số hóa,
   nạp vào CSDL bằng bảng tạm + COPY + INSERT ... ON CONFLICT DO UPDATE (Idempotent).
4. CLI: Giao diện dòng lệnh hỗ trợ --start-date, --end-date, --dry-run.
"""

from __future__ import annotations

import argparse
from datetime import date, datetime, time, timedelta
import io
import json
import math
import os
from pathlib import Path
import sys
import time as time_mod
from typing import Any, Dict, List, Optional, Set, Tuple
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd

# Chèn thư mục gốc forecast-service vào sys.path để import an toàn (theo chỉ thị A1)
_MODULE_DIR = Path(__file__).resolve().parent
_SERVICE_DIR = _MODULE_DIR.parents[1]
if str(_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(_SERVICE_DIR))

try:
    from scripts import config
except ImportError as err:
    raise ImportError(f"Không thể import scripts.config từ {_SERVICE_DIR}: {err}") from err

from app.db.connection import get_db_connection

# Múi giờ chuẩn hệ thống
TIMEZONE_VN = ZoneInfo("Asia/Ho_Chi_Minh")


# ==============================================================================
# 1. HÀM THUẦN BIẾN ĐỔI DỮ LIỆU (PURE FUNCTION - VECTORIZED PANDAS)
# ==============================================================================


def _ensure_vn_timezone(series: pd.Series) -> pd.Series:
    """Chuyển đổi Series timestamp sang múi giờ Asia/Ho_Chi_Minh an toàn bằng pandas (chỉ thị A2)."""
    ts = pd.to_datetime(series)
    if ts.dt.tz is None:
        ts = ts.dt.tz_localize("UTC").dt.tz_convert("Asia/Ho_Chi_Minh")
    else:
        ts = ts.dt.tz_convert("Asia/Ho_Chi_Minh")
    return ts


def build_daily_sales(
    df_orders: pd.DataFrame,
    df_order_items: pd.DataFrame,
    df_returns: pd.DataFrame,
    df_return_items: pd.DataFrame,
    df_promotions: pd.DataFrame,
    df_promo_branches: pd.DataFrame,
    df_promo_products: pd.DataFrame,
    df_branches: pd.DataFrame,
    df_products: pd.DataFrame,
    stockout_schedule_data: Any,
    start_date: date,
    end_date: date,
) -> pd.DataFrame:
    """Hàm thuần tổng hợp dữ liệu bảng daily_sales (Vectorized - Không truy cập DB).

    Xử lý siêu tốc bằng các phép toán vectorized của pandas, đảm bảo không sử dụng
    vòng lặp chậm trên 1,095 triệu dòng.
    """
    # -------------------------------------------------------------------------
    # 1. TẠO KHUNG CARTESIAN PRODUCT ĐẦY ĐỦ (NGÀY 0)
    # -------------------------------------------------------------------------
    active_branches = df_branches
    if "is_active" in active_branches.columns:
        active_branches = active_branches[active_branches["is_active"] == True]
    branch_ids = np.sort(active_branches["id"].astype(np.int64).unique())

    active_products = df_products
    if "is_active" in active_products.columns:
        active_products = active_products[active_products["is_active"] == True]
    product_ids = np.sort(active_products["id"].astype(np.int64).unique())

    delta_days = (end_date - start_date).days
    date_list = [start_date + timedelta(days=i) for i in range(delta_days + 1)]

    # MultiIndex Cartesian Product
    cartesian_idx = pd.MultiIndex.from_product(
        [date_list, branch_ids, product_ids],
        names=["sale_date", "branch_id", "product_id"],
    )
    df_base = pd.DataFrame(index=cartesian_idx).reset_index()

    # -------------------------------------------------------------------------
    # 2. TÍNH DOANH THU THỰC CHO MỌI ORDER_ITEM (BA §5.2 / BR-10)
    # -------------------------------------------------------------------------
    order_item_net_rev_map: Dict[int, int] = {}
    if not df_order_items.empty:
        if not df_orders.empty:
            df_oi_merged = df_order_items.merge(
                df_orders[["id", "discount"]].rename(columns={"id": "order_id", "discount": "order_discount"}),
                on="order_id",
                how="left",
            )
            df_oi_merged["order_discount"] = df_oi_merged["order_discount"].fillna(0)
            order_line_sum = df_oi_merged.groupby("order_id")["line_total"].transform("sum")
            alloc_disc = np.where(
                (order_line_sum > 0) & (df_oi_merged["order_discount"] > 0),
                np.floor(df_oi_merged["order_discount"] * df_oi_merged["line_total"] / order_line_sum).astype(np.int64),
                0,
            )
            df_oi_merged["line_net_rev"] = (df_oi_merged["line_total"] - alloc_disc).astype(np.int64)
            order_item_net_rev_map = dict(zip(df_oi_merged["id"].astype(np.int64), df_oi_merged["line_net_rev"]))
        else:
            order_item_net_rev_map = dict(zip(df_order_items["id"].astype(np.int64), df_order_items["line_total"].astype(np.int64)))

    # -------------------------------------------------------------------------
    # 3. VECTORIZED XỬ LÝ DOANH SỐ BÁN HÀNG (QTY_SOLD, REV_SOLD)
    # -------------------------------------------------------------------------
    valid_orders = df_orders[
        df_orders["status"].isin(["COMPLETED", "RETURNED"]) & df_orders["completed_at"].notna()
    ].copy()

    df_sales_agg = pd.DataFrame(columns=["sale_date", "branch_id", "product_id", "qty_sold", "rev_sold"])

    if not valid_orders.empty:
        valid_orders["sale_date"] = _ensure_vn_timezone(valid_orders["completed_at"]).dt.date
        valid_orders = valid_orders[
            (valid_orders["sale_date"] >= start_date) & (valid_orders["sale_date"] <= end_date)
        ]

        if not valid_orders.empty and not df_order_items.empty:
            df_valid_items = df_order_items.merge(
                valid_orders[["id", "branch_id", "sale_date"]].rename(columns={"id": "order_id"}),
                on="order_id",
                how="inner",
            )
            if not df_valid_items.empty:
                df_valid_items["line_net_rev"] = df_valid_items["id"].map(order_item_net_rev_map).fillna(df_valid_items["line_total"]).astype(np.int64)
                df_sales_agg = df_valid_items.groupby(
                    ["sale_date", "branch_id", "product_id"], as_index=False
                ).agg(
                    qty_sold=("quantity", "sum"),
                    rev_sold=("line_net_rev", "sum"),
                )

    # -------------------------------------------------------------------------
    # 4. VECTORIZED XỬ LÝ TRẢ HÀNG (QTY_RETURNED, REFUND)
    # -------------------------------------------------------------------------
    valid_returns = df_returns[
        (df_returns["status"] == "APPROVED") & df_returns["approved_at"].notna()
    ].copy()

    df_returns_agg = pd.DataFrame(columns=["sale_date", "branch_id", "product_id", "qty_returned", "refund"])

    if not valid_returns.empty:
        valid_returns["return_date"] = _ensure_vn_timezone(valid_returns["approved_at"]).dt.date
        valid_returns = valid_returns[
            (valid_returns["return_date"] >= start_date) & (valid_returns["return_date"] <= end_date)
        ]

        if not valid_returns.empty and not df_return_items.empty:
            df_valid_ret_items = df_return_items.merge(
                valid_returns[["id", "branch_id", "return_date"]].rename(columns={"id": "return_id"}),
                on="return_id",
                how="inner",
            )
            if not df_valid_ret_items.empty:
                df_merged_ret = df_valid_ret_items.merge(
                    df_order_items[["id", "product_id", "quantity", "line_total"]].rename(
                        columns={"id": "order_item_id", "quantity": "orig_quantity", "line_total": "orig_line_total"}
                    ),
                    on="order_item_id",
                    how="inner",
                )
                if not df_merged_ret.empty:
                    # Tính tiền hoàn trả cho từng dòng
                    df_merged_ret["orig_net_rev"] = df_merged_ret["order_item_id"].map(order_item_net_rev_map).fillna(df_merged_ret["orig_line_total"])
                    df_merged_ret["refund_line"] = np.where(
                        df_merged_ret["orig_quantity"] > 0,
                        np.floor((df_merged_ret["quantity"] / df_merged_ret["orig_quantity"]) * df_merged_ret["orig_net_rev"]).astype(np.int64),
                        0,
                    )
                    df_returns_agg = df_merged_ret.groupby(
                        ["return_date", "branch_id", "product_id"], as_index=False
                    ).agg(
                        qty_returned=("quantity", "sum"),
                        refund=("refund_line", "sum"),
                    ).rename(columns={"return_date": "sale_date"})

    # -------------------------------------------------------------------------
    # 5. VECTORIZED XỬ LÝ KHUYẾN MẠI (PROMO_FLAG, PROMO_DISCOUNT_PCT)
    # -------------------------------------------------------------------------
    df_promo_agg = pd.DataFrame(columns=["sale_date", "branch_id", "product_id", "promo_flag", "promo_discount_pct"])

    if not df_promotions.empty and not df_promo_branches.empty and not df_promo_products.empty:
        valid_promos = df_promotions[
            (df_promotions["level"] == "ITEM") & (df_promotions["status"] != "DRAFT")
        ].copy()

        if not valid_promos.empty:
            valid_promos["start_dt_vn"] = _ensure_vn_timezone(valid_promos["start_at"])
            valid_promos["end_dt_vn"] = _ensure_vn_timezone(valid_promos["end_at"])

            product_price_map = active_products.set_index("id")["sale_price"].to_dict()

            promo_expanded = valid_promos.merge(df_promo_branches, left_on="id", right_on="promotion_id")
            promo_expanded = promo_expanded.merge(df_promo_products, left_on="id", right_on="promotion_id")

            # Lọc các khuyến mại có thời gian giao thoa với [start_date, end_date]
            relevant_promos = promo_expanded[
                (promo_expanded["start_dt_vn"].dt.date <= end_date)
                & (promo_expanded["end_dt_vn"].dt.date >= start_date)
            ]

            promo_records = []
            for _, pr in relevant_promos.iterrows():
                p_start = pr["start_dt_vn"].date()
                p_end = pr["end_dt_vn"].date()
                b_id = int(pr["branch_id"])
                p_id = int(pr["product_id"])
                p_type = str(pr["type"])
                p_val = float(pr["value"])

                if p_type == "PERCENT":
                    discount_pct = p_val
                else:  # AMOUNT
                    sale_price = product_price_map.get(p_id, 0)
                    if sale_price > 0:
                        discount_pct = min(100.0, round((p_val / sale_price) * 100.0, 2))
                    else:
                        discount_pct = 0.0

                cur_start = max(start_date, p_start)
                cur_end = min(end_date, p_end)
                cur_d = cur_start
                while cur_d <= cur_end:
                    promo_records.append({
                        "sale_date": cur_d,
                        "branch_id": b_id,
                        "product_id": p_id,
                        "discount_pct": discount_pct,
                    })
                    cur_d += timedelta(days=1)

            if promo_records:
                df_promo_raw = pd.DataFrame(promo_records)
                # Nhiều KM cùng ngày: lấy MAX phần trăm giảm giá (S4c)
                df_promo_agg = df_promo_raw.groupby(
                    ["sale_date", "branch_id", "product_id"], as_index=False
                ).agg(promo_discount_pct=("discount_pct", "max"))
                df_promo_agg["promo_flag"] = True

    # -------------------------------------------------------------------------
    # 6. VECTORIZED XỬ LÝ STOCKOUT (HẾT HÀNG)
    # -------------------------------------------------------------------------
    df_stockout_agg = pd.DataFrame(columns=["sale_date", "branch_id", "product_id", "stockout"])
    stockout_records = []
    sku_to_id_map = (
        active_products.set_index("sku")["id"].to_dict()
        if "sku" in active_products.columns
        else {}
    )

    if isinstance(stockout_schedule_data, set):
        for d_val, b_id, p_id in stockout_schedule_data:
            if start_date <= d_val <= end_date:
                stockout_records.append({"sale_date": d_val, "branch_id": b_id, "product_id": p_id, "stockout": True})
    elif isinstance(stockout_schedule_data, list):
        for item in stockout_schedule_data:
            d_val = item.get("date")
            if d_val is None:
                continue
            if isinstance(d_val, str):
                d_val = date.fromisoformat(d_val)
            if start_date <= d_val <= end_date:
                p_id = item.get("product_id")
                if p_id is None and "sku" in item:
                    p_id = sku_to_id_map.get(item["sku"])
                if p_id is not None:
                    stockout_records.append({
                        "sale_date": d_val,
                        "branch_id": int(item["branch_id"]),
                        "product_id": int(p_id),
                        "stockout": True,
                    })

    if stockout_records:
        df_stockout_agg = pd.DataFrame(stockout_records).drop_duplicates(subset=["sale_date", "branch_id", "product_id"])

    # -------------------------------------------------------------------------
    # 7. GHÉP TOÀN BỘ VÀO KHUNG CƠ SỞ (VECTORIZED LEFT MERGE)
    # -------------------------------------------------------------------------
    # Merge Sales
    if not df_sales_agg.empty:
        df_base = df_base.merge(df_sales_agg, on=["sale_date", "branch_id", "product_id"], how="left")
    else:
        df_base["qty_sold"] = 0
        df_base["rev_sold"] = 0
    df_base["qty_sold"] = df_base["qty_sold"].fillna(0).astype(np.int32)
    df_base["rev_sold"] = df_base["rev_sold"].fillna(0).astype(np.int64)

    # Merge Returns
    if not df_returns_agg.empty:
        df_base = df_base.merge(df_returns_agg, on=["sale_date", "branch_id", "product_id"], how="left")
    else:
        df_base["qty_returned"] = 0
        df_base["refund"] = 0
    df_base["qty_returned"] = df_base["qty_returned"].fillna(0).astype(np.int32)
    df_base["refund"] = df_base["refund"].fillna(0).astype(np.int64)

    # Tính qty_net và revenue: GREATEST(0, rev_sold - refund)
    df_base["qty_net"] = (df_base["qty_sold"] - df_base["qty_returned"]).astype(np.int32)
    df_base["revenue"] = np.maximum(0, df_base["rev_sold"] - df_base["refund"]).astype(np.int64)

    # Merge Promotions
    if not df_promo_agg.empty:
        df_base = df_base.merge(df_promo_agg, on=["sale_date", "branch_id", "product_id"], how="left")
    else:
        df_base["promo_flag"] = False
        df_base["promo_discount_pct"] = 0.0
    df_base["promo_flag"] = df_base["promo_flag"].fillna(False).astype(bool)
    df_base["promo_discount_pct"] = df_base["promo_discount_pct"].fillna(0.0).round(2).astype(np.float64)

    # Merge Stockouts
    if not df_stockout_agg.empty:
        df_base = df_base.merge(df_stockout_agg, on=["sale_date", "branch_id", "product_id"], how="left")
    else:
        df_base["stockout"] = False
    df_base["stockout"] = df_base["stockout"].fillna(False).astype(bool)

    # Sắp xếp và chọn đúng thứ tự cột
    column_order = [
        "sale_date",
        "branch_id",
        "product_id",
        "qty_sold",
        "qty_returned",
        "qty_net",
        "revenue",
        "promo_flag",
        "promo_discount_pct",
        "stockout",
    ]
    df_result = df_base[column_order].sort_values(by=["sale_date", "branch_id", "product_id"]).reset_index(drop=True)

    return df_result


# ==============================================================================
# 2. PROVIDER LỊCH STOCKOUT (DỄ DÀNG THAY THẾ)
# ==============================================================================


class StockoutScheduleProvider:
    """Provider nguồn dữ liệu stockout từ file JSON demo/seed.
    
    Hỗ trợ đối số allow_missing để tránh fail thầm lặng (Fix điểm 1).
    """

    @staticmethod
    def load_schedule(
        json_path: Optional[Path] = None,
        allow_missing: bool = False,
    ) -> List[Dict[str, Any]]:
        """Đọc danh sách sự kiện stockout từ file JSON."""
        if json_path is None:
            json_path = _SERVICE_DIR / "data" / "stockout_schedule.json"
        if not json_path.exists():
            if not allow_missing:
                raise FileNotFoundError(
                    f"Không tìm thấy file nguồn lịch stockout tại: '{json_path}'. "
                    "Mô hình dự báo cần cờ stockout chính xác để tránh coi ngày hết hàng là không có nhu cầu. "
                    "Hãy sinh dữ liệu demo hoặc truyền cờ --allow-missing-stockout để tiếp tục."
                )
            import logging
            logging.getLogger(__name__).warning(
                f"[CẢNH BÁO] Không tìm thấy file lịch stockout tại '{json_path}'. "
                "Tiếp tục chạy với cờ stockout=False toàn bộ do bật --allow-missing-stockout."
            )
            return []
        with open(json_path, "r", encoding="utf-8") as f:
            return json.load(f)


class InventoryTransactionStockoutProvider:
    """Provider suy cờ stockout từ bảng inventory_transaction trong PostgreSQL.
    
    Một cặp (ngày, chi nhánh, sản phẩm) được coi là stockout nếu trong ngày đó
    có giao dịch kho mà tồn khả dụng (on_hand_after - reserved_after) <= 0.
    """

    @staticmethod
    def load_schedule_from_db(conn: Any, start_date: date, end_date: date) -> Set[Tuple[date, int, int]]:
        start_ts = datetime.combine(start_date, time.min, tzinfo=TIMEZONE_VN)
        end_ts = datetime.combine(end_date + timedelta(days=1), time.min, tzinfo=TIMEZONE_VN)
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT DISTINCT
                    (created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS sale_date,
                    branch_id,
                    product_id
                FROM inventory_transaction
                WHERE (on_hand_after - reserved_after) <= 0
                  AND created_at >= %s AND created_at < %s;
                """,
                (start_ts, end_ts),
            )
            return {(row[0], int(row[1]), int(row[2])) for row in cur.fetchall()}


# ==============================================================================
# 3. LỚP ĐIỀU PHỐI NẠP DB (DATABASE INGESTION PIPELINE)
# ==============================================================================


def _read_sql_df(sql: str, conn: Any, params: Optional[Any] = None) -> pd.DataFrame:
    """Đọc dữ liệu từ PostgreSQL qua cursor psycopg 3 và trả về pandas DataFrame an toàn."""
    with conn.cursor() as cur:
        cur.execute(sql, params)
        if cur.description is None:
            return pd.DataFrame()
        columns = [col[0] for col in cur.description]
        records = cur.fetchall()
        return pd.DataFrame(records, columns=columns)


class DailySalesPipeline:
    """Lớp quản lý trích xuất dữ liệu, tổng hợp và nạp bảng daily_sales (Idempotent)."""

    def __init__(
        self,
        stockout_provider: Any = StockoutScheduleProvider,
        allow_missing_stockout: bool = False,
        stockout_source: str = "json",
    ):
        self.stockout_provider = stockout_provider
        self.allow_missing_stockout = allow_missing_stockout
        self.stockout_source = stockout_source

    def fetch_raw_data_from_db(self, conn, start_date: date, end_date: date) -> Dict[str, pd.DataFrame]:
        """Trích xuất dữ liệu thô từ PostgreSQL bằng câu lệnh SELECT tham số hóa (chống SQL Injection).
        
        Đẩy điều kiện lọc thời gian theo giờ Việt Nam xuống thẳng database để tối ưu RAM và I/O.
        """
        start_ts = datetime.combine(start_date, time.min, tzinfo=TIMEZONE_VN)
        end_ts = datetime.combine(end_date + timedelta(days=1), time.min, tzinfo=TIMEZONE_VN)
        sql_params = {"start_ts": start_ts, "end_ts": end_ts}

        # Đọc chi nhánh và sản phẩm đang hoạt động
        df_branches = _read_sql_df("SELECT id, code, is_active FROM branch WHERE is_active = TRUE;", conn)
        df_products = _read_sql_df("SELECT id, sku, sale_price, is_active FROM product WHERE is_active = TRUE;", conn)

        # Đọc đơn hàng COMPLETED hoặc RETURNED trong khoảng ngày (hoặc được phiếu trả tham chiếu)
        df_orders = _read_sql_df(
            """
            SELECT id, branch_id, status, completed_at, subtotal, discount, total
            FROM sales_order
            WHERE (status IN ('COMPLETED', 'RETURNED') AND completed_at >= %(start_ts)s AND completed_at < %(end_ts)s)
               OR id IN (
                   SELECT rd.order_id FROM return_document rd
                   WHERE rd.status = 'APPROVED'
                     AND rd.approved_at >= %(start_ts)s AND rd.approved_at < %(end_ts)s
               );
            """,
            conn,
            sql_params,
        )

        # Đọc dòng đơn cho các đơn hàng liên quan
        df_order_items = _read_sql_df(
            """
            SELECT oi.id, oi.order_id, oi.product_id, oi.quantity, oi.unit_price, oi.discount, oi.line_total
            FROM order_item oi
            WHERE oi.order_id IN (
                SELECT id FROM sales_order
                WHERE status IN ('COMPLETED', 'RETURNED')
                  AND completed_at >= %(start_ts)s AND completed_at < %(end_ts)s
            )
            OR oi.id IN (
                SELECT ri.order_item_id FROM return_item ri
                JOIN return_document rd ON ri.return_id = rd.id
                WHERE rd.status = 'APPROVED'
                  AND rd.approved_at >= %(start_ts)s AND rd.approved_at < %(end_ts)s
            );
            """,
            conn,
            sql_params,
        )

        # Đọc phiếu trả APPROVED trong khoảng ngày
        df_returns = _read_sql_df(
            """
            SELECT id, order_id, branch_id, status, approved_at, refund_amount
            FROM return_document
            WHERE status = 'APPROVED'
              AND approved_at >= %(start_ts)s AND approved_at < %(end_ts)s;
            """,
            conn,
            sql_params,
        )

        # Đọc dòng trả cho các phiếu trả liên quan
        df_return_items = _read_sql_df(
            """
            SELECT ri.id, ri.return_id, ri.order_item_id, ri.quantity, ri.condition
            FROM return_item ri
            JOIN return_document rd ON ri.return_id = rd.id
            WHERE rd.status = 'APPROVED'
              AND rd.approved_at >= %(start_ts)s AND rd.approved_at < %(end_ts)s;
            """,
            conn,
            sql_params,
        )

        # Đọc khuyến mại cấp ITEM có thời gian giao thoa với khoảng ngày
        df_promotions = _read_sql_df(
            """
            SELECT id, code, type, level, value, start_at, end_at, status
            FROM promotion
            WHERE level = 'ITEM' AND status <> 'DRAFT'
              AND start_at < %(end_ts)s AND end_at >= %(start_ts)s;
            """,
            conn,
            sql_params,
        )

        # Đọc liên kết KM
        df_promo_branches = _read_sql_df(
            """
            SELECT pb.promotion_id, pb.branch_id
            FROM promotion_branch pb
            JOIN promotion p ON pb.promotion_id = p.id
            WHERE p.level = 'ITEM' AND p.status <> 'DRAFT'
              AND p.start_at < %(end_ts)s AND p.end_at >= %(start_ts)s;
            """,
            conn,
            sql_params,
        )
        df_promo_products = _read_sql_df(
            """
            SELECT pp.promotion_id, pp.product_id
            FROM promotion_product pp
            JOIN promotion p ON pp.promotion_id = p.id
            WHERE p.level = 'ITEM' AND p.status <> 'DRAFT'
              AND p.start_at < %(end_ts)s AND p.end_at >= %(start_ts)s;
            """,
            conn,
            sql_params,
        )

        return {
            "df_orders": df_orders,
            "df_order_items": df_order_items,
            "df_returns": df_returns,
            "df_return_items": df_return_items,
            "df_promotions": df_promotions,
            "df_promo_branches": df_promo_branches,
            "df_promo_products": df_promo_products,
            "df_branches": df_branches,
            "df_products": df_products,
        }

    def load_dataframe_to_db(self, conn, df_daily_sales: pd.DataFrame) -> int:
        """Nạp dữ liệu vào bảng daily_sales qua bảng tạm + COPY (psycopg 3) + ON CONFLICT DO UPDATE (Idempotent)."""
        if df_daily_sales.empty:
            return 0

        # Chuẩn bị buffer CSV trong bộ nhớ
        csv_buffer = io.StringIO()
        for _, row in df_daily_sales.iterrows():
            d_str = row["sale_date"].isoformat() if isinstance(row["sale_date"], date) else str(row["sale_date"])
            p_flag_str = "true" if row["promo_flag"] else "false"
            stk_str = "true" if row["stockout"] else "false"
            csv_buffer.write(
                f"{d_str},{row['branch_id']},{row['product_id']},{row['qty_sold']},{row['qty_returned']},"
                f"{row['qty_net']},{row['revenue']},{p_flag_str},{row['promo_discount_pct']:.2f},{stk_str}\n"
            )
        csv_buffer.seek(0)

        with conn.cursor() as cur:
            # 1. Tạo bảng tạm trong phiên (tự xóa khi commit transaction)
            cur.execute(
                """
                CREATE TEMP TABLE stage_daily_sales (
                    sale_date DATE NOT NULL,
                    branch_id BIGINT NOT NULL,
                    product_id BIGINT NOT NULL,
                    qty_sold INT NOT NULL,
                    qty_returned INT NOT NULL,
                    qty_net INT NOT NULL,
                    revenue BIGINT NOT NULL,
                    promo_flag BOOLEAN NOT NULL,
                    promo_discount_pct NUMERIC(5,2) NOT NULL,
                    stockout BOOLEAN NOT NULL
                ) ON COMMIT DROP;
                """
            )

            # 2. Nạp dữ liệu nhanh qua copy trong psycopg 3
            with cur.copy(
                """
                COPY stage_daily_sales (
                    sale_date, branch_id, product_id,
                    qty_sold, qty_returned, qty_net, revenue,
                    promo_flag, promo_discount_pct, stockout
                ) FROM STDIN WITH (FORMAT csv, HEADER false);
                """
            ) as copy:
                copy.write(csv_buffer.getvalue())

            # 3. Chèn hoặc cập nhật (Upsert) vào bảng daily_sales chính thức
            cur.execute(
                """
                INSERT INTO daily_sales (
                    sale_date, branch_id, product_id,
                    qty_sold, qty_returned, qty_net, revenue,
                    promo_flag, promo_discount_pct, stockout
                )
                SELECT
                    sale_date, branch_id, product_id,
                    qty_sold, qty_returned, qty_net, revenue,
                    promo_flag, promo_discount_pct, stockout
                FROM stage_daily_sales
                ON CONFLICT (sale_date, branch_id, product_id) DO UPDATE SET
                    qty_sold = EXCLUDED.qty_sold,
                    qty_returned = EXCLUDED.qty_returned,
                    qty_net = EXCLUDED.qty_net,
                    revenue = EXCLUDED.revenue,
                    promo_flag = EXCLUDED.promo_flag,
                    promo_discount_pct = EXCLUDED.promo_discount_pct,
                    stockout = EXCLUDED.stockout;
                """
            )

        conn.commit()
        return len(df_daily_sales)

    def run(
        self,
        start_date: date,
        end_date: date,
        dry_run: bool = False,
        allow_missing_stockout: Optional[bool] = None,
        stockout_source: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Thực thi toàn bộ quy trình tổng hợp."""
        t_start = time_mod.time()
        allow_missing = self.allow_missing_stockout if allow_missing_stockout is None else allow_missing_stockout
        src = self.stockout_source if stockout_source is None else stockout_source

        with get_db_connection() as conn:
            # 1. Trích xuất dữ liệu thô
            raw_data = self.fetch_raw_data_from_db(conn, start_date, end_date)

            # 2. Đọc lịch stockout
            if src == "db":
                stockout_schedule = InventoryTransactionStockoutProvider.load_schedule_from_db(conn, start_date, end_date)
            elif hasattr(self.stockout_provider, "load_schedule"):
                import inspect
                sig = inspect.signature(self.stockout_provider.load_schedule)
                if "allow_missing" in sig.parameters:
                    stockout_schedule = self.stockout_provider.load_schedule(allow_missing=allow_missing)
                else:
                    stockout_schedule = self.stockout_provider.load_schedule()
            elif callable(self.stockout_provider):
                stockout_schedule = self.stockout_provider()
            else:
                stockout_schedule = self.stockout_provider

            # 3. Tính toán DataFrame
            df_result = build_daily_sales(
                df_orders=raw_data["df_orders"],
                df_order_items=raw_data["df_order_items"],
                df_returns=raw_data["df_returns"],
                df_return_items=raw_data["df_return_items"],
                df_promotions=raw_data["df_promotions"],
                df_promo_branches=raw_data["df_promo_branches"],
                df_promo_products=raw_data["df_promo_products"],
                df_branches=raw_data["df_branches"],
                df_products=raw_data["df_products"],
                stockout_schedule_data=stockout_schedule,
                start_date=start_date,
                end_date=end_date,
            )

            total_rows = len(df_result)
            stats = {
                "total_rows": total_rows,
                "total_qty_sold": int(df_result["qty_sold"].sum()),
                "total_qty_returned": int(df_result["qty_returned"].sum()),
                "total_qty_net": int(df_result["qty_net"].sum()),
                "total_revenue": int(df_result["revenue"].sum()),
                "total_stockout_days": int(df_result["stockout"].sum()),
                "total_promo_days": int(df_result["promo_flag"].sum()),
            }

            # 4. Ghi vào CSDL (nếu không dry-run)
            if not dry_run:
                self.load_dataframe_to_db(conn, df_result)
                stats["status"] = "LOADED"
            else:
                stats["status"] = "DRY_RUN"

            stats["elapsed_seconds"] = round(time_mod.time() - t_start, 2)
            return stats


# ==============================================================================
# 4. CẦU NỐI DÒNG LỆNH (CLI INTERFACE)
# ==============================================================================


def parse_args() -> argparse.Namespace:
    """Phân tích đối số dòng lệnh CLI."""
    parser = argparse.ArgumentParser(description="Pipeline tổng hợp bảng daily_sales (Issue #109)")
    parser.add_argument(
        "--start-date",
        type=lambda s: date.fromisoformat(s),
        default=config.START_DATE.date(),
        help="Ngày bắt đầu (YYYY-MM-DD), mặc định: config.START_DATE",
    )
    parser.add_argument(
        "--end-date",
        type=lambda s: date.fromisoformat(s),
        default=config.END_DATE.date(),
        help="Ngày kết thúc (YYYY-MM-DD), mặc định: config.END_DATE",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Chỉ trích xuất và tính toán số liệu thống kê, không ghi vào CSDL",
    )
    parser.add_argument(
        "--allow-missing-stockout",
        action="store_true",
        help="Cho phép tiếp tục chạy pipeline với cờ stockout=False toàn bộ nếu thiếu file lịch stockout",
    )
    parser.add_argument(
        "--stockout-source",
        choices=["json", "db"],
        default="json",
        help="Nguồn xác định stockout: 'json' (file demo) hoặc 'db' (bảng inventory_transaction). Mặc định: json",
    )
    return parser.parse_args()


def main():
    """Hàm chạy chính từ giao diện dòng lệnh."""
    args = parse_args()
    print("=" * 70)
    print("GREENMART FORECAST-SERVICE: PIPELINE TỔNG HỢP DAILY_SALES (#109)")
    print(f"- Khoảng thời gian: {args.start_date} -> {args.end_date}")
    print(f"- Chế độ chạy: {'DRY RUN (Chỉ đọc & tính toán)' if args.dry_run else 'NẠP CSDL THỰC TẾ'}")
    print(f"- Nguồn stockout: {args.stockout_source} (allow-missing: {args.allow_missing_stockout})")
    print("=" * 70)

    pipeline = DailySalesPipeline(
        allow_missing_stockout=args.allow_missing_stockout,
        stockout_source=args.stockout_source,
    )
    try:
        stats = pipeline.run(args.start_date, args.end_date, dry_run=args.dry_run)
        print("\n[THÀNH CÔNG] Hoàn tất pipeline tổng hợp daily_sales!")
        print(f"- Tổng số dòng: {stats['total_rows']:,}")
        print(f"- Tổng số lượng bán (qty_sold): {stats['total_qty_sold']:,}")
        print(f"- Tổng số lượng trả (qty_returned): {stats['total_qty_returned']:,}")
        print(f"- Tổng bán ròng (qty_net): {stats['total_qty_net']:,}")
        print(f"- Tổng doanh thu thuần (revenue): {stats['total_revenue']:,} VND")
        print(f"- Số dòng có cờ stockout: {stats['total_stockout_days']:,}")
        print(f"- Số dòng có cờ khuyến mại: {stats['total_promo_days']:,}")
        print(f"- Thời gian thực thi: {stats['elapsed_seconds']} giây")
    except Exception as e:
        print(f"\n[LỖI] Pipeline thất bại: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
