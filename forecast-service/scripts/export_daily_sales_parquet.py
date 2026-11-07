"""Script xuất dữ liệu bảng daily_sales chuẩn (kèm metadata) ra định dạng Parquet.

Script này tích hợp module sinh dữ liệu chuẩn của Issue #106 (scripts/generate_data.py)
và pipeline tổng hợp của Issue #109 (app/pipelines/daily_sales.py) để tạo file
forecast-service/data/daily_sales.parquet phục vụ phân tích EDA (Issue #110)
khi không có kết nối cơ sở dữ liệu PostgreSQL trực tiếp.
"""

from __future__ import annotations

from datetime import date, datetime
from pathlib import Path
import sys
import time
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd

# Đường dẫn thư mục
_SCRIPT_DIR = Path(__file__).resolve().parent
_SERVICE_DIR = _SCRIPT_DIR.parent
if str(_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(_SERVICE_DIR))

from scripts import config
from scripts.generate_data import (
    generate_branches,
    generate_categories,
    generate_products,
    generate_customers,
    generate_promotions,
    generate_orders_and_items,
    generate_returns,
    get_stockout_schedule,
)
from app.pipelines.daily_sales import build_daily_sales

TIMEZONE_VN = ZoneInfo("Asia/Ho_Chi_Minh")


def export_daily_sales_to_parquet(
    output_path: Path | None = None,
) -> Path:
    """Tạo bảng daily_sales từ các entity sinh ngẫu nhiên có kiểm soát và xuất ra file Parquet."""
    if output_path is None:
        output_path = _SERVICE_DIR / "data" / "daily_sales.parquet"

    print("=" * 70)
    print("XUẤT BẢNG DAILY_SALES RA PARQUET PHỤC VỤ NOTEBOOK EDA (ISSUE #110)")
    print("=" * 70)

    t0 = time.time()
    print("[1/4] Sinh master data và giao dịch theo cấu hình chuẩn Issue #106...")
    branches = generate_branches()
    categories = generate_categories()
    products, top_sku_ids = generate_products()
    stockout_schedule = get_stockout_schedule(products, top_sku_ids)
    customers = generate_customers(count=1500)
    promotions = generate_promotions(products)
    processed_orders = generate_orders_and_items(
        products=products,
        top_sku_ids=top_sku_ids,
        customers=customers,
        promotions=promotions,
        stockout_schedule=stockout_schedule,
    )
    returns, return_items = generate_returns(processed_orders, products)

    print(f"      Thời gian sinh dữ liệu: {time.time() - t0:.2f}s")

    t1 = time.time()
    print("[2/4] Chuyển đổi dữ liệu sang DataFrames theo chuẩn schema daily_sales...")
    # 1. df_branches
    df_branches = pd.DataFrame([
        {"id": b.id, "code": b.code, "name": b.name, "is_active": b.is_active}
        for b in branches
    ])

    # 2. df_categories
    df_categories = pd.DataFrame([
        {"id": c.id, "name": c.name, "parent_id": c.parent_id, "group_code": c.group_code}
        for c in categories
    ])

    # 3. df_products
    df_products = pd.DataFrame([
        {
            "id": p.id,
            "sku": p.sku,
            "name": p.name,
            "barcode": p.barcode,
            "category_id": p.category_id,
            "group_code": p.group_code,
            "sale_price": p.sale_price,
            "cost_price": p.cost_price,
            "is_top_sku": p.is_top_sku,
            "is_active": p.is_active,
        }
        for p in products
    ])

    # 4. df_orders
    order_records = []
    order_item_records = []
    item_id_counter = 1
    for o in processed_orders:
        order_records.append({
            "id": o.id,
            "branch_id": o.branch_id,
            "status": o.status,
            "completed_at": o.completed_at,
            "subtotal": o.subtotal,
            "discount": o.discount,
            "total": o.total,
        })
        for it in o.items:
            order_item_records.append({
                "id": item_id_counter,
                "order_id": o.id,
                "product_id": it.product_id,
                "quantity": it.quantity,
                "unit_price": it.unit_price,
                "discount": it.discount,
                "line_total": it.line_total,
            })
            item_id_counter += 1

    df_orders = pd.DataFrame(order_records)
    df_order_items = pd.DataFrame(order_item_records)

    # 5. df_returns & df_return_items
    ret_records = []
    ret_item_records = []
    ret_item_id_counter = 1
    for r in returns:
        ret_records.append({
            "id": r.id,
            "order_id": r.order_id,
            "branch_id": r.branch_id,
            "status": r.status,
            "approved_at": r.approved_at,
            "refund_amount": r.refund_amount,
        })

    for r_item in return_items:
        ret_item_records.append({
            "id": r_item.id,
            "return_id": r_item.return_id,
            "order_item_id": r_item.order_item_id,
            "quantity": r_item.quantity,
            "condition": r_item.condition,
        })

    df_returns = pd.DataFrame(ret_records)
    df_return_items = pd.DataFrame(ret_item_records)

    # 6. df_promotions, promo_branches, promo_products
    promo_records = []
    promo_branch_records = []
    promo_prod_records = []
    for pr in promotions:
        promo_records.append({
            "id": pr.id,
            "code": pr.code,
            "type": pr.type,
            "level": pr.level,
            "value": pr.value,
            "start_at": pr.start_at,
            "end_at": pr.end_at,
            "status": pr.status,
        })
        for b_id in pr.branch_ids:
            promo_branch_records.append({"promotion_id": pr.id, "branch_id": b_id})
        for p_id in pr.product_ids:
            promo_prod_records.append({"promotion_id": pr.id, "product_id": p_id})

    df_promotions = pd.DataFrame(promo_records)
    df_promo_branches = pd.DataFrame(promo_branch_records)
    df_promo_products = pd.DataFrame(promo_prod_records)

    # 7. stockout schedule data: build_daily_sales kỳ vọng set(date, branch_id, product_id)
    stockout_set = {
        (d, b_id, p_id)
        for (b_id, p_id, d) in stockout_schedule.keys()
    }

    print(f"      Thời gian chuẩn bị DataFrames: {time.time() - t1:.2f}s")

    t2 = time.time()
    print("[3/4] Tổng hợp dữ liệu bán hàng hàng ngày qua pipeline vectorized...")
    df_daily = build_daily_sales(
        df_orders=df_orders,
        df_order_items=df_order_items,
        df_returns=df_returns,
        df_return_items=df_return_items,
        df_promotions=df_promotions,
        df_promo_branches=df_promo_branches,
        df_promo_products=df_promo_products,
        df_branches=df_branches,
        df_products=df_products,
        stockout_schedule_data=stockout_set,
        start_date=config.START_DATE.date(),
        end_date=config.END_DATE.date(),
    )
    print(f"      Thời gian tổng hợp: {time.time() - t2:.2f}s (Tổng: {len(df_daily):,} dòng)")

    t3 = time.time()
    print("[4/4] Bổ sung metadata (Chi nhánh, Sản phẩm, Danh mục) và xuất Parquet...")
    # Merge metadata
    prod_meta = df_products[[
        "id", "sku", "name", "category_id", "group_code", "sale_price", "cost_price", "is_top_sku"
    ]].rename(columns={"id": "product_id", "name": "product_name"})

    cat_meta = df_categories[["id", "name"]].rename(columns={"id": "category_id", "name": "category_name"})
    prod_meta = prod_meta.merge(cat_meta, on="category_id", how="left")

    branch_meta = df_branches[["id", "code", "name"]].rename(
        columns={"id": "branch_id", "code": "branch_code", "name": "branch_name"}
    )

    df_full = df_daily.merge(prod_meta, on="product_id", how="left")
    df_full = df_full.merge(branch_meta, on="branch_id", how="left")

    # Tối ưu kiểu dữ liệu để nén Parquet nhỏ gọn
    df_full["sale_date"] = pd.to_datetime(df_full["sale_date"])
    df_full["branch_id"] = df_full["branch_id"].astype("int16")
    df_full["product_id"] = df_full["product_id"].astype("int32")
    df_full["qty_sold"] = df_full["qty_sold"].astype("int16")
    df_full["qty_returned"] = df_full["qty_returned"].astype("int16")
    df_full["qty_net"] = df_full["qty_net"].astype("int16")
    df_full["revenue"] = df_full["revenue"].astype("int64")
    df_full["promo_flag"] = df_full["promo_flag"].astype("bool")
    df_full["promo_discount_pct"] = df_full["promo_discount_pct"].astype("float32")
    df_full["stockout"] = df_full["stockout"].astype("bool")
    df_full["is_top_sku"] = df_full["is_top_sku"].astype("bool")
    df_full["group_code"] = df_full["group_code"].astype("category")
    df_full["branch_code"] = df_full["branch_code"].astype("category")
    df_full["category_name"] = df_full["category_name"].astype("category")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    df_full.to_parquet(output_path, compression="snappy", index=False)

    size_mb = output_path.stat().st_size / (1024 * 1024)
    print(f"      Đã lưu file thành công tại: {output_path} ({size_mb:.2f} MB)")
    print(f"      Tổng thời gian hoàn tất: {time.time() - t0:.2f}s")
    print("=" * 70)
    return output_path


if __name__ == "__main__":
    export_daily_sales_to_parquet()
