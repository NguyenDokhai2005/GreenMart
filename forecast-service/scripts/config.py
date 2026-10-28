"""Module cấu hình tham số sinh dữ liệu cho forecast-service (Issue #105).

Quy ước:
- Toàn bộ các giá trị cấu hình dưới đây đã được đối chiếu và xác nhận
  từ đặc tả SRS v1.0, Phân tích nghiệp vụ và phản hồi nghiệp vụ của Lead/SV01.
- Chỉ đưa vào dưới dạng tham số thiết kế; không tự ý tạo lịch chi tiết hay
  hệ số chưa có căn cứ.
"""

from datetime import date, datetime
from typing import Dict, Tuple

# ==============================================================================
# 1. THỜI GIAN VÀ MÙA VỤ
# ==============================================================================

# Khoảng thời gian dữ liệu lịch sử: 01/11/2025 đến hết 31/10/2026 (12 tháng)
START_DATE: datetime = datetime(2025, 11, 1, 0, 0, 0)
END_DATE: datetime = datetime(2026, 10, 31, 23, 59, 59)

# Giới hạn thời gian sự kiện: không có bất kỳ sự kiện nào sinh ra sau 31/10/2026 23:59:59
MAX_EVENT_DATETIME: datetime = datetime(2026, 10, 31, 23, 59, 59)

# Ngày Tết Bính Ngọ (17/02/2026) - mốc đỉnh điểm mùa vụ mua sắm
TET_DATE: date = date(2026, 2, 17)


# ==============================================================================
# 2. ĐƠN HÀNG, KÊNH VÀ CHI NHÁNH
# ==============================================================================

# Tổng số đơn hàng COMPLETED trong 12 tháng trên cả 3 chi nhánh
TOTAL_COMPLETED_ORDERS: int = 10_000

# Tỷ lệ phân bổ đơn hàng theo kênh bán trên 10.000 đơn COMPLETED (tổng = 1.0)
# POS: 70% (7.000 đơn), WEB: 20% (2.000 đơn), MARKET: 10% (1.000 đơn)
CHANNEL_RATIOS: Dict[str, float] = {
    "POS": 0.70,
    "WEB": 0.20,
    "MARKET": 0.10,
}

# Số lượng đơn online COMPLETED mục tiêu (WEB 2.000 + MARKET 1.000)
COMPLETED_ONLINE_ORDERS: int = int(
    TOTAL_COMPLETED_ORDERS * (CHANNEL_RATIOS["WEB"] + CHANNEL_RATIOS["MARKET"])
)

# Tỷ lệ phân bổ đơn hàng theo chi nhánh trên 10.000 đơn COMPLETED (tổng = 1.0)
# GM-001 (Quận 1): 45%, GM-002 (Bình Thạnh): 35%, GM-003 (Tân Bình): 20%
BRANCH_RATIOS: Dict[str, float] = {
    "GM-001": 0.45,
    "GM-002": 0.35,
    "GM-003": 0.20,
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

# Thời hạn thanh toán / giữ chỗ đơn online: 15 phút (SRS 3.5 & BR-14)
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

# Tỷ lệ đơn hàng phát sinh trả hàng: 1%–2% số đơn COMPLETED
RETURN_RATE_RANGE: Tuple[float, float] = (0.01, 0.02)

# Cửa sổ thời gian cho phép trả hàng: trong vòng 7 ngày kể từ khi đơn hoàn tất (BR-10)
RETURN_WINDOW_DAYS: int = 7

# Ranh giới trả hàng: không cho phép phát sinh phiếu trả hàng sang tháng 11/2026
ALLOW_RETURNS_AFTER_END_DATE: bool = False


# ==============================================================================
# 7. TÁI LẬP DỮ LIỆU (REPRODUCIBILITY)
# ==============================================================================

# Random seed cố định đảm bảo khả năng tái lập 100%
RANDOM_SEED: int = 2026
