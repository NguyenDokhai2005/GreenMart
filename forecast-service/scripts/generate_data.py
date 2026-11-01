"""Bộ sinh dữ liệu mẫu lịch sử 12 tháng GreenMart (Issue #106 / AI-01).

Chặng 1: Cấu hình danh mục, chi nhánh, sản phẩm (1.000 SKU) và tài khoản demo.
Chặng 2: Khách hàng, khuyến mại, đơn hàng, dòng đơn, thanh toán (10.000 đơn).
Chặng 3: Đơn online thất bại (200 đơn), phiếu trả hàng (150 phiếu).
Chặng 4: Xuất file SQL hoàn chỉnh (idempotent, setval, update channels) và file lịch stockout.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
import json
import math
import os
from pathlib import Path
import random
import sys
from typing import Any, Dict, List, Optional, Set, Tuple
from zoneinfo import ZoneInfo

# Đảm bảo đường dẫn import tương thích khi gọi từ mọi thư mục làm việc (repo root, forecast-service, test)
_MODULE_DIR = Path(__file__).resolve().parent
_SERVICE_DIR = _MODULE_DIR.parent
if str(_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(_SERVICE_DIR))

# Import cấu hình chuẩn từ Issue #105
try:
    from scripts import config
except ImportError:
    import config  # type: ignore


# ==============================================================================
# CẤU TRÚC DỮ LIỆU ĐỐI TƯỢNG (DATA MODELS)
# ==============================================================================


@dataclass
class Branch:
    id: int
    code: str
    name: str
    address: str
    is_active: bool = True


@dataclass
class Category:
    id: int
    name: str
    parent_id: Optional[int]
    group_code: str  # Ánh xạ nội bộ sang 3 nhóm ngành trong config


@dataclass
class Product:
    id: int
    sku: str
    barcode: str
    name: str
    unit: str
    category_id: int
    group_code: str
    sale_price: int
    cost_price: int  # Giả định thiết kế: đảm bảo lãi gộp dương cost_price < sale_price
    is_top_sku: bool = False
    is_active: bool = True


@dataclass
class AppUser:
    id: int
    username: str
    password_hash: str
    full_name: str
    role: str
    branch_ids: List[int]
    is_active: bool = True


@dataclass
class Customer:
    id: int
    phone: str
    name: str
    email: Optional[str]
    created_at: datetime


@dataclass
class Promotion:
    id: int
    code: str
    name: str
    type: str  # PERCENT | AMOUNT
    level: str  # ORDER | ITEM
    value: int  # Với PERCENT: số nguyên phần trăm (ví dụ 10 = 10%) do cột DB là BIGINT
    max_discount: Optional[int]
    min_order: Optional[int]
    start_at: datetime
    end_at: datetime
    usage_limit: Optional[int]
    used_count: int = 0
    status: str = "ACTIVE"
    product_ids: List[int] = field(default_factory=list)
    branch_ids: List[int] = field(default_factory=list)
    channel_codes: List[str] = field(default_factory=list)
    demand_lift: float = 0.0


@dataclass
class OrderItem:
    id: int
    order_id: int
    product_id: int
    quantity: int
    unit_price: int  # Giá chụp lúc bán (BR-12)
    discount: int  # Giảm giá cấp dòng
    line_total: int  # quantity * unit_price - discount
    promotion_id: Optional[int] = None


@dataclass
class Payment:
    id: int
    order_id: int
    method: str  # CASH | CARD | QR
    amount: int  # Khớp sales_order.total
    status: str = "PAID"  # PENDING | PAID | FAILED | REFUNDED
    provider_ref: Optional[str] = None
    paid_at: Optional[datetime] = None


@dataclass
class SalesOrder:
    id: int
    code: str
    channel_id: int
    channel_code: str
    external_order_id: Optional[str]
    branch_id: int
    branch_code: str
    customer_id: Optional[int]
    promotion_id: Optional[int]
    status: str  # COMPLETED | RETURNED | CANCELLED | EXPIRED | REJECTED
    subtotal: int  # Tổng giá gốc: sum(quantity * unit_price)
    discount: int  # Tổng giảm giá: item_discount + order_discount
    total: int  # subtotal - discount
    created_by: Optional[int]
    created_at: datetime
    completed_at: Optional[datetime]
    reserved_until: Optional[datetime] = None
    items: List[OrderItem] = field(default_factory=list)
    payment: Optional[Payment] = None


@dataclass
class ReturnItem:
    id: int
    return_id: int
    order_item_id: int
    quantity: int
    condition: str  # RESELLABLE | DAMAGED


@dataclass
class ReturnDocument:
    id: int
    code: str
    order_id: int
    branch_id: int
    reason: str
    refund_amount: int
    status: str  # DRAFT | APPROVED | CANCELLED
    created_by: int
    approved_by: int
    created_at: datetime  # Thời điểm khách mang hàng đến quầy trả
    approved_at: Optional[datetime] = None  # Thời điểm quản lý duyệt phiếu (BR-18, V4)
    items: List[ReturnItem] = field(default_factory=list)


@dataclass
class AuditLog:
    id: int
    user_id: Optional[int]
    action: str
    entity: str
    entity_id: str
    detail: str  # JSON format
    created_at: datetime


@dataclass
class StockDocument:
    id: int
    code: str
    type: str  # IMPORT | EXPORT | TRANSFER | ADJUST
    branch_id: int
    to_branch_id: Optional[int] = None
    status: str = "APPROVED"  # DRAFT | APPROVED | IN_TRANSIT | RECEIVED | CANCELLED
    supplier: Optional[str] = None
    reason: Optional[str] = None
    created_by: int = 1
    approved_by: Optional[int] = None
    received_by: Optional[int] = None
    created_at: datetime = field(default_factory=lambda: config.END_DATE)
    approved_at: Optional[datetime] = None
    received_at: Optional[datetime] = None


@dataclass
class StockDocumentItem:
    id: int
    document_id: int
    product_id: int
    quantity: int
    received_quantity: Optional[int] = None
    counted_quantity: Optional[int] = None
    unit_cost: Optional[int] = None


@dataclass
class Inventory:
    branch_id: int
    product_id: int
    on_hand: int
    reserved: int = 0
    reorder_point: int = 10
    version: int = 0
    updated_at: datetime = field(default_factory=lambda: config.END_DATE)


@dataclass
class InventoryTransaction:
    id: int
    branch_id: int
    product_id: int
    type: str  # IMPORT | EXPORT | SALE | RESERVE | RELEASE | RETURN | TRANSFER_OUT | TRANSFER_IN | ADJUST
    delta_on_hand: int
    on_hand_after: int
    delta_reserved: int = 0
    reserved_after: int = 0
    ref_type: str = "STOCK_DOCUMENT"
    ref_id: int = 1
    created_by: Optional[int] = None
    created_at: datetime = field(default_factory=lambda: config.END_DATE)


# ==============================================================================
# 1. HÀM SINH CHI NHÁNH (BRANCHES)
# ==============================================================================


def generate_branches() -> List[Branch]:
    """Sinh danh sách 3 chi nhánh GreenMart chuẩn theo đặc tả SRS."""
    return [
        Branch(
            id=1,
            code="GM-001",
            name="GreenMart Quận 1",
            address="123 Lê Lợi, Phường Bến Thành, Quận 1, TP. Hồ Chí Minh",
        ),
        Branch(
            id=2,
            code="GM-002",
            name="GreenMart Bình Thạnh",
            address="45 Điện Biên Phủ, Phường 15, Quận Bình Thạnh, TP. Hồ Chí Minh",
        ),
        Branch(
            id=3,
            code="GM-003",
            name="GreenMart Tân Bình",
            address="78 Hoàng Hoa Thám, Phường 12, Quận Tân Bình, TP. Hồ Chí Minh",
        ),
    ]


# ==============================================================================
# 2. HÀM SINH CÂY DANH MỤC 2 CẤP (CATEGORIES)
# ==============================================================================

CATEGORY_TREE_SPEC = [
    {
        "id": 1,
        "name": "Thực phẩm chế biến & Đồ uống",
        "group_code": "READY_TO_EAT_BEVERAGE",
        "children": [
            (4, "Cơm hộp & Món ăn liền", ["phần", "hộp"]),
            (5, "Nước giải khát & Nước ép", ["chai", "lon"]),
            (6, "Cà phê & Trà pha sẵn", ["lon", "hộp"]),
            (7, "Bánh mì & Đồ ăn nhẹ (Snack)", ["gói", "cái"]),
            (8, "Bánh ngọt & Món tráng miệng", ["hộp", "cái"]),
        ],
    },
    {
        "id": 2,
        "name": "Thực phẩm tươi sống",
        "group_code": "FRESH_FOOD",
        "children": [
            (9, "Rau lá & Củ quả tươi", ["kg", "túi", "bó"]),
            (10, "Thịt heo, bò, gà tươi sống", ["kg", "khay"]),
            (11, "Thủy hải sản tươi sống", ["kg", "khay"]),
            (12, "Trái cây nội địa & nhập khẩu", ["kg", "hộp"]),
            (13, "Trứng gia cầm & Thực phẩm sơ chế", ["vỉ", "hộp"]),
        ],
    },
    {
        "id": 3,
        "name": "Đồ khô & Tiêu dùng nhanh",
        "group_code": "DRY_FOOD_FMCG",
        "children": [
            (14, "Gạo, nếp & Ngũ cốc dinh dưỡng", ["túi", "kg"]),
            (15, "Mì, miến, bún & Phở khô", ["gói", "thùng"]),
            (16, "Dầu ăn, gia vị & Nước chấm", ["chai", "gói", "hũ"]),
            (17, "Sữa tươi, bơ & Phô mai", ["hộp", "lốc"]),
            (18, "Hóa mỹ phẩm & Tẩy rửa gia dụng", ["chai", "túi"]),
        ],
    },
]


def generate_categories() -> List[Category]:
    """Sinh danh sách 18 danh mục (3 danh mục cha Cấp 1 + 15 danh mục con Cấp 2)."""
    categories: List[Category] = []
    for parent in CATEGORY_TREE_SPEC:
        categories.append(
            Category(
                id=parent["id"],
                name=parent["name"],
                parent_id=None,
                group_code=parent["group_code"],
            )
        )
        for child_id, child_name, _ in parent["children"]:
            categories.append(
                Category(
                    id=child_id,
                    name=child_name,
                    parent_id=parent["id"],
                    group_code=parent["group_code"],
                )
            )
    return categories


# ==============================================================================
# 3. HÀM SINH 1.000 SẢN PHẨM (PRODUCTS & 80/20 PARETO)
# ==============================================================================

PRODUCT_NAME_TEMPLATES: Dict[int, List[Tuple[str, str, int, int]]] = {
    4: [
        ("Cơm gà xối mỡ", "COM-GA-XOI-MO", 35_000, 25_000),
        ("Cơm sườn bì chả", "COM-SUON-BI-CHA", 40_000, 28_000),
        ("Bánh mì que pate", "BANH-MI-PATE", 15_000, 10_000),
        ("Xôi mặn thập cẩm", "XOI-MAN-THAP-CAM", 25_000, 17_000),
        ("Sandwich thịt xông khói", "SANDWICH-THIT-XONG-KHOI", 32_000, 22_000),
        ("Salad ức gà sốt mè", "SALAD-UC-GA-ME", 38_000, 26_000),
    ],
    5: [
        ("Nước ngọt Coca-Cola 320ml", "COCA-COLA-320ML", 11_000, 8_000),
        ("Nước ngọt Pepsi lon 320ml", "PEPSI-LON-320ML", 11_000, 8_000),
        ("Trà xanh 0 Độ 455ml", "TRA-XANH-0DO-455ML", 12_000, 8_500),
        ("Nước tăng lực Redbull 250ml", "REDBULL-250ML", 15_000, 11_000),
        ("Nước khoáng Lavie 500ml", "LAVIE-500ML", 7_000, 4_500),
        ("Trà C2 hương chanh 455ml", "C2-CHANH-455ML", 10_000, 7_000),
    ],
    6: [
        ("Cà phê sữa đá Highlands lon", "HIGHLANDS-SUA-LON", 18_000, 13_000),
        ("Cà phê đen Highlands lon", "HIGHLANDS-DEN-LON", 18_000, 13_000),
        ("Trà đào sả lon Mr.Brown", "TRA-DAO-SA-MRBROWN", 20_000, 14_000),
        ("Trà ô long Tea+ lon 320ml", "TEA-PLUS-OLONG-320ML", 12_000, 8_500),
    ],
    7: [
        ("Snack khoai tây Lays tự nhiên 54g", "LAYS-KHOAI-TAY-54G", 14_000, 10_000),
        ("Snack bắp ngọt Oishi 40g", "OISHI-BAP-NGOT-40G", 8_000, 5_500),
        ("Bánh gạo An chà bông 145g", "BANH-GAO-AN-CHA-BONG", 28_000, 20_000),
        ("Bánh snack rong biển Tao Kae Noi", "TAOKAENOI-RONG-BIEN", 16_000, 11_500),
    ],
    8: [
        ("Bánh bông lan Cuộn bơ kem", "BONG-LAN-BO-KEM", 18_000, 12_000),
        ("Thạch rau câu Long Hải 500g", "THACH-RAU-CAU-LONGHAI", 22_000, 15_000),
        ("Chè bưởi đóng hộp", "CHE-BUOI-HOP", 20_000, 14_000),
    ],
    9: [
        ("Rau muống nước VietGAP 500g", "RAU-MUONG-500G", 12_000, 8_000),
        ("Rau cải ngọt VietGAP 500g", "RAU-CAI-NGOT-500G", 15_000, 10_000),
        ("Cà chua sạch Lâm Đồng 500g", "CA-CHUA-500G", 18_000, 12_000),
        ("Khoai tây củ vàng Đà Lạt 1kg", "KHOAI-TAY-1KG", 32_000, 22_000),
        ("Bắp cải trắng Đà Lạt 1kg", "BAP-CAI-1KG", 20_000, 13_000),
    ],
    10: [
        ("Thịt ba rọi heo MeatDeli 400g", "THIT-BA-ROI-MEATDELI-400G", 78_000, 58_000),
        ("Thịt nạc dăm heo 500g", "THIT-NAC-DAM-500G", 75_000, 55_000),
        ("Thăn bò Úc tươi 300g", "THAN-BO-UC-300G", 125_000, 95_000),
        ("Đùi gà góc tư CP 500g", "DUI-GA-CP-500G", 38_000, 27_000),
        ("Cánh gà tươi CP 500g", "CANH-GA-CP-500G", 52_000, 38_000),
    ],
    11: [
        ("Cá basa fillet 500g", "CA-BASA-FILLET-500G", 48_000, 35_000),
        ("Tôm thẻ chân trắng tươi 500g", "TOM-THE-TUOI-500G", 110_000, 82_000),
        ("Cá thu cắt khúc 400g", "CA-THU-KHUC-400G", 95_000, 70_000),
        ("Mực ống tươi loại vừa 500g", "MUC-ONG-TUOI-500G", 135_000, 100_000),
    ],
    12: [
        ("Chuối già Nam Mỹ vỉ 1kg", "CHUOI-NAM-MY-1KG", 28_000, 19_000),
        ("Cam sành mọng nước 1kg", "CAM-SANH-1KG", 32_000, 22_000),
        ("Táo Envy New Zealand 1kg", "TAO-ENVY-NZ-1KG", 145_000, 105_000),
        ("Dưa hấu đỏ không hạt 2kg", "DUA-HAU-KHONG-HAT", 45_000, 30_000),
    ],
    13: [
        ("Trứng gà công nghiệp Ba Huân hộp 10", "TRUNG-GA-BA-HUAN-10", 32_000, 24_000),
        ("Trứng vịt lộn vỉ 4 quả", "TRUNG-VIT-LON-4", 26_000, 19_000),
        ("Đậu hũ non Vfood hộp 300g", "DAU-HU-NON-300G", 14_000, 9_500),
    ],
    14: [
        ("Gạo Nàng Thơm Chợ Đào túi 5kg", "GAO-NANG-THOM-5KG", 135_000, 105_000),
        ("Gạo thơm Lài Miên túi 5kg", "GAO-LAI-MIEN-5KG", 125_000, 95_000),
        ("Nếp cái hoa vàng túi 1kg", "NEP-HOA-VANG-1KG", 35_000, 26_000),
        ("Yến mạch nguyên chất Quaker 500g", "YEN-MACH-QUAKER-500G", 55_000, 40_000),
    ],
    15: [
        ("Mì tôm Hảo Hảo tôm chua cay 75g", "MI-HAO-HAO-CHUA-CAY", 4_500, 3_400),
        ("Mì Indomie Goreng vị đặc biệt 85g", "INDOMIE-GORENG-85G", 6_000, 4_400),
        ("Bún khô sợi nhỏ Sa Đéc 400g", "BUN-KHO-SA-DEC-400G", 22_000, 16_000),
        ("Miến dong cao cấp Việt Cường 500g", "MIEN-DONG-500G", 45_000, 32_000),
    ],
    16: [
        ("Dầu ăn Neptune Gold 1L", "DAU-AN-NEPTUNE-1L", 52_000, 39_000),
        ("Dầu thực vật Tường An Cooking Oil 1L", "TUONG-AN-COOKING-1L", 42_000, 31_000),
        ("Nước mắm Nam Ngư Đệ Nhị 900ml", "NUOC-MAM-NAM-NGU-900ML", 28_000, 20_000),
        ("Nước tương Maggi Đậm Đặc 700ml", "MAGGI-DAM-DAC-700ML", 30_000, 21_000),
        ("Hạt nêm Knorr thịt thăn 400g", "KNORR-THIT-THAN-400G", 38_000, 28_000),
    ],
    17: [
        ("Sữa tươi tiệt trùng Vinamilk 1L", "SUA-VINAMILK-1L", 34_000, 26_000),
        ("Lốc 4 hộp Sữa chua Vinamilk có đường", "SUA-CHUA-VINAMILK-LOC4", 26_000, 19_000),
        ("Phô mai Con Bò Cười hộp 8 miếng", "PHO-MAI-CON-BO-CUOI-8", 42_000, 31_000),
        ("Bơ lạt Anchor 227g", "BO-LAT-ANCHOR-227G", 78_000, 58_000),
    ],
    18: [
        ("Nước rửa chén Sunlight Chanh 750g", "SUNLIGHT-CHANH-750G", 32_000, 23_000),
        ("Nước giặt OMO Matic cửa trên 2kg", "OMO-MATIC-CUA-TREN-2KG", 135_000, 98_000),
        ("Nước xả vải Comfort một lần xả 1.8L", "COMFORT-1LAN-XA-1.8L", 115_000, 84_000),
        ("Kem đánh răng Colgate Total 180g", "COLGATE-TOTAL-180G", 42_000, 30_000),
    ],
}


def generate_products() -> Tuple[List[Product], Set[int]]:
    """Sinh danh sách đúng 1.000 sản phẩm theo quy chuẩn của GreenMart."""
    rng = random.Random(config.RANDOM_SEED)
    categories = generate_categories()
    child_categories = [c for c in categories if c.parent_id is not None]

    cat_to_unit_sample = {
        4: "hộp", 5: "lon", 6: "lon", 7: "gói", 8: "hộp",
        9: "túi", 10: "khay", 11: "khay", 12: "kg", 13: "hộp",
        14: "túi", 15: "gói", 16: "chai", 17: "hộp", 18: "chai",
    }

    products_by_id: Dict[int, Product] = {}

    # 1. Tái hiện chuẩn xác 2 sản phẩm ví dụ
    products_by_id[101] = Product(
        id=101,
        sku="GAO-ST25-5KG",
        barcode="8935001201015",
        name="Gạo ST25 túi 5kg",
        unit="túi",
        category_id=14,
        group_code="DRY_FOOD_FMCG",
        sale_price=180_000,
        cost_price=140_000,
        is_top_sku=True,
    )

    products_by_id[205] = Product(
        id=205,
        sku="SUA-TH-1L",
        barcode="8935217400013",
        name="Sữa tươi TH True Milk 1L",
        unit="hộp",
        category_id=17,
        group_code="DRY_FOOD_FMCG",
        sale_price=29_000,
        cost_price=22_000,
        is_top_sku=True,
    )

    # 2. Sinh các sản phẩm còn lại cho đủ 1.000 SKU
    current_id = 1
    child_cat_cycle = list(child_categories)
    cat_idx = 0

    while len(products_by_id) < config.TOTAL_SKU_COUNT:
        while current_id in products_by_id:
            current_id += 1

        cat = child_cat_cycle[cat_idx % len(child_cat_cycle)]
        cat_idx += 1
        cat_id = cat.id
        templates = PRODUCT_NAME_TEMPLATES.get(cat_id, [])
        unit_default = cat_to_unit_sample.get(cat_id, "gói")

        template_idx = current_id % len(templates)
        base_name, base_sku, base_price, base_cost = templates[template_idx]

        variant_num = (current_id // len(templates)) + 1
        if variant_num == 1:
            name = f"{base_name} ({current_id})"
            sku = f"{base_sku}-{current_id:04d}"
        else:
            name = f"{base_name} loại {variant_num} ({current_id})"
            sku = f"{base_sku}-V{variant_num}-{current_id:04d}"

        barcode = f"893{current_id:010d}"

        price_variation = rng.uniform(0.95, 1.05)
        sale_price = int(round(base_price * price_variation, -3))
        cost_price = int(round(base_cost * price_variation, -3))
        if cost_price >= sale_price:
            cost_price = int(sale_price * 0.75)

        products_by_id[current_id] = Product(
            id=current_id,
            sku=sku,
            barcode=barcode,
            name=name,
            unit=unit_default,
            category_id=cat_id,
            group_code=cat.group_code,
            sale_price=sale_price,
            cost_price=cost_price,
            is_top_sku=False,
        )
        current_id += 1

    products = [products_by_id[i] for i in sorted(products_by_id.keys())]

    # 3. Chọn đúng 200 SKU bán chạy cốt lõi (Top 20% Pareto)
    top_sku_ids: Set[int] = {101, 205}
    candidate_ids = [p.id for p in products if p.id not in top_sku_ids]
    additional_top = rng.sample(candidate_ids, config.PARETO_TARGET_SKU_COUNT - len(top_sku_ids))
    top_sku_ids.update(additional_top)

    for p in products:
        p.is_top_sku = p.id in top_sku_ids

    return products, top_sku_ids


# ==============================================================================
# 4. HÀM SINH TÀI KHOẢN DEMO (APP_USER & USER_BRANCH)
# ==============================================================================

# 12 mã hash BCrypt chuẩn Spring Security ($2a$10$...) cho mật khẩu 'Demo@123' với 12 salt ngẫu nhiên độc lập
# được sinh cố định từ RANDOM_SEED = 2026 nhằm đảm bảo tính lặp lại và vượt qua test ID-052
DEMO_PASSWORD_HASHES: List[str] = [
    "$2a$10$FjE.62Mj1NKY2hljkX8MYumr9nr/ZWHUt52YaKTRBgWlUUCOG5yOG",
    "$2a$10$wHIKz5tEvF1.vXXuNOAbD.Ap6Q7tv0sSgKU0aVy8mtF3nSBKQ1VFi",
    "$2a$10$FClPEVKAyMQtdYzOLUT.UOIescMAi2TXPAl0xKiUfHEOeb.m2hhwW",
    "$2a$10$8t9rU63e954BmvM3TvYMgOgmrhDu855O1rWF.q6CVGYFNGvl8d9b2",
    "$2a$10$QijzHMAjrJcRRuZXrT3bbeat8rmao2FyadLBOm2Z3E9bn1T6Mr0RS",
    "$2a$10$Z/bk3HULd72bZGFLuL0X2.eN8CxZchD2uOYEmaC4uUo0RBvoT.5jG",
    "$2a$10$dUVofiJWY3Br81oBfK2EiuozXgBySz6Aa61yDD1p3/52whS43biAq",
    "$2a$10$MaOfyOx0rGVhovo1l0z2H.AeCmZf4t1d2/t2Ax7c9BnH17r8CWfWS",
    "$2a$10$c8G40cGdmJLypFg05dV68eVVxGSLd4tAEOPEkTKhDTHawgfcoV42C",
    "$2a$10$gyANXot9RmxKnWAwcq5gXeEnycr2xIYhYZ5yyPACfZH1MU6a0YMo6",
    "$2a$10$ZhLJzCOAAAYl2Xhzba0D5ORfmXZKhq5qAPbHF6D38Rb7PkENSExKq",
    "$2a$10$dTxpKyIvdFcAvLL9xk8MoOLrkSYXjAXp1Q354Hph9gf11HmCLAA.O",
]


def generate_demo_users() -> List[AppUser]:
    """Sinh danh sách 12 tài khoản demo cho 5 vai trò × 3 chi nhánh.

    Khớp chính xác:
    - Thu ngân CN1: ID 7, username 'thungan.gm001', tên 'Nguyễn Thu Ngân'
    - ID 6: Phó chủ chuỗi GreenMart (role OWNER)
    - Mật khẩu chung: 'Demo@123', mỗi tài khoản có salt và hash BCrypt riêng biệt (ID-052)
    """
    return [
        AppUser(
            id=1,
            username="admin",
            password_hash=DEMO_PASSWORD_HASHES[0],
            full_name="Quản trị hệ thống",
            role="ADMIN",
            branch_ids=[1, 2, 3],
        ),
        AppUser(
            id=2,
            username="owner",
            password_hash=DEMO_PASSWORD_HASHES[1],
            full_name="Chủ chuỗi GreenMart",
            role="OWNER",
            branch_ids=[1, 2, 3],
        ),
        AppUser(
            id=3,
            username="quanly.gm001",
            password_hash=DEMO_PASSWORD_HASHES[2],
            full_name="Trần Văn Quản Lý 1",
            role="BRANCH_MANAGER",
            branch_ids=[1],
        ),
        AppUser(
            id=4,
            username="quanly.gm002",
            password_hash=DEMO_PASSWORD_HASHES[3],
            full_name="Lê Thị Quản Lý 2",
            role="BRANCH_MANAGER",
            branch_ids=[2],
        ),
        AppUser(
            id=5,
            username="quanly.gm003",
            password_hash=DEMO_PASSWORD_HASHES[4],
            full_name="Phạm Văn Quản Lý 3",
            role="BRANCH_MANAGER",
            branch_ids=[3],
        ),
        AppUser(
            id=6,
            username="owner.pho",
            password_hash=DEMO_PASSWORD_HASHES[5],
            full_name="Trần Thị Phó Chủ Chuỗi",
            role="OWNER",
            branch_ids=[1, 2, 3],
        ),
        AppUser(
            id=7,
            username="thungan.gm001",
            password_hash=DEMO_PASSWORD_HASHES[6],
            full_name="Nguyễn Thu Ngân",
            role="CASHIER",
            branch_ids=[1],
        ),
        AppUser(
            id=8,
            username="thungan.gm002",
            password_hash=DEMO_PASSWORD_HASHES[7],
            full_name="Vũ Thu Ngân 2",
            role="CASHIER",
            branch_ids=[2],
        ),
        AppUser(
            id=9,
            username="thungan.gm003",
            password_hash=DEMO_PASSWORD_HASHES[8],
            full_name="Đặng Thu Ngân 3",
            role="CASHIER",
            branch_ids=[3],
        ),
        AppUser(
            id=10,
            username="thukho.gm001",
            password_hash=DEMO_PASSWORD_HASHES[9],
            full_name="Hoàng Văn Thủ Kho 1",
            role="STOREKEEPER",
            branch_ids=[1],
        ),
        AppUser(
            id=11,
            username="thukho.gm002",
            password_hash=DEMO_PASSWORD_HASHES[10],
            full_name="Bùi Văn Thủ Kho 2",
            role="STOREKEEPER",
            branch_ids=[2],
        ),
        AppUser(
            id=12,
            username="thukho.gm003",
            password_hash=DEMO_PASSWORD_HASHES[11],
            full_name="Đỗ Văn Thủ Kho 3",
            role="STOREKEEPER",
            branch_ids=[3],
        ),
    ]


# ==============================================================================
# 5. HÀM SINH KHÁCH HÀNG (CUSTOMERS - CHẶNG 2)
# ==============================================================================

VIETNAMESE_LAST_NAMES = ["Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Huỳnh", "Phan", "Vũ", "Võ", "Đặng", "Bùi", "Đỗ"]
VIETNAMESE_MIDDLE_NAMES = ["Văn", "Thị", "Đức", "Thành", "Hải", "Minh", "Hồng", "Thanh", "Ngọc", "Quang"]
VIETNAMESE_FIRST_NAMES = ["Mai", "Tuấn", "Lan", "Hùng", "Dũng", "Cường", "Trang", "Nga", "Linh", "Khoa", "Đạt", "Huy", "An", "Bình"]


def generate_customers(count: int = 1500) -> List[Customer]:
    """Sinh danh sách 1.500 khách hàng thân thiết.

    Tái hiện chính xác khách hàng ID 125: 'Trần Thị Mai', SĐT '0901234567'.
    """
    rng = random.Random(config.RANDOM_SEED)
    customers: List[Customer] = []
    used_phones: Set[str] = set()

    # ID 125 mẫu
    special_c = Customer(
        id=125,
        phone="0901234567",
        name="Trần Thị Mai",
        email="mai.tran@gmail.com",
        created_at=config.START_DATE + timedelta(days=5, hours=9),
    )
    used_phones.add("0901234567")

    cust_dict: Dict[int, Customer] = {125: special_c}

    # Sinh các khách hàng còn lại
    cust_id = 1
    while len(cust_dict) < count:
        if cust_id == 125:
            cust_id += 1
            continue

        phone_suffix = f"{cust_id:08d}"
        phone = f"09{phone_suffix[-8:]}"
        if phone in used_phones:
            phone = f"08{phone_suffix[-8:]}"
        used_phones.add(phone)

        last = rng.choice(VIETNAMESE_LAST_NAMES)
        mid = rng.choice(VIETNAMESE_MIDDLE_NAMES)
        first = rng.choice(VIETNAMESE_FIRST_NAMES)
        full_name = f"{last} {mid} {first}"

        days_offset = rng.randint(0, 300)
        c_time = config.START_DATE + timedelta(days=days_offset, hours=rng.randint(8, 20), minutes=rng.randint(0, 59))

        cust_dict[cust_id] = Customer(
            id=cust_id,
            phone=phone,
            name=full_name,
            email=f"user{cust_id}@example.com" if cust_id % 3 != 0 else None,
            created_at=c_time,
        )
        cust_id += 1

    return [cust_dict[i] for i in sorted(cust_dict.keys())]


def adjust_customer_created_at(customers: List[Customer], orders: List[SalesOrder]) -> None:
    """Đảm bảo customer.created_at <= min(sales_order.created_at) cho mọi khách hàng đã phát sinh đơn."""
    first_order_by_cust: Dict[int, datetime] = {}
    for o in orders:
        if o.customer_id is not None:
            if o.customer_id not in first_order_by_cust or o.created_at < first_order_by_cust[o.customer_id]:
                first_order_by_cust[o.customer_id] = o.created_at

    for c in customers:
        if c.id in first_order_by_cust:
            first_time = first_order_by_cust[c.id]
            if c.created_at > first_time:
                offset_mins = (c.id % 45) + 15
                c.created_at = max(config.START_DATE, first_time - timedelta(minutes=offset_mins))


# ==============================================================================
# 6. HÀM SINH KHUYẾN MẠI (PROMOTIONS - CHẶNG 2)
# ==============================================================================


def generate_promotions(products: Optional[List[Product]] = None) -> List[Promotion]:
    """Sinh danh sách các chương trình khuyến mại trong 12 tháng lịch sử.

    Bao gồm các mã mẫu và chiến dịch:
    - ID 11: GAO-15K (Giảm 15.000 đ cho Gạo ST25 5kg - đợt tháng 11, 7 ngày)
    - ID 12: DON200K-10 (Giảm 10% cho đơn từ 200.000 đ, tối đa 30.000 đ - đợt tháng 11, 6 ngày)
    - ID 13: TET2026-15 (Giảm 15% cho đơn từ 300.000 đ, tối đa 50.000 đ - dịp Tết Bính Ngọ, 14 ngày)
    - ID 14..25: 12 chiến dịch khuyến mại cấp ITEM hàng tháng (20-50 SKU/đợt, lift 30-80%, thời lượng 3-7 ngày)
    - ID 26..37: 12 chiến dịch khuyến mại cấp ORDER hàng tháng (giảm 20.000 đ đơn từ 200k, thời lượng 3-7 ngày)
    Áp dụng đầy đủ phạm vi SPECIFIC/ALL và POS_ONLY/ONLINE_ONLY theo config.py.
    """
    rng = random.Random(config.RANDOM_SEED)
    promotions: List[Promotion] = []

    if products is None:
        prods, _ = generate_products()
    else:
        prods = products

    top_prods = [p for p in prods if p.is_top_sku]
    top_sku_candidates = [p.id for p in top_prods if p.id != 101]

    branch_options = [
        [1, 2, 3],  # ALL
        [1],        # GM-001
        [2],        # GM-002
        [1, 2],     # GM-001 & GM-002
        [3],        # GM-003
        [1, 2, 3],  # ALL
    ]
    channel_options = [
        ["POS", "WEB", "MARKET"],  # ALL
        ["POS"],                   # POS_ONLY
        ["WEB", "MARKET"],          # ONLINE_ONLY
        ["POS", "WEB", "MARKET"],  # ALL
    ]

    # 1. Khuyến mại mẫu ID 11: GAO-15K (ITEM, AMOUNT = 15.000đ, đợt 7 ngày tháng 11)
    s11 = datetime(2025, 11, 10, 0, 0, 0, tzinfo=config.TIMEZONE)
    e11 = s11 + timedelta(days=7 - 1, hours=23, minutes=59, seconds=59)
    p_rice = Promotion(
        id=11,
        code="GAO-15K",
        name="Giảm 15.000 đ cho Gạo ST25 5kg",
        type="AMOUNT",
        level="ITEM",
        value=15_000,
        max_discount=None,
        min_order=None,
        start_at=s11,
        end_at=e11,
        usage_limit=None,
        status="ACTIVE",
        product_ids=[101],
        branch_ids=[1, 2, 3],
        channel_codes=["POS", "WEB", "MARKET"],
        demand_lift=0.50,
    )
    promotions.append(p_rice)

    # 2. Khuyến mại mẫu ID 12: DON200K-10 (ORDER, PERCENT = 10%, đợt 6 ngày tháng 11)
    s12 = datetime(2025, 11, 20, 0, 0, 0, tzinfo=config.TIMEZONE)
    e12 = s12 + timedelta(days=6 - 1, hours=23, minutes=59, seconds=59)
    p_order = Promotion(
        id=12,
        code="DON200K-10",
        name="Giảm 10% đơn từ 200.000 đ",
        type="PERCENT",
        level="ORDER",
        value=10,  # 10% lưu dạng số nguyên cho cột BIGINT
        max_discount=30_000,
        min_order=200_000,
        start_at=s12,
        end_at=e12,
        usage_limit=None,
        status="ACTIVE",
        product_ids=[],
        branch_ids=[1, 2, 3],
        channel_codes=["POS", "WEB", "MARKET"],
    )
    promotions.append(p_order)

    # 3. Khuyến mại đặc biệt đợt Tết Bính Ngọ 2026 (14 ngày trước Tết)
    tet_start = datetime(2026, 2, 3, 0, 0, 0, tzinfo=config.TIMEZONE)
    tet_end = datetime(2026, 2, 16, 23, 59, 59, tzinfo=config.TIMEZONE)
    p_tet = Promotion(
        id=13,
        code="TET2026-15",
        name="Ưu đãi đón Tết Bính Ngọ giảm 15% đơn từ 300k",
        type="PERCENT",
        level="ORDER",
        value=15,
        max_discount=50_000,
        min_order=300_000,
        start_at=tet_start,
        end_at=tet_end,
        usage_limit=1000,
        status="ACTIVE",
        product_ids=[],
        branch_ids=[1, 2, 3],
        channel_codes=["POS", "WEB", "MARKET"],
    )
    promotions.append(p_tet)

    # 4. Sinh 12 chiến dịch khuyến mại cấp ITEM hàng tháng (ID 14..25)
    promo_id = 14
    for month in range(1, 13):
        if month <= 2:
            y, m = 2025, month + 10  # Tháng 11, 12 năm 2025
        else:
            y, m = 2026, month - 2   # Tháng 1 đến 10 năm 2026

        start_day = rng.randint(3, 7)
        duration = rng.randint(config.PROMOTION_CAMPAIGN_DURATION_DAYS_RANGE[0], config.PROMOTION_CAMPAIGN_DURATION_DAYS_RANGE[1])
        s_dt = datetime(y, m, start_day, 0, 0, 0, tzinfo=config.TIMEZONE)
        e_dt = s_dt + timedelta(days=duration - 1, hours=23, minutes=59, seconds=59)

        sku_cnt = rng.randint(config.PROMOTION_ITEM_SKU_COUNT_RANGE[0], config.PROMOTION_ITEM_SKU_COUNT_RANGE[1])
        sampled_skus = sorted(rng.sample(top_sku_candidates, sku_cnt))
        pct_val = rng.choice([10, 15, 20])
        b_scope = branch_options[(month - 1) % len(branch_options)]
        ch_scope = channel_options[(month - 1) % len(channel_options)]

        p_item = Promotion(
            id=promo_id,
            code=f"KM-ITEM-T{m:02d}",
            name=f"Khuyến mại tháng {m:02d} - Giảm {pct_val}% sản phẩm thiết yếu",
            type="PERCENT",
            level="ITEM",
            value=pct_val,
            max_discount=None,
            min_order=None,
            start_at=s_dt,
            end_at=e_dt,
            usage_limit=None,
            status="ACTIVE",
            product_ids=sampled_skus,
            branch_ids=b_scope,
            channel_codes=ch_scope,
            demand_lift=round(rng.uniform(0.50, 0.70), 2),
        )
        promotions.append(p_item)
        promo_id += 1

    # 5. Sinh 12 chiến dịch khuyến mại cấp ORDER hàng tháng (ID 26..37)
    for month in range(1, 13):
        if month <= 2:
            y, m = 2025, month + 10  # Tháng 11, 12 năm 2025
        else:
            y, m = 2026, month - 2   # Tháng 1 đến 10 năm 2026

        if m == 11 and y == 2025:
            start_day = 3
        elif m == 2 and y == 2026:
            start_day = 20
        else:
            start_day = rng.randint(18, 22)

        duration = rng.randint(config.PROMOTION_CAMPAIGN_DURATION_DAYS_RANGE[0], config.PROMOTION_CAMPAIGN_DURATION_DAYS_RANGE[1])
        s_dt = datetime(y, m, start_day, 0, 0, 0, tzinfo=config.TIMEZONE)
        e_dt = s_dt + timedelta(days=duration - 1, hours=23, minutes=59, seconds=59)

        b_scope = branch_options[month % len(branch_options)]
        ch_scope = channel_options[month % len(channel_options)]

        p_order = Promotion(
            id=promo_id,
            code=f"KM-ORD-T{m:02d}",
            name=f"Khuyến mại tháng {m:02d} - Giảm 20.000 đ đơn từ 200k",
            type="AMOUNT",
            level="ORDER",
            value=20_000,
            max_discount=None,
            min_order=200_000,
            start_at=s_dt,
            end_at=e_dt,
            usage_limit=500,
            status="ACTIVE",
            product_ids=[],
            branch_ids=b_scope,
            channel_codes=ch_scope,
        )
        promotions.append(p_order)
        promo_id += 1

    return promotions


# ==============================================================================
# 7. HÀM SINH LỊCH STOCKOUT (STOCKOUT SCHEDULE - BƯỚC 0)
# ==============================================================================


def get_stockout_schedule(
    products: List[Product],
    top_sku_ids: Set[int],
    seed: int = config.RANDOM_SEED + 99,
) -> Dict[Tuple[int, int, date], str]:
    """Sinh lịch stockout giả lập cho 200 SKU bán chạy cốt lõi trên 3 chi nhánh.

    Đặc tả:
    - 600 cặp (chi_nhánh, top_sku).
    - Tỷ lệ ngày stockout: 2%–3% số ngày trong năm (7 đến 11 ngày/năm cho mỗi cặp).
    - Mỗi đợt kéo dài 1–3 ngày (STOCKOUT_DURATION_DAYS_RANGE).
    - Ngày đầu của đợt đa ngày là 'MID_DAY' (hết hàng sau 11:00), các ngày tiếp theo là 'FULL_DAY'.
    """
    rng = random.Random(seed)
    start_d = config.START_DATE.date()
    end_d = config.END_DATE.date()
    days_in_year = (end_d - start_d).days + 1

    schedule: Dict[Tuple[int, int, date], str] = {}

    for b_id in [1, 2, 3]:
        for p_id in sorted(top_sku_ids):
            target_days = rng.randint(7, 11)
            episodes: List[int] = []
            rem = target_days
            while rem > 0:
                ep_len = min(rem, rng.randint(config.STOCKOUT_DURATION_DAYS_RANGE[0], config.STOCKOUT_DURATION_DAYS_RANGE[1]))
                episodes.append(ep_len)
                rem -= ep_len

            occupied_indices: Set[int] = set()
            for ep_len in episodes:
                attempts = 0
                while attempts < 100:
                    idx = rng.randint(0, days_in_year - ep_len)
                    overlap = any(idx - 3 <= oc <= idx + ep_len + 2 for oc in occupied_indices)
                    if not overlap:
                        for i in range(ep_len):
                            occupied_indices.add(idx + i)
                            d = start_d + timedelta(days=idx + i)
                            if ep_len > 1:
                                mode = "MID_DAY" if i == 0 else "FULL_DAY"
                            else:
                                mode = "MID_DAY" if rng.random() < 0.50 else "FULL_DAY"
                            schedule[(b_id, p_id, d)] = mode
                        break
                    attempts += 1

    return schedule


# ==============================================================================
# 8. HÀM SINH ĐƠN HÀNG, DÒNG ĐƠN & THANH TOÁN (ORDERS - CHẶNG 2)
# ==============================================================================


def calculate_daily_demand_weights() -> Dict[date, float]:
    """Tính trọng số nhu cầu theo từng ngày trong 365 ngày (01/11/2025 đến 31/10/2026).

    Áp dụng công thức có chặn trần MAX_DEMAND_LIFT_CAP = 3.0.
    """
    rng = random.Random(config.RANDOM_SEED)
    cur = config.START_DATE.date()
    end = config.END_DATE.date()
    weights: Dict[date, float] = {}

    while cur <= end:
        dow = cur.weekday()
        f_dow = config.DAY_OF_WEEK_FACTORS.get(dow, 1.0)

        f_season = 1.0
        if config.TET_HOLIDAY_START <= cur <= config.TET_HOLIDAY_END:
            f_season = config.TET_HOLIDAY_DEMAND_FACTOR
        elif config.TET_PRE_START <= cur <= config.TET_PRE_END:
            f_season = rng.uniform(config.TET_PRE_DEMAND_LIFT_RANGE[0], config.TET_PRE_DEMAND_LIFT_RANGE[1])
        else:
            for hol in config.SPECIAL_HOLIDAYS.values():
                if hol["start"] <= cur <= hol["end"]:
                    f_season = max(f_season, hol["demand_factor"])

        noise = rng.uniform(config.DEMAND_NOISE_RANGE[0], config.DEMAND_NOISE_RANGE[1])

        if config.TET_HOLIDAY_START <= cur <= config.TET_HOLIDAY_END:
            raw = f_season * (1.0 + noise)
        else:
            raw = min(f_dow * f_season * (1.0 + noise), config.MAX_DEMAND_LIFT_CAP)

        weights[cur] = max(0.05, raw)
        cur += timedelta(days=1)

    return weights


def generate_orders_and_items(
    products: List[Product],
    top_sku_ids: Set[int],
    customers: List[Customer],
    promotions: List[Promotion],
    stockout_schedule: Optional[Dict[Tuple[int, int, date], str]] = None,
) -> List[SalesOrder]:
    """Sinh đúng 10.000 đơn hàng đã xử lý (COMPLETED) thỏa mãn:

    - Tỷ lệ kênh: POS 70% (7.000), WEB 20% (2.000), MARKET 10% (1.000).
    - Tỷ lệ chi nhánh: GM-001 45% (4.500), GM-002 35% (3.500), GM-003 20% (2.000).
    - Quy luật Pareto 80/20 theo SẢN LƯỢNG (qty_sold).
    - Tuân thủ lịch stockout: không bán ngày FULL_DAY, không bán sau 11:00 ngày MID_DAY.
    - Mọi ràng buộc CHECK và OpenAPI Quote chuẩn xác.
    """
    rng = random.Random(config.RANDOM_SEED)
    daily_weights = calculate_daily_demand_weights()
    all_dates = sorted(daily_weights.keys())
    total_weight = sum(daily_weights.values())

    top_prods = [p for p in products if p.is_top_sku]
    other_prods = [p for p in products if not p.is_top_sku]

    branch_code_to_id = {"GM-001": 1, "GM-002": 2, "GM-003": 3}
    channel_code_to_id = {"POS": 1, "WEB": 2, "MARKET": 3}

    # Trọng số nhóm ngành cơ sở cho nhóm 800 SKU còn lại theo từng chi nhánh
    other_weights_by_branch = {
        b: [config.BRANCH_CATEGORY_DEMAND_WEIGHTS[b][p.group_code] for p in other_prods]
        for b in ["GM-001", "GM-002", "GM-003"]
    }
    item_promos_list = [p for p in promotions if p.level == "ITEM"]

    QUOTAS: List[Tuple[str, str, int]] = [
        ("POS", "GM-001", 2680),
        ("POS", "GM-002", 2500),
        ("POS", "GM-003", 1820),
        ("WEB", "GM-001", 1700),
        ("WEB", "GM-002", 200),
        ("WEB", "GM-003", 100),
        ("MARKET", "GM-001", 120),
        ("MARKET", "GM-002", 800),
        ("MARKET", "GM-003", 80),
    ]

    orders: List[SalesOrder] = []
    order_id_counter = 1
    item_id_counter = 1
    payment_id_counter = 1

    daily_branch_order_seq: Dict[Tuple[str, str, date], int] = {}

    for ch_code, b_code, quota in QUOTAS:
        b_id = branch_code_to_id[b_code]
        ch_id = channel_code_to_id[ch_code]

        day_allocations: Dict[date, int] = {}
        rem = quota
        for d in all_dates:
            cnt = int(round(quota * (daily_weights[d] / total_weight)))
            day_allocations[d] = cnt
            rem -= cnt

        sorted_by_w = sorted(all_dates, key=lambda d: daily_weights[d], reverse=True)
        idx = 0
        while rem > 0:
            day_allocations[sorted_by_w[idx % len(sorted_by_w)]] += 1
            rem -= 1
            idx += 1
        while rem < 0:
            d_pick = sorted_by_w[idx % len(sorted_by_w)]
            if day_allocations[d_pick] > 0:
                day_allocations[d_pick] -= 1
                rem += 1
            idx += 1

        for d in all_dates:
            orders_in_day = day_allocations[d]
            for _ in range(orders_in_day):
                hour = rng.randint(7, 21)
                minute = rng.randint(0, 59)
                second = rng.randint(0, 59)
                created_at = datetime(d.year, d.month, d.day, hour, minute, second, tzinfo=config.TIMEZONE)

                seq_key = (ch_code, b_code, d)
                daily_branch_order_seq[seq_key] = daily_branch_order_seq.get(seq_key, 0) + 1
                order_seq = daily_branch_order_seq[seq_key]

                d_str = d.strftime("%Y%m%d")
                if ch_code == "POS":
                    order_code = f"POS-{b_code}-{d_str}-{order_seq:04d}"
                    ext_order_id = None
                    created_by = 7 if b_code == "GM-001" else (8 if b_code == "GM-002" else 9)
                    cust = rng.choice(customers) if rng.random() < 0.40 else None
                    completed_at = created_at + timedelta(minutes=rng.randint(1, 5))
                    paid_at = created_at + timedelta(minutes=rng.randint(1, 2))
                elif ch_code == "WEB":
                    order_code = f"WEB-{b_code}-{d_str}-{order_seq:04d}"
                    ext_order_id = f"WEB-ORD-{order_id_counter:06d}"
                    created_by = None
                    cust = rng.choice(customers)
                    completed_at = created_at + timedelta(minutes=rng.randint(30, 180))
                    paid_at = created_at + timedelta(minutes=rng.randint(2, 10))
                else:  # MARKET
                    order_code = f"MKT-{b_code}-{d_str}-{order_seq:04d}"
                    ext_order_id = f"MKT-ORD-{order_id_counter:06d}"
                    created_by = None
                    cust = rng.choice(customers)
                    completed_at = created_at + timedelta(minutes=rng.randint(30, 180))
                    paid_at = created_at + timedelta(minutes=rng.randint(2, 10))

                num_items = rng.randint(config.ORDER_ITEMS_RANGE[0], config.ORDER_ITEMS_RANGE[1])

                order_items: List[OrderItem] = []
                subtotal = 0
                item_discount_total = 0
                selected_prod_ids: Set[int] = set()

                # Xác định các khuyến mại cấp ITEM đang có hiệu lực tại thời điểm, chi nhánh, kênh này
                active_item_p = [
                    p for p in item_promos_list
                    if p.start_at <= created_at <= p.end_at
                    and b_id in p.branch_ids
                    and ch_code in p.channel_codes
                ]
                promo_sku_map: Dict[int, Promotion] = {}
                for p in active_item_p:
                    for p_id in p.product_ids:
                        promo_sku_map[p_id] = p

                while len(order_items) < num_items:
                    if rng.random() < 0.80:
                        # 200 Top SKU: áp dụng BRANCH_CATEGORY_DEMAND_WEIGHTS + PROMOTION_DEMAND_LIFT
                        eligible = []
                        weights = []
                        for p in top_prods:
                            if p.id in selected_prod_ids:
                                continue
                            if stockout_schedule:
                                smode = stockout_schedule.get((b_id, p.id, d))
                                if smode == "FULL_DAY":
                                    continue
                                if smode == "MID_DAY" and hour >= 11:
                                    continue
                            base_w = config.BRANCH_CATEGORY_DEMAND_WEIGHTS[b_code][p.group_code]
                            if p.id in promo_sku_map:
                                lift = getattr(promo_sku_map[p.id], "demand_lift", 0.55)
                                w = min(base_w * (1.0 + lift), base_w * config.MAX_DEMAND_LIFT_CAP)
                            else:
                                w = base_w
                            eligible.append(p)
                            weights.append(w)
                        prod = rng.choices(eligible, weights=weights, k=1)[0]
                    else:
                        # 800 SKU còn lại: áp dụng BRANCH_CATEGORY_DEMAND_WEIGHTS
                        other_w = other_weights_by_branch[b_code]
                        while True:
                            prod = rng.choices(other_prods, weights=other_w, k=1)[0]
                            if prod.id not in selected_prod_ids:
                                break

                    selected_prod_ids.add(prod.id)

                    quantity = rng.randint(config.ITEM_QUANTITY_RANGE[0], config.ITEM_QUANTITY_RANGE[1])
                    unit_price = prod.sale_price

                    item_promo = promo_sku_map.get(prod.id)
                    line_discount = 0
                    if item_promo:
                        if item_promo.type == "PERCENT":
                            line_discount = math.floor(quantity * unit_price * item_promo.value / 100)
                        else:  # AMOUNT
                            line_discount = min(item_promo.value * quantity, unit_price * quantity)
                        item_promo.used_count += 1

                    line_total = quantity * unit_price - line_discount
                    subtotal += quantity * unit_price
                    item_discount_total += line_discount

                    order_items.append(
                        OrderItem(
                            id=item_id_counter,
                            order_id=order_id_counter,
                            product_id=prod.id,
                            quantity=quantity,
                            unit_price=unit_price,
                            discount=line_discount,
                            line_total=line_total,
                            promotion_id=item_promo.id if item_promo else None,
                        )
                    )
                    item_id_counter += 1

                order_promo_applied = None
                order_discount = 0
                amount_after_line_discount = subtotal - item_discount_total

                valid_order_promos = [
                    p for p in promotions
                    if p.level == "ORDER"
                    and p.start_at <= created_at <= p.end_at
                    and b_id in p.branch_ids
                    and ch_code in p.channel_codes
                    and (p.min_order is None or amount_after_line_discount >= p.min_order)
                    and (p.usage_limit is None or p.used_count < p.usage_limit)
                ]

                if valid_order_promos:
                    best_discount = 0
                    best_promo = None
                    for p in valid_order_promos:
                        if p.type == "PERCENT":
                            disc = math.floor(amount_after_line_discount * p.value / 100)
                            if p.max_discount:
                                disc = min(disc, p.max_discount)
                        else:  # AMOUNT
                            disc = min(p.value, amount_after_line_discount)

                        if disc > best_discount:
                            best_discount = disc
                            best_promo = p

                    if best_promo and best_discount > 0:
                        order_promo_applied = best_promo
                        order_discount = best_discount
                        best_promo.used_count += 1

                total_discount = item_discount_total + order_discount
                total_order_amount = subtotal - total_discount

                if ch_code == "POS":
                    method = rng.choices(["CASH", "CARD", "QR"], weights=[0.60, 0.20, 0.20])[0]
                else:
                    method = rng.choices(["QR", "CARD"], weights=[0.70, 0.30])[0]

                payment = Payment(
                    id=payment_id_counter,
                    order_id=order_id_counter,
                    method=method,
                    amount=total_order_amount,
                    status="PAID",
                    provider_ref=f"VNPAY-{order_id_counter:08d}" if method in ("QR", "CARD") else None,
                    paid_at=paid_at,
                )
                payment_id_counter += 1

                order = SalesOrder(
                    id=order_id_counter,
                    code=order_code,
                    channel_id=ch_id,
                    channel_code=ch_code,
                    external_order_id=ext_order_id,
                    branch_id=b_id,
                    branch_code=b_code,
                    customer_id=cust.id if cust else None,
                    promotion_id=order_promo_applied.id if order_promo_applied else None,
                    status="COMPLETED",
                    subtotal=subtotal,
                    discount=total_discount,
                    total=total_order_amount,
                    created_by=created_by,
                    created_at=created_at,
                    completed_at=completed_at,
                    items=order_items,
                    payment=payment,
                )
                orders.append(order)
                order_id_counter += 1

    return orders


# ==============================================================================
# 9. HÀM SINH PHIẾU TRẢ HÀNG (RETURNS - CHẶNG 3)
# ==============================================================================


def generate_returns(
    orders: List[SalesOrder],
    products: List[Product],
    seed: int = config.RANDOM_SEED + 101,
) -> Tuple[List[ReturnDocument], List[ReturnItem]]:
    """Sinh danh sách 150 phiếu trả hàng (1.5% số đơn đã xử lý) thỏa mãn:

    - 75 đơn trả toàn bộ: sales_order.status chuyển sang 'RETURNED'.
    - 75 đơn trả một phần: sales_order.status giữ nguyên 'COMPLETED'.
    - Đảm bảo sales_order WHERE status IN ('COMPLETED', 'RETURNED') = 10.000.
    - Thời hạn trả: <= 7 ngày kể từ completed_at và không vượt quá 31/10/2026.
    - Áp dụng công thức hoàn tiền chuẩn BA §5.2.
    - Hàng tươi sống (FRESH_FOOD) condition = 'DAMAGED'.
    - SoD: created_by (thu ngân) != approved_by (quản lý chi nhánh).
    - 10% trả chéo tại chi nhánh khác.
    """
    rng = random.Random(seed)
    prod_map = {p.id: p for p in products}

    # Chọn 150 đơn hoàn tất trước thời điểm kết thúc ít nhất 1 ngày
    candidates = [
        o for o in orders
        if o.status == "COMPLETED"
        and o.completed_at is not None
        and o.completed_at <= config.MAX_EVENT_DATETIME - timedelta(days=1)
    ]
    chosen_orders = rng.sample(candidates, 150)

    full_return_orders = chosen_orders[:75]
    partial_return_orders = chosen_orders[75:]

    returns: List[ReturnDocument] = []
    all_return_items: List[ReturnItem] = []
    ret_id_counter = 1
    ret_item_id_counter = 1

    # Map tra cứu nhân sự theo chi nhánh
    branch_cashiers = {1: 7, 2: 8, 3: 9}
    branch_managers = {1: 3, 2: 4, 3: 5}
    branch_codes = {1: "GM-001", 2: "GM-002", 3: "GM-003"}

    # 1. Sinh 75 đơn trả toàn bộ (FULL RETURNS)
    for ord in full_return_orders:
        days_later = rng.randint(1, 6)
        hours_later = rng.randint(1, 10)
        ret_dt = min(ord.completed_at + timedelta(days=days_later, hours=hours_later), config.MAX_EVENT_DATETIME)

        # 90% trả tại chi nhánh bán, 10% trả chéo chi nhánh
        if rng.random() < 0.90:
            recv_b_id = ord.branch_id
        else:
            other_b_ids = [b for b in [1, 2, 3] if b != ord.branch_id]
            recv_b_id = rng.choice(other_b_ids)

        recv_b_code = branch_codes[recv_b_id]
        created_by = branch_cashiers[recv_b_id]
        approved_by = branch_managers[recv_b_id]

        # Quyết định lý do trả hàng nhất quán: lỗi/chất lượng -> DAMAGED, đổi ý -> RESELLABLE (tránh mâu thuẫn)
        has_fresh_food = any(prod_map[it.product_id].group_code == "FRESH_FOOD" for it in ord.items)
        is_damaged_reason = has_fresh_food or (rng.random() < 0.30)
        condition = "DAMAGED" if is_damaged_reason else "RESELLABLE"
        reason = "Khách hàng trả toàn bộ đơn hàng do lỗi sản phẩm / bao bì" if is_damaged_reason else "Khách hàng đổi ý trả hàng nguyên seal"

        ret_items: List[ReturnItem] = []
        for it in ord.items:
            r_item = ReturnItem(
                id=ret_item_id_counter,
                return_id=ret_id_counter,
                order_item_id=it.id,
                quantity=it.quantity,
                condition=condition,
            )
            ret_items.append(r_item)
            all_return_items.append(r_item)
            ret_item_id_counter += 1

        ret_code = f"RET-{recv_b_code}-{ret_dt.strftime('%Y%m%d')}-{ret_id_counter:04d}"
        app_dt = max(ret_dt, min(ret_dt + timedelta(minutes=30), config.MAX_EVENT_DATETIME))

        ret_doc = ReturnDocument(
            id=ret_id_counter,
            code=ret_code,
            order_id=ord.id,
            branch_id=recv_b_id,
            reason=reason,
            refund_amount=ord.total,  # Trả toàn bộ -> hoàn 100% số tiền thực trả
            status="APPROVED",
            created_by=created_by,
            approved_by=approved_by,
            created_at=ret_dt,
            approved_at=app_dt,
            items=ret_items,
        )
        returns.append(ret_doc)
        ret_id_counter += 1

        # Chuyển trạng thái đơn gốc sang RETURNED
        ord.status = "RETURNED"

    # 2. Sinh 75 đơn trả một phần (PARTIAL RETURNS)
    for ord in partial_return_orders:
        days_later = rng.randint(1, 6)
        hours_later = rng.randint(1, 10)
        ret_dt = min(ord.completed_at + timedelta(days=days_later, hours=hours_later), config.MAX_EVENT_DATETIME)

        if rng.random() < 0.90:
            recv_b_id = ord.branch_id
        else:
            other_b_ids = [b for b in [1, 2, 3] if b != ord.branch_id]
            recv_b_id = rng.choice(other_b_ids)

        recv_b_code = branch_codes[recv_b_id]
        created_by = branch_cashiers[recv_b_id]
        approved_by = branch_managers[recv_b_id]

        # Chọn 1 hoặc tối đa 2 dòng để trả một phần
        k_items = rng.randint(1, min(2, len(ord.items) - 1))
        chosen_items = rng.sample(ord.items, k_items)

        # Quyết định lý do trả hàng nhất quán: lỗi/chất lượng -> DAMAGED, dư dùng -> RESELLABLE
        has_fresh_food = any(prod_map[it.product_id].group_code == "FRESH_FOOD" for it in chosen_items)
        is_damaged_reason = has_fresh_food or (rng.random() < 0.30)
        condition = "DAMAGED" if is_damaged_reason else "RESELLABLE"
        reason = "Trả một phần sản phẩm bị lỗi chất lượng" if is_damaged_reason else "Khách hàng đổi trả sản phẩm dư dùng"

        subtotal_after_item_disc = sum(x.line_total for x in ord.items)
        order_discount = max(0, ord.discount - sum(x.discount for x in ord.items))

        ret_items = []
        doc_refund_amount = 0

        for it in chosen_items:
            qty_return = rng.randint(1, it.quantity)

            # Công thức hoàn tiền chuẩn BA §5.2
            if subtotal_after_item_disc > 0:
                allocated_order_disc = (order_discount * it.line_total) / subtotal_after_item_disc
                line_net = it.line_total - allocated_order_disc
            else:
                line_net = it.line_total

            item_refund = math.floor((qty_return / it.quantity) * line_net)
            doc_refund_amount += item_refund

            r_item = ReturnItem(
                id=ret_item_id_counter,
                return_id=ret_id_counter,
                order_item_id=it.id,
                quantity=qty_return,
                condition=condition,
            )
            ret_items.append(r_item)
            all_return_items.append(r_item)
            ret_item_id_counter += 1

        ret_code = f"RET-{recv_b_code}-{ret_dt.strftime('%Y%m%d')}-{ret_id_counter:04d}"
        app_dt = max(ret_dt, min(ret_dt + timedelta(minutes=30), config.MAX_EVENT_DATETIME))

        ret_doc = ReturnDocument(
            id=ret_id_counter,
            code=ret_code,
            order_id=ord.id,
            branch_id=recv_b_id,
            reason=reason,
            refund_amount=doc_refund_amount,
            status="APPROVED",
            created_by=created_by,
            approved_by=approved_by,
            created_at=ret_dt,
            approved_at=app_dt,
            items=ret_items,
        )
        returns.append(ret_doc)
        ret_id_counter += 1

        # Đơn trả một phần giữ nguyên trạng thái COMPLETED (BA §5.2)

    return returns, all_return_items


# ==============================================================================
# 10. HÀM SINH ĐƠN ONLINE THẤT BẠI (FAILED ORDERS - CHẶNG 3)
# ==============================================================================


def generate_failed_online_orders(
    products: List[Product],
    customers: List[Customer],
    order_id_start: int = 10_001,
    item_id_start: int = 60_000,
    payment_id_start: int = 10_001,
    audit_id_start: int = 1,
    seed: int = config.RANDOM_SEED + 102,
) -> Tuple[List[SalesOrder], List[AuditLog]]:
    """Sinh đúng 200 đơn online thất bại (ngoài 10.000 đơn COMPLETED):

    - 80 CANCELLED: 40 chưa thanh toán, 40 đã thanh toán được hoàn (REFUNDED).
    - 80 EXPIRED: giữ chỗ 15 phút, thanh toán PENDING (quá hạn BR-03).
    - 40 REJECTED: gán chi nhánh mặc định theo BR-14, từ chối do hết tồn khả dụng (OUT_OF_STOCK).
    - Ghi nhận audit_log cho các thao tác hủy và từ chối đơn hàng.
    """
    rng = random.Random(seed)
    channel_code_to_id = {"WEB": 2, "MARKET": 3}
    default_branch_for_channel = {"WEB": 1, "MARKET": 2}
    branch_codes = {1: "GM-001", 2: "GM-002", 3: "GM-003"}

    orders: List[SalesOrder] = []
    audit_logs: List[AuditLog] = []

    cur_order_id = order_id_start
    cur_item_id = item_id_start
    cur_payment_id = payment_id_start
    cur_audit_id = audit_id_start

    # Phân bổ 200 đơn: 80 CANCELLED, 80 EXPIRED, 40 REJECTED
    STATUS_ALLOCATION = [
        ("CANCELLED", 80),
        ("EXPIRED", 80),
        ("REJECTED", 40),
    ]

    for status, count in STATUS_ALLOCATION:
        for idx in range(count):
            ch_code = "WEB" if rng.random() < 0.65 else "MARKET"
            ch_id = channel_code_to_id[ch_code]

            # Với REJECTED: chi nhánh phải là default_branch_id theo BR-14
            if status == "REJECTED":
                b_id = default_branch_for_channel[ch_code]
            else:
                b_id = rng.choices([1, 2, 3], weights=[0.45, 0.35, 0.20])[0]

            b_code = branch_codes[b_id]
            cust = rng.choice(customers)

            days_offset = rng.randint(0, 360)
            hour = rng.randint(7, 21)
            minute = rng.randint(0, 59)
            second = rng.randint(0, 59)
            created_at = config.START_DATE + timedelta(days=days_offset, hours=hour, minutes=minute, seconds=second)
            d_str = created_at.strftime("%Y%m%d")

            order_code = f"{ch_code}-{b_code}-{d_str}-F{cur_order_id:05d}"
            ext_order_id = f"{ch_code}-FAIL-{cur_order_id:06d}"

            num_items = rng.randint(config.ORDER_ITEMS_RANGE[0], config.ORDER_ITEMS_RANGE[1])
            selected_prods = rng.sample(products, num_items)

            order_items: List[OrderItem] = []
            subtotal = 0
            for prod in selected_prods:
                qty = rng.randint(config.ITEM_QUANTITY_RANGE[0], config.ITEM_QUANTITY_RANGE[1])
                u_price = prod.sale_price
                line_total = qty * u_price
                subtotal += line_total
                order_items.append(
                    OrderItem(
                        id=cur_item_id,
                        order_id=cur_order_id,
                        product_id=prod.id,
                        quantity=qty,
                        unit_price=u_price,
                        discount=0,
                        line_total=line_total,
                        promotion_id=None,
                    )
                )
                cur_item_id += 1

            total_amount = subtotal
            payment = None
            reserved_until = None

            if status == "CANCELLED":
                # 40 đơn đầu hủy khi chưa thanh toán; 40 đơn sau đã thanh toán rồi hoàn (REFUNDED)
                if idx < 40:
                    payment = None
                else:
                    paid_at = created_at + timedelta(minutes=rng.randint(2, 5))
                    payment = Payment(
                        id=cur_payment_id,
                        order_id=cur_order_id,
                        method="QR",
                        amount=total_amount,
                        status="REFUNDED",
                        provider_ref=f"VNPAY-CANC-{cur_order_id:06d}",
                        paid_at=paid_at,
                    )
                    cur_payment_id += 1

                audit_logs.append(
                    AuditLog(
                        id=cur_audit_id,
                        user_id=None,
                        action="CANCEL_ORDER",
                        entity="sales_order",
                        entity_id=str(cur_order_id),
                        detail=json.dumps({"reason": "Khách hàng yêu cầu hủy đơn hàng trực tuyến trước khi đóng gói"}),
                        created_at=created_at + timedelta(minutes=rng.randint(5, 14)),
                    )
                )
                cur_audit_id += 1

            elif status == "EXPIRED":
                # Giữ chỗ 15 phút, thanh toán PENDING nhưng khách không chuyển khoản (BR-03)
                reserved_until = created_at + timedelta(minutes=config.ONLINE_PAYMENT_TIMEOUT_MINUTES)
                payment = Payment(
                    id=cur_payment_id,
                    order_id=cur_order_id,
                    method="QR",
                    amount=total_amount,
                    status="PENDING",
                    provider_ref=None,
                    paid_at=None,
                )
                cur_payment_id += 1

            elif status == "REJECTED":
                # Không đủ tồn kho tại chi nhánh phục vụ mặc định -> từ chối đơn ngay lập tức
                reserved_until = None
                payment = None
                audit_logs.append(
                    AuditLog(
                        id=cur_audit_id,
                        user_id=None,
                        action="REJECT_ORDER",
                        entity="sales_order",
                        entity_id=str(cur_order_id),
                        detail=json.dumps({"reason": "OUT_OF_STOCK", "message": "Kho chi nhánh phục vụ không đủ tồn khả dụng cho đơn hàng"}),
                        created_at=created_at + timedelta(seconds=rng.randint(15, 60)),
                    )
                )
                cur_audit_id += 1

            order = SalesOrder(
                id=cur_order_id,
                code=order_code,
                channel_id=ch_id,
                channel_code=ch_code,
                external_order_id=ext_order_id,
                branch_id=b_id,
                branch_code=b_code,
                customer_id=cust.id,
                promotion_id=None,
                status=status,
                subtotal=subtotal,
                discount=0,
                total=total_amount,
                created_by=None,
                created_at=created_at,
                completed_at=None,
                reserved_until=reserved_until,
                items=order_items,
                payment=payment,
            )
            orders.append(order)
            cur_order_id += 1

    return orders, audit_logs


def generate_initial_inventory(
    products: List[Product],
    stockout_schedule: Dict[Tuple[int, int, date], str],
    branches: Optional[List[Branch]] = None,
    users: Optional[List[AppUser]] = None,
    orders: Optional[List[SalesOrder]] = None,
    returns: Optional[List[ReturnDocument]] = None,
    seed: int = config.RANDOM_SEED + 107,
) -> Tuple[List[Inventory], List[InventoryTransaction], List[StockDocument], List[StockDocumentItem]]:
    """Sinh dữ liệu tồn kho khởi tạo cho 1.000 sản phẩm × 3 chi nhánh (3.000 bản ghi)
    kèm phiếu nhập kho đầu kỳ (stock_document), chi tiết dòng phiếu (stock_document_item)
    và sổ kho IMPORT (inventory_transaction) tương ứng (Issue #107).

    Quy tắc:
    - Mô hình B: Tồn kho khởi tạo tại mốc chốt sổ demo 31/10/2026.
    - Điểm đặt hàng lại (ROP) tính từ bán ròng (qty_net) 28 ngày cuối theo công thức BA §8.2:
      ROP = ceil(d_bar * L + SS), SS = ceil(z * sigma_d * sqrt(L)), L = 2 ngày, z = 1.65.
    - Phân bổ trạng thái demo: ~1% OUT (hết hàng: on_hand=0), ~5% LOW (tồn thấp: 0 < on_hand <= ROP),
      ~94% OK (bình thường: on_hand > ROP). Mỗi chi nhánh có ít nhất 3 OUT và 10 LOW; ưu tiên nhóm Top-200 SKU.
    - Mốc neo demo TC-DASH-01: Sữa tươi TH True Milk 1L (SUA-TH-1L) tại GM-001 có on_hand = 35, reserved = 0,
      ROP = 34 (trạng thái OK sát ngưỡng ROP để demo bán 6 cái tụt xuống 29 kích hoạt cảnh báo).
    - Cột version: 1 nếu có giao dịch IMPORT (on_hand > 0), 0 nếu on_hand = 0 (không có giao dịch).
    - Cột updated_at: thời điểm IMPORT (END_DATE - 15 phút) hoặc END_DATE cho dòng không giao dịch.
    - Dòng on_hand = 0: KHÔNG tạo stock_document_item và KHÔNG tạo giao dịch IMPORT.
    - Người lập và duyệt phiếu kho: tra cứu động theo chi nhánh (thủ kho lập, quản lý duyệt, SoD BR-13).
    - Dùng riêng rng = random.Random(seed), không làm thay đổi luồng RNG của các chặng khác.
    """
    if branches is None:
        branches = generate_branches()
    if users is None:
        users = generate_demo_users()
    if orders is None:
        orders = []
    if returns is None:
        returns = []

    rng = random.Random(seed)
    end_date = config.END_DATE.date()
    import_time = config.END_DATE - timedelta(minutes=15)

    # 1. Tra cứu động thủ kho và quản lý theo chi nhánh (SoD BR-13: created_by != approved_by)
    storekeepers: Dict[int, int] = {}
    managers: Dict[int, int] = {}
    for b in branches:
        for u in users:
            if b.id in u.branch_ids:
                if u.role == "STOREKEEPER" and b.id not in storekeepers:
                    storekeepers[b.id] = u.id
                elif u.role == "BRANCH_MANAGER" and b.id not in managers:
                    managers[b.id] = u.id
        # Fallback an toàn nếu không tìm thấy
        if b.id not in storekeepers:
            storekeepers[b.id] = 10 + (b.id - 1)
        if b.id not in managers:
            managers[b.id] = 3 + (b.id - 1)

    # 2. Tạo 3 phiếu nhập kho khởi tạo đầu kỳ (mỗi chi nhánh 1 phiếu)
    stock_documents: List[StockDocument] = []
    for b in branches:
        doc = StockDocument(
            id=b.id,
            code=f"NK-INIT-{b.code.replace('-', '')}",
            type="IMPORT",
            branch_id=b.id,
            to_branch_id=None,
            status="APPROVED",
            supplier="Khởi tạo tồn đầu kỳ hệ thống",
            reason="Khởi tạo tồn đầu kỳ hệ thống",
            created_by=storekeepers[b.id],
            approved_by=managers[b.id],
            received_by=None,
            created_at=import_time,
            approved_at=import_time,
            received_at=None,
        )
        stock_documents.append(doc)
    doc_by_branch = {d.branch_id: d for d in stock_documents}

    # 3. Tính toán bán ròng 28 ngày cuối (04/10/2026 - 31/10/2026) cho từng cặp (branch_id, product_id)
    start_28_date = end_date - timedelta(days=config.ROP_HISTORY_DAYS - 1)
    history_dates = [start_28_date + timedelta(days=i) for i in range(config.ROP_HISTORY_DAYS)]

    daily_sales: Dict[Tuple[int, int, date], int] = defaultdict(int)
    order_item_to_product: Dict[int, int] = {}
    for ord in orders:
        if ord.status in ("COMPLETED", "RETURNED"):
            o_date = (ord.completed_at or ord.created_at).date()
            if start_28_date <= o_date <= end_date:
                for it in ord.items:
                    daily_sales[(ord.branch_id, it.product_id, o_date)] += it.quantity
        for it in ord.items:
            order_item_to_product[it.id] = it.product_id

    daily_returns: Dict[Tuple[int, int, date], int] = defaultdict(int)
    for ret in returns:
        if ret.status == "APPROVED":
            r_date = ret.created_at.date()
            if start_28_date <= r_date <= end_date:
                for ri in getattr(ret, "items", []):
                    p_id = order_item_to_product.get(ri.order_item_id)
                    if p_id is not None:
                        daily_returns[(ret.branch_id, p_id, r_date)] += ri.quantity

    def calculate_rop(b_id: int, p_id: int) -> int:
        quantities = []
        for d in history_dates:
            sold = daily_sales.get((b_id, p_id, d), 0)
            ret_qty = daily_returns.get((b_id, p_id, d), 0)
            net_qty = max(0, sold - ret_qty)
            quantities.append(net_qty)

        total_qty = sum(quantities)
        if total_qty == 0:
            return config.ROP_MINIMUM

        n = len(quantities)  # 28
        d_bar = total_qty / n
        variance = sum((q - d_bar) ** 2 for q in quantities) / (n - 1)
        sigma_d = math.sqrt(variance)
        ss = math.ceil(config.SERVICE_LEVEL_Z * sigma_d * math.sqrt(config.LEAD_TIME_DAYS))
        rop = math.ceil(d_bar * config.LEAD_TIME_DAYS + ss)
        return max(rop, config.ROP_MINIMUM)

    # 4. Phân loại sản phẩm mục tiêu (OUT ~1% = 10 SKU/CN, LOW ~5% = 50 SKU/CN, ưu tiên Top-200 SKU)
    out_skus_by_branch: Dict[int, Set[int]] = {}
    low_skus_by_branch: Dict[int, Set[int]] = {}

    for b in branches:
        active_products = [p for p in products if p.is_active]

        # Xác định sản phẩm neo tại chi nhánh (nếu có) để loại trừ khỏi nhóm ngẫu nhiên
        anchor_p_id = None
        if b.code == config.ANCHOR_MILK_BRANCH_CODE:
            for p in active_products:
                if p.sku == config.ANCHOR_MILK_SKU:
                    anchor_p_id = p.id
                    break

        # Ứng viên OUT (đúng 10 SKU/chi nhánh, ưu tiên lịch stockout FULL_DAY ngày cuối)
        out_candidates: List[int] = []
        for p in active_products:
            if anchor_p_id and p.id == anchor_p_id:
                continue
            key = (b.id, p.id, end_date)
            if key in stockout_schedule and stockout_schedule[key] == "FULL_DAY":
                out_candidates.append(p.id)

        # Bổ sung từ nhóm Top-200 SKU nếu chưa đủ 10
        remaining_top = [
            p.id for p in active_products
            if p.is_top_sku and (not anchor_p_id or p.id != anchor_p_id) and p.id not in out_candidates
        ]
        rng.shuffle(remaining_top)
        for pid in remaining_top:
            if len(out_candidates) >= 10:
                break
            out_candidates.append(pid)

        # Bổ sung từ SKU thường nếu vẫn chưa đủ 10
        if len(out_candidates) < 10:
            remaining_normal = [
                p.id for p in active_products
                if not p.is_top_sku and (not anchor_p_id or p.id != anchor_p_id) and p.id not in out_candidates
            ]
            rng.shuffle(remaining_normal)
            for pid in remaining_normal:
                if len(out_candidates) >= 10:
                    break
                out_candidates.append(pid)

        branch_out_ids = set(out_candidates[:10])
        out_skus_by_branch[b.id] = branch_out_ids

        # Ứng viên LOW (đúng 50 SKU/chi nhánh, ưu tiên nhóm Top-200 SKU còn lại)
        low_candidates: List[int] = []
        low_top_pool = [
            p.id for p in active_products
            if p.is_top_sku and (not anchor_p_id or p.id != anchor_p_id) and p.id not in branch_out_ids
        ]
        rng.shuffle(low_top_pool)
        low_candidates.extend(low_top_pool)

        if len(low_candidates) < 50:
            low_normal_pool = [
                p.id for p in active_products
                if not p.is_top_sku and (not anchor_p_id or p.id != anchor_p_id) and p.id not in branch_out_ids
            ]
            rng.shuffle(low_normal_pool)
            for pid in low_normal_pool:
                if len(low_candidates) >= 50:
                    break
                low_candidates.append(pid)

        branch_low_ids = set(low_candidates[:50])
        low_skus_by_branch[b.id] = branch_low_ids

    # 5. Khởi tạo danh sách Inventory, StockDocumentItem và InventoryTransaction
    inventories: List[Inventory] = []
    stock_document_items: List[StockDocumentItem] = []
    transactions: List[InventoryTransaction] = []

    tx_id = 1
    doc_item_id = 1

    for b in branches:
        doc = doc_by_branch[b.id]
        out_ids = out_skus_by_branch[b.id]
        low_ids = low_skus_by_branch[b.id]

        for p in products:
            is_anchor = (b.code == config.ANCHOR_MILK_BRANCH_CODE and p.sku == config.ANCHOR_MILK_SKU)
            if is_anchor:
                rop = config.ANCHOR_MILK_ROP
                on_hand = config.ANCHOR_MILK_ON_HAND  # 35
                reserved = config.ANCHOR_MILK_RESERVED  # 0
            elif p.id in out_ids:
                rop = calculate_rop(b.id, p.id)
                on_hand = 0
                reserved = 0
            elif p.id in low_ids:
                rop = calculate_rop(b.id, p.id)
                # 0 < on_hand <= ROP (trạng thái LOW)
                on_hand = rng.randint(1, max(1, rop))
                reserved = 0
            else:
                rop = calculate_rop(b.id, p.id)
                # on_hand > ROP (trạng thái OK)
                if p.is_top_sku:
                    on_hand = rop + rng.randint(15, 60)
                else:
                    on_hand = rop + rng.randint(5, 30)
                reserved = 0

            version = 1 if on_hand > 0 else 0
            updated_at = import_time if on_hand > 0 else config.END_DATE

            inv = Inventory(
                branch_id=b.id,
                product_id=p.id,
                on_hand=on_hand,
                reserved=reserved,
                reorder_point=rop,
                version=version,
                updated_at=updated_at,
            )
            inventories.append(inv)

            # Chỉ tạo stock_document_item và inventory_transaction khi on_hand > 0
            if on_hand > 0:
                doc_item = StockDocumentItem(
                    id=doc_item_id,
                    document_id=doc.id,
                    product_id=p.id,
                    quantity=on_hand,
                    received_quantity=None,
                    counted_quantity=None,
                    unit_cost=p.cost_price,
                )
                stock_document_items.append(doc_item)
                doc_item_id += 1

                tx = InventoryTransaction(
                    id=tx_id,
                    branch_id=b.id,
                    product_id=p.id,
                    type="IMPORT",
                    delta_on_hand=on_hand,
                    on_hand_after=on_hand,
                    delta_reserved=0,
                    reserved_after=0,
                    ref_type="STOCK_DOCUMENT",
                    ref_id=doc.id,
                    created_by=doc.created_by,
                    created_at=import_time,
                )
                transactions.append(tx)
                tx_id += 1

    return inventories, transactions, stock_documents, stock_document_items



# ==============================================================================
# 11. HÀM TÍNH THỐNG KÊ (SALES STATISTICS & SPARSITY)
# ==============================================================================


def compute_sales_statistics(
    orders: List[SalesOrder],
    products: List[Product],
    top_sku_ids: Set[int],
) -> Dict[str, Any]:
    """Tính toán các chỉ số thống kê bán hàng phục vụ kiểm định và báo cáo:

    - Tỷ lệ Pareto theo số lượng bán (qty_sold).
    - Đơn vị bán/ngày (units/day) của 200 SKU top đầu (chuỗi và từng chi nhánh).
    - Tỷ lệ % ô daily_sales bằng 0 (sparsity).
    """
    total_qty_all = 0
    total_qty_top = 0

    daily_sales_cells: Set[Tuple[date, int, int]] = set()
    sku_qty_sold: Dict[int, int] = {p.id: 0 for p in products}
    branch_top_qty: Dict[int, int] = {1: 0, 2: 0, 3: 0}

    for order in orders:
        if order.status not in ("COMPLETED", "RETURNED"):
            continue

        o_date = order.created_at.date()
        b_id = order.branch_id
        for item in order.items:
            qty = item.quantity
            p_id = item.product_id

            total_qty_all += qty
            sku_qty_sold[p_id] += qty
            if p_id in top_sku_ids:
                total_qty_top += qty
                branch_top_qty[b_id] += qty

            daily_sales_cells.add((o_date, b_id, p_id))

    pareto_share = (total_qty_top / total_qty_all) if total_qty_all > 0 else 0.0

    days_count = 365
    top_units_per_day_per_sku_chain = (total_qty_top / (len(top_sku_ids) * days_count)) if top_sku_ids else 0.0
    top_units_per_day_per_sku_branch = (total_qty_top / (len(top_sku_ids) * 3 * days_count)) if top_sku_ids else 0.0

    total_possible_cells = 365 * 3 * config.TOTAL_SKU_COUNT
    active_cells = len(daily_sales_cells)
    zero_cells = total_possible_cells - active_cells
    zero_cells_pct = (zero_cells / total_possible_cells) * 100.0

    return {
        "total_qty_all": total_qty_all,
        "total_qty_top": total_qty_top,
        "pareto_share": pareto_share,
        "top_units_per_day_per_sku_chain": top_units_per_day_per_sku_chain,
        "top_units_per_day_per_sku_branch": top_units_per_day_per_sku_branch,
        "branch_top_qty": branch_top_qty,
        "total_possible_cells": total_possible_cells,
        "active_cells": active_cells,
        "zero_cells": zero_cells,
        "zero_cells_pct": zero_cells_pct,
    }


# ==============================================================================
# 12. HÀM XUẤT LỊCH STOCKOUT (JSON EXPORTER)
# ==============================================================================


def export_stockout_schedule(
    schedule: Dict[Tuple[int, int, date], str],
    products: List[Product],
    filepath: Optional[Path] = None,
    stream: Optional[Any] = None,
) -> None:
    """Xuất file JSON lịch stockout phục vụ task #107 và #109 (hỗ trợ ghi ra file hoặc stream in-memory)."""
    prod_map = {p.id: p for p in products}
    branch_codes = {1: "GM-001", 2: "GM-002", 3: "GM-003"}

    records = []
    for (b_id, p_id, d), mode in sorted(schedule.items(), key=lambda x: (x[0][0], x[0][1], x[0][2])):
        p = prod_map.get(p_id)
        records.append({
            "branch_id": b_id,
            "branch_code": branch_codes.get(b_id, f"GM-{b_id:03d}"),
            "product_id": p_id,
            "sku": p.sku if p else f"SKU-{p_id}",
            "date": d.isoformat(),
            "mode": mode,
        })

    if stream is not None:
        json.dump(records, stream, ensure_ascii=False, indent=2)
    else:
        if filepath is None:
            filepath = Path(__file__).resolve().parent.parent / "data" / "stockout_schedule.json"
        filepath.parent.mkdir(parents=True, exist_ok=True)
        with open(filepath, "w", encoding="utf-8", newline="\n") as f:
            json.dump(records, f, ensure_ascii=False, indent=2)


# ==============================================================================
# 13. HÀM XUẤT FILE SQL SEED (CHẶNG 4 - SQL EXPORTER)
# ==============================================================================


def _sql_str(val: Optional[str]) -> str:
    if val is None:
        return "NULL"
    escaped = val.replace("'", "''")
    return f"'{escaped}'"


def _sql_int(val: Optional[int]) -> str:
    return str(val) if val is not None else "NULL"


def _sql_bool(val: bool) -> str:
    return "true" if val else "false"


def _sql_dt(val: Optional[datetime]) -> str:
    if val is None:
        return "NULL"
    return f"'{val.strftime('%Y-%m-%d %H:%M:%S%z')}'"


def _write_sql_statements(
    f: Any,
    branches: List[Branch],
    categories: List[Category],
    products: List[Product],
    users: List[AppUser],
    customers: List[Customer],
    promotions: List[Promotion],
    orders: List[SalesOrder],
    returns: List[ReturnDocument],
    audit_logs: List[AuditLog],
    stock_documents: Optional[List[StockDocument]] = None,
    stock_document_items: Optional[List[StockDocumentItem]] = None,
    inventories: Optional[List[Inventory]] = None,
    inventory_transactions: Optional[List[InventoryTransaction]] = None,
) -> None:
    """Ghi toàn bộ câu lệnh SQL vào file stream với định dạng chuẩn newline='\\n'."""
    # 1. Header cảnh báo
    f.write("-- ==============================================================================\n")
    f.write("-- [CẢNH BÁO: DỮ LIỆU SẼ BỊ PHÁ HỦY VÀ XÓA HOÀN TOÀN - CHỈ CHẠY TRÊN DEV/STAGING]\n")
    f.write("-- File seed dữ liệu mẫu 12 tháng lịch sử GreenMart (Issue #106 / AI-01)\n")
    f.write("-- Thời gian dữ liệu: 01/11/2025 đến 31/10/2026 (+07:00)\n")
    f.write("-- ==============================================================================\n\n")
    f.write("BEGIN;\n\n")

    # 2. Xóa sạch dữ liệu cũ nhưng bảo toàn 3 kênh bán của V3
    f.write("-- 1. Tạm gỡ default_branch_id trên sales_channel để tránh vướng khóa ngoại khi xóa branch\n")
    f.write("UPDATE sales_channel SET default_branch_id = NULL;\n\n")

    f.write("-- 2. TRUNCATE các bảng dữ liệu nghiệp vụ theo thứ tự phụ thuộc\n")
    f.write("TRUNCATE TABLE\n")
    f.write("    audit_log,\n")
    f.write("    forecast_result,\n")
    f.write("    daily_sales,\n")
    f.write("    stock_document_item,\n")
    f.write("    stock_document,\n")
    f.write("    inventory_transaction,\n")
    f.write("    inventory,\n")
    f.write("    return_item,\n")
    f.write("    return_document,\n")
    f.write("    payment,\n")
    f.write("    order_item,\n")
    f.write("    sales_order,\n")
    f.write("    promotion_product,\n")
    f.write("    promotion_channel,\n")
    f.write("    promotion_branch,\n")
    f.write("    promotion,\n")
    f.write("    customer,\n")
    f.write("    product,\n")
    f.write("    category,\n")
    f.write("    user_branch,\n")
    f.write("    app_user,\n")
    f.write("    channel_branch\n")
    f.write("RESTART IDENTITY CASCADE;\n\n")

    f.write("-- 3. Xóa dữ liệu chi nhánh và đặt lại sequence\n")
    f.write("DELETE FROM branch;\n")
    f.write("ALTER SEQUENCE branch_id_seq RESTART WITH 1;\n\n")

    # 3. Insert Branch
    f.write("-- 4. Khởi tạo 3 chi nhánh\n")
    f.write("INSERT INTO branch (id, code, name, address, is_active) VALUES\n")
    b_lines = [
        f"({b.id}, {_sql_str(b.code)}, {_sql_str(b.name)}, {_sql_str(b.address)}, {_sql_bool(b.is_active)})"
        for b in branches
    ]
    f.write(",\n".join(b_lines) + ";\n\n")

    # 4. Update sales_channel & insert channel_branch
    f.write("-- 5. Cập nhật chi nhánh mặc định cho kênh online và phân quyền chi nhánh kênh\n")
    f.write("UPDATE sales_channel SET default_branch_id = 1 WHERE code = 'WEB';\n")
    f.write("UPDATE sales_channel SET default_branch_id = 2 WHERE code = 'MARKET';\n")
    f.write("UPDATE sales_channel SET default_branch_id = NULL WHERE code = 'POS';\n\n")

    f.write("INSERT INTO channel_branch (channel_id, branch_id)\n")
    f.write("SELECT c.id, b.id FROM sales_channel c CROSS JOIN branch b;\n\n")

    # 5. Insert Categories
    f.write("-- 6. Danh mục sản phẩm (18 danh mục: 3 cha, 15 con)\n")
    f.write("INSERT INTO category (id, name, parent_id) VALUES\n")
    c_lines = [
        f"({c.id}, {_sql_str(c.name)}, {_sql_int(c.parent_id)})"
        for c in categories
    ]
    f.write(",\n".join(c_lines) + ";\n\n")

    # 6. Insert Products (batch 500)
    f.write("-- 7. Danh mục 1.000 sản phẩm\n")
    batch_size = 500
    for i in range(0, len(products), batch_size):
        batch = products[i:i + batch_size]
        f.write("INSERT INTO product (id, sku, barcode, name, unit, category_id, sale_price, cost_price, is_active) VALUES\n")
        p_lines = [
            f"({p.id}, {_sql_str(p.sku)}, {_sql_str(p.barcode)}, {_sql_str(p.name)}, {_sql_str(p.unit)}, {p.category_id}, {p.sale_price}, {p.cost_price}, {_sql_bool(p.is_active)})"
            for p in batch
        ]
        f.write(",\n".join(p_lines) + ";\n\n")

    # 7. Insert App Users & User Branches
    f.write("-- 8. Tài khoản demo (12 người dùng) và phân quyền chi nhánh\n")
    f.write("INSERT INTO app_user (id, username, password_hash, full_name, role, is_active) VALUES\n")
    u_lines = [
        f"({u.id}, {_sql_str(u.username)}, {_sql_str(u.password_hash)}, {_sql_str(u.full_name)}, {_sql_str(u.role)}, {_sql_bool(u.is_active)})"
        for u in users
    ]
    f.write(",\n".join(u_lines) + ";\n\n")

    f.write("INSERT INTO user_branch (user_id, branch_id) VALUES\n")
    ub_lines = []
    for u in users:
        for b_id in u.branch_ids:
            ub_lines.append(f"({u.id}, {b_id})")
    f.write(",\n".join(ub_lines) + ";\n\n")

    # 8. Insert Customers (batch 500)
    f.write("-- 9. Danh sách khách hàng thân thiết (1.500 khách hàng)\n")
    for i in range(0, len(customers), batch_size):
        batch = customers[i:i + batch_size]
        f.write("INSERT INTO customer (id, phone, name, email, created_at) VALUES\n")
        cust_lines = [
            f"({c.id}, {_sql_str(c.phone)}, {_sql_str(c.name)}, {_sql_str(c.email)}, {_sql_dt(c.created_at)})"
            for c in batch
        ]
        f.write(",\n".join(cust_lines) + ";\n\n")

    # 9. Insert Promotions & Promotion relations
    f.write("-- 10. Chiến dịch khuyến mại và quan hệ ràng buộc\n")
    f.write("INSERT INTO promotion (id, code, type, level, value, max_discount, min_order, start_at, end_at, usage_limit, used_count, status) VALUES\n")
    promo_lines = [
        f"({p.id}, {_sql_str(p.code)}, {_sql_str(p.type)}, {_sql_str(p.level)}, {p.value}, {_sql_int(p.max_discount)}, {_sql_int(p.min_order)}, {_sql_dt(p.start_at)}, {_sql_dt(p.end_at)}, {_sql_int(p.usage_limit)}, {p.used_count}, {_sql_str(p.status)})"
        for p in promotions
    ]
    f.write(",\n".join(promo_lines) + ";\n\n")

    # promotion_branch
    pb_lines = []
    for p in promotions:
        for b_id in p.branch_ids:
            pb_lines.append(f"({p.id}, {b_id})")
    if pb_lines:
        f.write("INSERT INTO promotion_branch (promotion_id, branch_id) VALUES\n")
        f.write(",\n".join(pb_lines) + ";\n\n")

    # promotion_channel
    channel_code_to_id = {"POS": 1, "WEB": 2, "MARKET": 3}
    pch_lines = []
    for p in promotions:
        for c_code in p.channel_codes:
            ch_id = channel_code_to_id[c_code]
            pch_lines.append(f"({p.id}, {ch_id})")
    if pch_lines:
        f.write("INSERT INTO promotion_channel (promotion_id, channel_id) VALUES\n")
        f.write(",\n".join(pch_lines) + ";\n\n")

    # promotion_product
    pp_lines = []
    for p in promotions:
        for prod_id in p.product_ids:
            pp_lines.append(f"({p.id}, {prod_id})")
    if pp_lines:
        f.write("INSERT INTO promotion_product (promotion_id, product_id) VALUES\n")
        f.write(",\n".join(pp_lines) + ";\n\n")

    # 10. Insert Sales Orders (batch 500)
    f.write("-- 11. Đơn hàng (10.000 đơn COMPLETED/RETURNED + 200 đơn online lỗi = 10.200 đơn)\n")
    for i in range(0, len(orders), batch_size):
        batch = orders[i:i + batch_size]
        f.write("INSERT INTO sales_order (id, code, channel_id, external_order_id, branch_id, customer_id, promotion_id, status, subtotal, discount, total, reserved_until, created_by, created_at, completed_at) VALUES\n")
        o_lines = [
            f"({o.id}, {_sql_str(o.code)}, {o.channel_id}, {_sql_str(o.external_order_id)}, {o.branch_id}, {_sql_int(o.customer_id)}, {_sql_int(o.promotion_id)}, {_sql_str(o.status)}, {o.subtotal}, {o.discount}, {o.total}, {_sql_dt(o.reserved_until)}, {_sql_int(o.created_by)}, {_sql_dt(o.created_at)}, {_sql_dt(o.completed_at)})"
            for o in batch
        ]
        f.write(",\n".join(o_lines) + ";\n\n")

    # 11. Insert Order Items (batch 500)
    f.write("-- 12. Dòng hàng đơn hàng (order_item)\n")
    all_items: List[OrderItem] = []
    for o in orders:
        all_items.extend(o.items)

    for i in range(0, len(all_items), batch_size):
        batch = all_items[i:i + batch_size]
        f.write("INSERT INTO order_item (id, order_id, product_id, quantity, unit_price, discount, line_total, promotion_id) VALUES\n")
        it_lines = [
            f"({it.id}, {it.order_id}, {it.product_id}, {it.quantity}, {it.unit_price}, {it.discount}, {it.line_total}, {_sql_int(it.promotion_id)})"
            for it in batch
        ]
        f.write(",\n".join(it_lines) + ";\n\n")

    # 12. Insert Payments (batch 500)
    f.write("-- 13. Giao dịch thanh toán (payment)\n")
    payments: List[Payment] = [o.payment for o in orders if o.payment is not None]
    for i in range(0, len(payments), batch_size):
        batch = payments[i:i + batch_size]
        f.write("INSERT INTO payment (id, order_id, method, amount, status, provider_ref, paid_at) VALUES\n")
        pay_lines = [
            f"({p.id}, {p.order_id}, {_sql_str(p.method)}, {p.amount}, {_sql_str(p.status)}, {_sql_str(p.provider_ref)}, {_sql_dt(p.paid_at)})"
            for p in batch
        ]
        f.write(",\n".join(pay_lines) + ";\n\n")

    # 13. Insert Return Documents & Return Items
    f.write("-- 14. Phiếu đổi trả hàng (150 phiếu) và dòng hàng trả lại (tương thích migration V4 #215)\n")
    if returns:
        f.write("INSERT INTO return_document (id, code, order_id, branch_id, reason, refund_amount, status, created_by, approved_by, created_at, approved_at) VALUES\n")
        ret_lines = [
            f"({r.id}, {_sql_str(r.code)}, {r.order_id}, {r.branch_id}, {_sql_str(r.reason)}, {r.refund_amount}, {_sql_str(r.status)}, {r.created_by}, {_sql_int(r.approved_by)}, {_sql_dt(r.created_at)}, {_sql_dt(r.approved_at)})"
            for r in returns
        ]
        f.write(",\n".join(ret_lines) + ";\n\n")

        all_ret_items: List[ReturnItem] = []
        for r in returns:
            all_ret_items.extend(r.items)

        for i in range(0, len(all_ret_items), batch_size):
            batch = all_ret_items[i:i + batch_size]
            f.write("INSERT INTO return_item (id, return_id, order_item_id, quantity, condition) VALUES\n")
            ri_lines = [
                f"({ri.id}, {ri.return_id}, {ri.order_item_id}, {ri.quantity}, {_sql_str(ri.condition)})"
                for ri in batch
            ]
            f.write(",\n".join(ri_lines) + ";\n\n")

    # 14. Insert Audit Logs
    f.write("-- 15. Nhật ký kiểm toán đơn hàng online hủy và từ chối\n")
    if audit_logs:
        f.write("INSERT INTO audit_log (id, user_id, action, entity, entity_id, detail, created_at) VALUES\n")
        log_lines = [
            f"({l.id}, {_sql_int(l.user_id)}, {_sql_str(l.action)}, {_sql_str(l.entity)}, {_sql_str(l.entity_id)}, {_sql_str(l.detail)}::jsonb, {_sql_dt(l.created_at)})"
            for l in audit_logs
        ]
        f.write(",\n".join(log_lines) + ";\n\n")

    # 15. Insert Stock Documents & Stock Document Items (Issue #107)
    if stock_documents:
        f.write("-- 16. Phiếu nhập kho khởi tạo đầu kỳ (stock_document: 3 phiếu cho 3 chi nhánh, tương thích V4)\n")
        doc_lines = [
            f"({d.id}, {_sql_str(d.code)}, {_sql_str(d.type)}, {d.branch_id}, {_sql_int(d.to_branch_id)}, {_sql_str(d.status)}, {_sql_str(d.supplier)}, {_sql_str(d.reason)}, {d.created_by}, {_sql_int(d.approved_by)}, {_sql_int(d.received_by)}, {_sql_dt(d.created_at)}, {_sql_dt(d.approved_at)}, {_sql_dt(d.received_at)})"
            for d in stock_documents
        ]
        f.write("INSERT INTO stock_document (id, code, type, branch_id, to_branch_id, status, supplier, reason, created_by, approved_by, received_by, created_at, approved_at, received_at) VALUES\n")
        f.write(",\n".join(doc_lines) + ";\n\n")

    if stock_document_items:
        f.write(f"-- 17. Chi tiết dòng phiếu nhập kho (stock_document_item: {len(stock_document_items)} dòng hàng có on_hand > 0)\n")
        for i in range(0, len(stock_document_items), batch_size):
            batch = stock_document_items[i:i + batch_size]
            item_lines = [
                f"({di.id}, {di.document_id}, {di.product_id}, {di.quantity}, {_sql_int(di.received_quantity)}, {_sql_int(di.counted_quantity)}, {_sql_int(di.unit_cost)})"
                for di in batch
            ]
            f.write("INSERT INTO stock_document_item (id, document_id, product_id, quantity, received_quantity, counted_quantity, unit_cost) VALUES\n")
            f.write(",\n".join(item_lines) + ";\n\n")

    # 16. Insert Inventory & Inventory Transactions
    if inventories:
        f.write("-- 18. Tồn kho khởi tạo (3.000 bản ghi: 3 chi nhánh x 1.000 sản phẩm)\n")
        batch_size = 500
        for i in range(0, len(inventories), batch_size):
            batch = inventories[i : i + batch_size]
            val_strs = [
                f"({inv.branch_id}, {inv.product_id}, {inv.on_hand}, {inv.reserved}, {inv.reorder_point}, {inv.version}, {_sql_dt(inv.updated_at)})"
                for inv in batch
            ]
            f.write("INSERT INTO inventory (branch_id, product_id, on_hand, reserved, reorder_point, version, updated_at) VALUES\n")
            f.write(",\n".join(val_strs) + ";\n\n")

    if inventory_transactions:
        f.write(f"-- 19. Sổ giao dịch kho IMPORT ban đầu ({len(inventory_transactions)} bản ghi cho các dòng có on_hand > 0)\n")
        batch_size = 500
        for i in range(0, len(inventory_transactions), batch_size):
            batch = inventory_transactions[i : i + batch_size]
            val_strs = [
                f"({tx.id}, {tx.branch_id}, {tx.product_id}, {_sql_str(tx.type)}, {tx.delta_on_hand}, {tx.on_hand_after}, {tx.delta_reserved}, {tx.reserved_after}, {_sql_str(tx.ref_type)}, {tx.ref_id}, {_sql_int(tx.created_by)}, {_sql_dt(tx.created_at)})"
                for tx in batch
            ]
            f.write("INSERT INTO inventory_transaction (id, branch_id, product_id, type, delta_on_hand, on_hand_after, delta_reserved, reserved_after, ref_type, ref_id, created_by, created_at) VALUES\n")
            f.write(",\n".join(val_strs) + ";\n\n")

    # 17. Reset sequences cho mọi bảng
    f.write("-- 20. Thiết lập lại sequence cho tất cả các bảng vừa seed\n")
    tables_to_reset = [
        "branch",
        "category",
        "product",
        "app_user",
        "customer",
        "promotion",
        "sales_order",
        "order_item",
        "payment",
        "return_document",
        "return_item",
        "stock_document",
        "stock_document_item",
        "inventory_transaction",
        "audit_log",
    ]
    for tbl in tables_to_reset:
        f.write(f"SELECT setval(pg_get_serial_sequence('{tbl}', 'id'), COALESCE((SELECT MAX(id) FROM {tbl}), 1));\n")

    f.write("\nCOMMIT;\n")


def export_to_sql(
    branches: List[Branch],
    categories: List[Category],
    products: List[Product],
    users: List[AppUser],
    customers: List[Customer],
    promotions: List[Promotion],
    orders: List[SalesOrder],
    returns: List[ReturnDocument],
    audit_logs: List[AuditLog],
    stock_documents: Optional[List[StockDocument]] = None,
    stock_document_items: Optional[List[StockDocumentItem]] = None,
    inventories: Optional[List[Inventory]] = None,
    inventory_transactions: Optional[List[InventoryTransaction]] = None,
    filepath: Optional[Path] = None,
    stream: Optional[Any] = None,
) -> None:
    """Xuất toàn bộ dữ liệu mẫu 12 tháng lịch sử thành file SQL hoàn chỉnh (idempotent, newline='\\n')."""
    if stream is not None:
        _write_sql_statements(stream, branches, categories, products, users, customers, promotions, orders, returns, audit_logs, stock_documents, stock_document_items, inventories, inventory_transactions)
    else:
        if filepath is None:
            filepath = Path(__file__).resolve().parent.parent / "data" / "seed_data.sql"
        filepath.parent.mkdir(parents=True, exist_ok=True)
        with open(filepath, "w", encoding="utf-8", newline="\n") as f:
            _write_sql_statements(f, branches, categories, products, users, customers, promotions, orders, returns, audit_logs, stock_documents, stock_document_items, inventories, inventory_transactions)


# ==============================================================================
# 14. HÀM MAIN THỰC THI TOÀN BỘ TIẾN TRÌNH SINH DỮ LIỆU
# ==============================================================================


def main() -> None:
    """Hàm chạy toàn bộ luồng sinh dữ liệu và xuất file SQL + JSON."""
    print("=" * 70)
    print("GreenMart Demo Data Generator (Issue #106 / AI-01)")
    print("=" * 70)

    # Chặng 1
    print("[1/5] Sinh Master Data (Chi nhánh, Danh mục, Sản phẩm, Tài khoản)...")
    branches = generate_branches()
    categories = generate_categories()
    products, top_sku_ids = generate_products()
    users = generate_demo_users()

    # Bước 0: Stockout
    print("[2/5] Sinh Lịch Stockout giả lập (600 cặp SKU×CN, 2-3% ngày)...")
    stockout_schedule = get_stockout_schedule(products, top_sku_ids)

    # Chặng 2: Khách, Khuyến mại, 10.000 đơn xử lý
    print("[3/5] Sinh Khách hàng, Khuyến mại và 10.000 đơn hàng xử lý...")
    customers = generate_customers(count=1500)
    promotions = generate_promotions(products)
    processed_orders = generate_orders_and_items(
        products=products,
        top_sku_ids=top_sku_ids,
        customers=customers,
        promotions=promotions,
        stockout_schedule=stockout_schedule,
    )

    # Chặng 3: Đổi trả và Đơn online thất bại
    print("[4/5] Sinh 150 phiếu đổi trả và 200 đơn online thất bại...")
    returns, return_items = generate_returns(processed_orders, products)
    failed_orders, audit_logs = generate_failed_online_orders(
        products=products,
        customers=customers,
        order_id_start=len(processed_orders) + 1,
        item_id_start=sum(len(o.items) for o in processed_orders) + 1,
        payment_id_start=len(processed_orders) + 1,
    )

    all_orders = processed_orders + failed_orders

    # Đồng bộ mốc thời gian tạo khách hàng trước đơn hàng đầu tiên của họ
    adjust_customer_created_at(customers, all_orders)

    # Sinh tồn kho khởi tạo cho 3.000 cặp sản phẩm x chi nhánh và sổ kho IMPORT ban đầu (Issue #107)
    inventories, inventory_transactions, stock_documents, stock_document_items = generate_initial_inventory(
        products=products,
        stockout_schedule=stockout_schedule,
        branches=branches,
        users=users,
        orders=all_orders,
        returns=returns,
    )

    # Chặng 4: Xuất SQL và JSON
    print("[5/5] Xuất file seed_data.sql và stockout_schedule.json...")
    base_dir = Path(__file__).resolve().parent.parent / "data"
    sql_path = base_dir / "seed_data.sql"
    json_path = base_dir / "stockout_schedule.json"

    export_stockout_schedule(stockout_schedule, products, json_path)
    export_to_sql(
        branches=branches,
        categories=categories,
        products=products,
        users=users,
        customers=customers,
        promotions=promotions,
        orders=all_orders,
        returns=returns,
        audit_logs=audit_logs,
        stock_documents=stock_documents,
        stock_document_items=stock_document_items,
        inventories=inventories,
        inventory_transactions=inventory_transactions,
        filepath=sql_path,
    )


    # Thống kê
    stats = compute_sales_statistics(processed_orders, products, top_sku_ids)
    print("\n--- KẾT QUẢ THỐNG KÊ ---")
    print(f"- Tổng số đơn hàng trong DB: {len(all_orders)} (10.000 đơn xử lý + {len(failed_orders)} đơn online thất bại)")
    print(f"- Đơn COMPLETED: {sum(1 for o in all_orders if o.status == 'COMPLETED')}")
    print(f"- Đơn RETURNED: {sum(1 for o in all_orders if o.status == 'RETURNED')}")
    print(f"- Đơn CANCELLED: {sum(1 for o in all_orders if o.status == 'CANCELLED')}")
    print(f"- Đơn EXPIRED: {sum(1 for o in all_orders if o.status == 'EXPIRED')}")
    print(f"- Đơn REJECTED: {sum(1 for o in all_orders if o.status == 'REJECTED')}")
    print(f"- Phiếu đổi trả: {len(returns)} (tổng số dòng hàng trả return_item: {len(return_items)})")
    print(f"- Phiếu nhập kho đầu kỳ (stock_document): {len(stock_documents)} phiếu ({len(stock_document_items)} dòng hàng)")
    print(f"- Tồn kho khởi tạo (inventory): {len(inventories)} bản ghi (sổ kho IMPORT: {len(inventory_transactions)} giao dịch)")
    print(f"  + Hết hàng (OUT): {sum(1 for i in inventories if i.on_hand - i.reserved == 0)} dòng")
    print(f"  + Tồn thấp (LOW): {sum(1 for i in inventories if 0 < i.on_hand - i.reserved <= i.reorder_point)} dòng")
    print(f"  + Bình thường (OK): {sum(1 for i in inventories if i.on_hand - i.reserved > i.reorder_point)} dòng")
    print(f"- Tỷ lệ Pareto 200 SKU top: {stats['pareto_share']*100:.2f}% sản lượng bán")
    print(f"- Tỷ lệ ô trống (sparsity) trong daily_sales: {stats['zero_cells_pct']:.2f}%")
    print(f"- File SQL: forecast-service/data/{sql_path.name} ({sql_path.stat().st_size / (1024*1024):.2f} MB)")
    print(f"- File Stockout JSON: forecast-service/data/{json_path.name} ({json_path.stat().st_size / 1024:.2f} KB)")
    print("Hoàn tất thành công!")


if __name__ == "__main__":
    main()
