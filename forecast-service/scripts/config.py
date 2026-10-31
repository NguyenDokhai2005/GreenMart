"""Module cấu hình tham số sinh dữ liệu cho forecast-service (Issue #105).

Quy ước:
- Toàn bộ các giá trị cấu hình dưới đây đã được đối chiếu từ đặc tả SRS v1.0,
  Phân tích nghiệp vụ, ERD chuẩn và các phản hồi nghiệp vụ của Lead/SV01.
- Phân biệt rõ giữa các thông số đã xác nhận chính thức và các thông số giả định thiết kế.
- Múi giờ chuẩn của hệ thống là Asia/Ho_Chi_Minh (UTC+7).
- Chỉ đóng vai trò module tham số thiết kế; không triển khai logic sinh dữ liệu
  của Issue #106 tại đây.
"""
from __future__ import annotations

from datetime import date, datetime
from typing import Any, Dict, List, Tuple
from zoneinfo import ZoneInfo

# ==============================================================================
# 0. MÚI GIỜ HỆ THỐNG
# ==============================================================================

TIMEZONE_NAME: str = "Asia/Ho_Chi_Minh"
TIMEZONE: ZoneInfo = ZoneInfo(TIMEZONE_NAME)


# ==============================================================================
# 1. THỜI GIAN VÀ MÙA VỤ
# ==============================================================================

# Khoảng thời gian dữ liệu lịch sử: 01/11/2025 đến hết 31/10/2026 (12 tháng)
START_DATE: datetime = datetime(2025, 11, 1, 0, 0, 0, tzinfo=TIMEZONE)
END_DATE: datetime = datetime(2026, 10, 31, 23, 59, 59, tzinfo=TIMEZONE)

# Giới hạn thời gian sự kiện: không có bất kỳ sự kiện nào sinh ra sau 31/10/2026 23:59:59
MAX_EVENT_DATETIME: datetime = datetime(2026, 10, 31, 23, 59, 59, tzinfo=TIMEZONE)

# Ngày mùng 1 Tết Bính Ngọ (17/02/2026) - mốc đỉnh điểm mùa vụ mua sắm
TET_DATE: date = date(2026, 2, 17)

# Hệ số nhu cầu theo thứ trong tuần (0: Thứ 2 ... 6: Chủ nhật)
# Đảm bảo tổng = 7.00 và trung bình cộng chính xác = 1.000 (không làm lệch tổng nhu cầu tuần)
# Cuối tuần (T7, CN) có lượng mua cao hơn ngày thường theo phân tích nghiệp vụ
DAY_OF_WEEK_FACTORS: Dict[int, float] = {
    0: 0.85,  # Thứ Hai
    1: 0.85,  # Thứ Ba
    2: 0.90,  # Thứ Tư
    3: 0.90,  # Thứ Năm
    4: 1.10,  # Thứ Sáu
    5: 1.20,  # Thứ Bảy
    6: 1.20,  # Chủ Nhật
}

# Tham số mùa Tết Bính Ngọ 2026:
# - Giai đoạn trước Tết: từ 27/01/2026 đến 16/02/2026 (3 tuần trước Tết), nhu cầu tăng từ 50% đến 100%
# - Giai đoạn Tết (Mùng 1 - Mùng 3): 17/02/2026 đến 19/02/2026, nhu cầu giảm sâu (giả định mở bán nhẹ / trực Tết)
TET_PRE_START: date = date(2026, 1, 27)
TET_PRE_END: date = date(2026, 2, 16)
TET_PRE_DEMAND_LIFT_RANGE: Tuple[float, float] = (1.50, 2.00)

TET_HOLIDAY_START: date = date(2026, 2, 17)
TET_HOLIDAY_END: date = date(2026, 2, 19)
TET_HOLIDAY_DEMAND_FACTOR: float = 0.10

# Các ngày lễ lớn trong năm lịch sử và hệ số nhu cầu giả định
# Bao gồm: Giáng sinh (24-25/12), 30/4 - 1/5, Quốc khánh (2/9), Phụ nữ VN (20/10)
SPECIAL_HOLIDAYS: Dict[str, Dict[str, Any]] = {
    "CHRISTMAS_2025": {
        "name": "Giáng sinh",
        "start": date(2025, 12, 24),
        "end": date(2025, 12, 25),
        "demand_factor": 1.25,
    },
    "REUNIFICATION_LABOR_2026": {
        "name": "Giải phóng & Quốc tế Lao động",
        "start": date(2026, 4, 30),
        "end": date(2026, 5, 1),
        "demand_factor": 1.35,
    },
    "NATIONAL_DAY_2026": {
        "name": "Quốc khánh",
        "start": date(2026, 9, 2),
        "end": date(2026, 9, 2),
        "demand_factor": 1.35,
    },
    "VIETNAMESE_WOMENS_DAY_2026": {
        "name": "Ngày Phụ nữ Việt Nam",
        "start": date(2026, 10, 20),
        "end": date(2026, 10, 20),
        "demand_factor": 1.20,
    },
}

# Cơ cấu nhu cầu / ngành hàng đặc thù theo từng chi nhánh (giả định thiết kế):
# - GM-001 (Quận 1): Trung tâm, khách công sở -> thiên về thực phẩm chế biến sẵn & đồ uống
# - GM-002 (Bình Thạnh): Khu dân cư, hộ gia đình -> thiên về tươi sống, rau củ quả thiết yếu
# - GM-003 (Tân Bình): Khu đô thị phụ trợ -> thiên về đồ khô, hàng tiêu dùng nhanh (FMCG)
BRANCH_CATEGORY_DEMAND_WEIGHTS: Dict[str, Dict[str, float]] = {
    "GM-001": {
        "READY_TO_EAT_BEVERAGE": 1.30,
        "FRESH_FOOD": 0.90,
        "DRY_FOOD_FMCG": 1.00,
    },
    "GM-002": {
        "READY_TO_EAT_BEVERAGE": 0.95,
        "FRESH_FOOD": 1.30,
        "DRY_FOOD_FMCG": 1.00,
    },
    "GM-003": {
        "READY_TO_EAT_BEVERAGE": 0.90,
        "FRESH_FOOD": 1.00,
        "DRY_FOOD_FMCG": 1.30,
    },
}

# Biên độ nhiễu ngẫu nhiên của nhu cầu bán hàng hàng ngày (-10% đến +10%)
DEMAND_NOISE_RANGE: Tuple[float, float] = (-0.10, 0.10)


# ==============================================================================
# 2. ĐƠN HÀNG, KÊNH VÀ CHI NHÁNH
# ==============================================================================

# Tổng số đơn hàng đã xử lý thành công trong 12 tháng trên cả 3 chi nhánh (AC-01 & TC-AC01-03)
# Bao gồm các đơn hoàn tất nguyên vẹn (COMPLETED) và các đơn hoàn trả toàn bộ (RETURNED).
# 200 đơn online lỗi (CANCELLED, EXPIRED, REJECTED) là phần sinh thêm ngoài 10.000 đơn này.
TOTAL_PROCESSED_ORDERS: int = 10_000
TOTAL_COMPLETED_ORDERS: int = TOTAL_PROCESSED_ORDERS  # Alias tương thích ngược

# Trần hệ số tăng nhu cầu tối đa (Giả định thiết kế: tránh bùng nổ nhu cầu khi lễ/Tết trùng cuối tuần & KM)
MAX_DEMAND_LIFT_CAP: float = 3.0

# Tỷ lệ phân bổ đơn hàng theo kênh bán trên 10.000 đơn đã xử lý (tổng = 1.0)
# POS: 70% (7.000 đơn), WEB: 20% (2.000 đơn), MARKET: 10% (1.000 đơn)
CHANNEL_RATIOS: Dict[str, float] = {
    "POS": 0.70,
    "WEB": 0.20,
    "MARKET": 0.10,
}

# Số lượng đơn online đã xử lý mục tiêu (WEB 2.000 + MARKET 1.000)
PROCESSED_ONLINE_ORDERS: int = int(
    TOTAL_PROCESSED_ORDERS * (CHANNEL_RATIOS["WEB"] + CHANNEL_RATIOS["MARKET"])
)
COMPLETED_ONLINE_ORDERS: int = PROCESSED_ONLINE_ORDERS  # Alias tương thích ngược

# Tỷ lệ phân bổ đơn hàng theo chi nhánh trên 10.000 đơn COMPLETED (tổng = 1.0)
# GM-001 (Quận 1): 45%, GM-002 (Bình Thạnh): 35%, GM-003 (Tân Bình): 20%
BRANCH_RATIOS: Dict[str, float] = {
    "GM-001": 0.45,
    "GM-002": 0.35,
    "GM-003": 0.20,
}

# Danh sách chi nhánh phục vụ cho từng kênh bán hàng (ERD channel_branch & BR-14)
CHANNEL_SERVICE_BRANCHES: Dict[str, List[str]] = {
    "POS": ["GM-001", "GM-002", "GM-003"],
    "WEB": ["GM-001", "GM-002", "GM-003"],
    "MARKET": ["GM-001", "GM-002", "GM-003"],
}

# Chi nhánh mặc định (default_branch_id) cho kênh online khi khởi tạo đơn (BR-14)
# Đã được SV01 xác nhận: WEB -> GM-001 (Quận 1), MARKET -> GM-002 (Bình Thạnh)
CHANNEL_DEFAULT_BRANCH: Dict[str, str] = {
    "WEB": "GM-001",
    "MARKET": "GM-002",
}

# Quy cách giỏ hàng trên mỗi đơn:
# - Số dòng hàng (order_item) trong mỗi đơn: từ 3 đến 8 dòng
# - Số lượng sản phẩm trên mỗi dòng hàng: từ 1 đến 5 đơn vị
ORDER_ITEMS_RANGE: Tuple[int, int] = (3, 8)
ITEM_QUANTITY_RANGE: Tuple[int, int] = (1, 5)


# ==============================================================================
# 3. ĐƠN ONLINE THẤT BẠI (CANCELLED, EXPIRED, REJECTED)
# ==============================================================================

# Mẫu số tính đơn online thất bại: tính trên số đơn online COMPLETED (3.000 đơn)
FAILED_ONLINE_BASE: str = "COMPLETED_ONLINE"

# Mục tiêu tổng số đơn online thất bại sinh thêm (ngoài 10.000 đơn COMPLETED)
FAILED_ONLINE_TARGET_TOTAL: int = 200

# Phân bổ 200 đơn online thất bại theo trạng thái: 80 CANCELLED, 80 EXPIRED, 40 REJECTED
FAILED_ONLINE_BY_STATUS: Dict[str, int] = {
    "CANCELLED": 80,
    "EXPIRED": 80,
    "REJECTED": 40,
}

# Tỷ lệ thất bại suy ra từ 200 đơn thất bại trên 3.000 đơn online COMPLETED (200 / 3000 ≈ 6.67%)
FAILED_ONLINE_RATE: float = FAILED_ONLINE_TARGET_TOTAL / COMPLETED_ONLINE_ORDERS

# Thời hạn thanh toán / giữ chỗ đơn online: 15 phút (SRS 3.5 & BR-03)
# Lưu ý sửa tham chiếu từ BR-14 (phân bổ kênh) sang đúng BR-03 (thời hạn giữ chỗ thanh toán)
ONLINE_PAYMENT_TIMEOUT_MINUTES: int = 15


# ==============================================================================
# 4. DANH MỤC SẢN PHẨM VÀ QUY LUẬT PARETO 80/20
# ==============================================================================

# Tổng số SKU sản phẩm trong danh mục
TOTAL_SKU_COUNT: int = 1_000

# Thước đo Pareto: tính theo số lượng bán (qty_sold), không tính theo doanh thu
PARETO_TARGET_METRIC: str = "QUANTITY"

# Số lượng SKU bán chạy đóng góp chính
PARETO_TARGET_SKU_COUNT: int = 200

# Tỷ lệ đóng góp mục tiêu của 200 SKU bán chạy trên tổng số lượng bán (80%)
PARETO_TARGET_QUANTITY_SHARE: float = 0.80


# ==============================================================================
# 5. CHIẾN DỊCH KHUYẾN MÃI
# ==============================================================================

# Tần suất chiến dịch khuyến mãi: 1–2 chiến dịch mỗi tháng
PROMOTION_CAMPAIGNS_PER_MONTH_RANGE: Tuple[int, int] = (1, 2)

# Có chiến dịch khuyến mãi đặc biệt đợt Tết Bính Ngọ
HAS_TET_PROMOTION: bool = True

# Mức tăng nhu cầu (demand lift) trong thời gian khuyến mãi: từ +30% đến +80%
PROMOTION_DEMAND_LIFT_RANGE: Tuple[float, float] = (0.30, 0.80)

# Kiểu giảm giá và cấp độ khuyến mại (ERD promotion table & BR-07)
PROMOTION_DISCOUNT_TYPES: Tuple[str, ...] = ("PERCENT", "AMOUNT")
PROMOTION_LEVELS: Tuple[str, ...] = ("ORDER", "ITEM")

# Mức giảm phần trăm: từ 10% đến 20% (0.10 đến 0.20)
PROMOTION_PERCENT_DISCOUNT_RANGE: Tuple[float, float] = (0.10, 0.20)

# Trần giảm giá tối đa cho khuyến mại kiểu PERCENT cấp đơn: 50.000 VND
PROMOTION_MAX_DISCOUNT_CAP: int = 50_000

# Các mệnh giá giảm tiền cố định (AMOUNT) cho khuyến mại: 10.000, 20.000, 50.000 VND
PROMOTION_AMOUNT_DISCOUNT_OPTIONS: Tuple[int, ...] = (10_000, 20_000, 50_000)

# Giá trị đơn hàng tối thiểu để áp dụng khuyến mại cấp đơn: 150.000 đến 300.000 VND
PROMOTION_MIN_ORDER_AMOUNT_RANGE: Tuple[int, int] = (150_000, 300_000)

# Thời lượng chiến dịch thông thường: 3 đến 7 ngày
PROMOTION_CAMPAIGN_DURATION_DAYS_RANGE: Tuple[int, int] = (3, 7)

# Thời lượng chiến dịch khuyến mại đợt Tết: 14 ngày
TET_PROMOTION_DURATION_DAYS: int = 14

# Tỷ lệ và số lượng SKU áp dụng cho chiến dịch khuyến mại cấp sản phẩm (ITEM):
# Khoảng 2% đến 5% danh mục (tương đương 20 đến 50 SKU trên tổng 1.000 SKU)
PROMOTION_ITEM_SKU_RATIO_RANGE: Tuple[float, float] = (0.02, 0.05)
PROMOTION_ITEM_SKU_COUNT_RANGE: Tuple[int, int] = (20, 50)

# Phạm vi áp dụng chi nhánh và kênh bán hàng (ERD promotion_branch & promotion_channel)
PROMOTION_BRANCH_SCOPE_OPTIONS: Tuple[str, ...] = ("ALL", "SPECIFIC")
PROMOTION_CHANNEL_SCOPE_OPTIONS: Tuple[str, ...] = ("ALL", "POS_ONLY", "ONLINE_ONLY")


# ==============================================================================
# 6. STOCKOUT VÀ TRẢ HÀNG
# ==============================================================================

# Chế độ mô phỏng stockout: hỗn hợp (MIXED)
# Gồm cả bán được một phần rồi hết hàng giữa ngày và không bán được hàng trong ngày tiếp theo
STOCKOUT_SIMULATION_MODE: str = "MIXED"

# Khoảng thời gian mỗi đợt stockout kéo dài: từ 1 đến 3 ngày
STOCKOUT_DURATION_DAYS_RANGE: Tuple[int, int] = (1, 3)

# Tỷ lệ ngày xảy ra stockout ở nhóm 200 SKU bán nhanh: khoảng 2%–3% số ngày
STOCKOUT_DAYS_RATIO_RANGE: Tuple[float, float] = (0.02, 0.03)

# Tỷ lệ đơn hàng phát sinh trả hàng: 1%–2% số đơn đã xử lý (COMPLETED)
RETURN_RATE_RANGE: Tuple[float, float] = (0.01, 0.02)

# Tỷ lệ trả hàng toàn bộ vs trả một phần trong số các đơn phát sinh trả hàng (Giả định thiết kế):
# - Trả toàn bộ (FULL): 50% số đơn trả -> sales_order.status chuyển sang 'RETURNED'
# - Trả một phần (PARTIAL): 50% số đơn trả -> sales_order.status giữ nguyên 'COMPLETED'
FULL_RETURN_RATE_AMONG_RETURNS: float = 0.50
PARTIAL_RETURN_RATE_AMONG_RETURNS: float = 0.50

# Cửa sổ thời gian cho phép trả hàng: trong vòng 7 ngày kể từ khi đơn hoàn tất (BR-10)
RETURN_WINDOW_DAYS: int = 7

# Ranh giới trả hàng: không cho phép phát sinh phiếu trả hàng sang tháng 11/2026
ALLOW_RETURNS_AFTER_END_DATE: bool = False


# ==============================================================================
# 7. TÁI LẬP DỮ LIỆU (REPRODUCIBILITY)
# ==============================================================================

# Random seed cố định đảm bảo khả năng tái lập 100%
RANDOM_SEED: int = 2026
