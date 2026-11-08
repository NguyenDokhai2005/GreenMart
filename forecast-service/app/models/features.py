"""Module xây dựng đặc trưng (Feature Engineering) cho mô hình dự báo nhu cầu (Task #112).

Tuân thủ nghiêm ngặt các quy tắc:
1. Thiết kế theo "ngày gốc (origin)":
   - Mỗi mẫu quan sát gồm ngày gốc t và ngày mục tiêu t+h.
   - Mọi đặc trưng lịch sử chỉ tính từ dữ liệu <= t (rolling mean 7/14/28 ngày kết thúc tại t,
     lượng bán cùng thứ ở các tuần trước t, lượng bán tại ngày gốc t).
   - Tuyệt đối không dùng lag tính theo ngày mục tiêu để tránh rò rỉ dữ liệu (No Data Leakage).
2. Đặc trưng của NGÀY MỤC TIÊU:
   - Cờ lịch (thứ trong tuần, ngày trong tháng, tháng, cuối tuần, ngày lễ).
   - Cờ Tết Bính Ngọ 2026: 21 ngày trước Tết (27/01/2026 - 16/02/2026).
   - Khuyến mại của ngày mục tiêu (promo_flag, promo_discount_pct).
3. Xử lý phân loại:
   - branch_id và product_id được chuyển thành kiểu category (Categorical Features).
   - group_code được sử dụng nếu có trong DataFrame, nếu không có sẽ tự động fallback sang category 'UNKNOWN'.
4. Tối ưu hóa Vectorized: Tính toán dựa trên ma trận (chuỗi × ngày) qua numpy và pandas để xử lý
   nhanh chóng 3.000 chuỗi bán lẻ.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta
import math
from typing import Dict, List, Optional, Sequence, Set, Tuple, Union
import numpy as np
import pandas as pd

from scripts import config

# Danh sách ngày lễ từ config
SPECIAL_HOLIDAYS_DATES: Set[date] = set()
for h_info in config.SPECIAL_HOLIDAYS.values():
    curr = h_info["start"]
    while curr <= h_info["end"]:
        SPECIAL_HOLIDAYS_DATES.add(curr)
        curr += timedelta(days=1)

TET_PRE_START = config.TET_PRE_START
TET_PRE_END = config.TET_PRE_END


def is_holiday(d: date) -> bool:
    """Kiểm tra một ngày có phải ngày lễ đặc biệt không."""
    return d in SPECIAL_HOLIDAYS_DATES


def is_tet_pre(d: date) -> bool:
    """Kiểm tra ngày có thuộc giai đoạn 21 ngày trước Tết không (27/01/2026 - 16/02/2026)."""
    return TET_PRE_START <= d <= TET_PRE_END


def extract_target_date_calendar_features(target_date: date) -> Dict[str, Union[int, float, bool]]:
    """Trích xuất các đặc trưng lịch và mùa vụ của NGÀY MỤC TIÊU (target_date)."""
    dow = target_date.weekday()
    return {
        "target_day_of_week": dow,
        "target_day_of_month": target_date.day,
        "target_month": target_date.month,
        "target_is_weekend": int(dow in (5, 6)),
        "target_is_holiday": int(is_holiday(target_date)),
        "target_is_tet_pre": int(is_tet_pre(target_date)),
    }


class FeatureMatrixBuilder:
    """Lớp xây dựng ma trận đặc trưng theo thiết kế Ngày Gốc (Origin) tối ưu hóa ma trận."""

    def __init__(self, df_daily: pd.DataFrame):
        df = df_daily.copy()
        if pd.api.types.is_datetime64_any_dtype(df["sale_date"]):
            df["sale_date"] = df["sale_date"].dt.date

        self.df = df
        self.unique_dates: List[date] = sorted(df["sale_date"].unique())
        self.date_to_idx: Dict[date, int] = {d: i for i, d in enumerate(self.unique_dates)}

        if "stockout" not in self.df.columns:
            self.df["stockout"] = False
        if "promo_flag" not in self.df.columns:
            self.df["promo_flag"] = False
        if "promo_discount_pct" not in self.df.columns:
            self.df["promo_discount_pct"] = 0.0

        # Kiểm tra cột group_code
        self.has_group_code = "group_code" in self.df.columns

        # Pivot ma trận lượng bán và stockout (chuỗi × ngày)
        indexed = self.df.set_index(["branch_id", "product_id", "sale_date"])
        self.pivot_qty = (
            indexed["qty_net"]
            .unstack(level="sale_date", fill_value=0)
            .reindex(columns=self.unique_dates, fill_value=0)
        )
        self.pivot_stockout = (
            indexed["stockout"]
            .unstack(level="sale_date", fill_value=False)
            .reindex(columns=self.unique_dates, fill_value=False)
        )
        self.pivot_promo_flag = (
            indexed["promo_flag"]
            .unstack(level="sale_date", fill_value=False)
            .reindex(columns=self.unique_dates, fill_value=False)
        )
        self.pivot_promo_pct = (
            indexed["promo_discount_pct"]
            .unstack(level="sale_date", fill_value=0.0)
            .reindex(columns=self.unique_dates, fill_value=0.0)
        )

        self.series_index = self.pivot_qty.index
        self.n_series = len(self.series_index)

        self.branch_ids = self.series_index.get_level_values("branch_id").to_numpy()
        self.product_ids = self.series_index.get_level_values("product_id").to_numpy()

        if self.has_group_code:
            # Lấy mapping product_id -> group_code
            prod_group_map = self.df.drop_duplicates(subset=["product_id"])[["product_id", "group_code"]]
            group_dict = dict(zip(prod_group_map["product_id"], prod_group_map["group_code"]))
            self.group_codes = np.array([str(group_dict.get(pid, "UNKNOWN")) for pid in self.product_ids])
        else:
            self.group_codes = np.full(self.n_series, "UNKNOWN")

        self.qty_matrix = self.pivot_qty.to_numpy(dtype=float)  # (N_series, N_dates)
        self.stockout_matrix = self.pivot_stockout.to_numpy(dtype=bool)
        self.promo_flag_matrix = self.pivot_promo_flag.to_numpy(dtype=bool)
        self.promo_pct_matrix = self.pivot_promo_pct.to_numpy(dtype=float)

    def extract_features_for_origin_and_step(
        self,
        origin_date: date,
        step: int,
    ) -> Tuple[pd.DataFrame, np.ndarray, np.ndarray]:
        """Tạo đặc trưng cho 1 cặp (origin_date t, step h).

        Trả về:
            X_df: DataFrame đặc trưng của N_series tại ngày mục tiêu (t+h).
            y_arr: Mảng nhãn thực tế qty_net tại ngày mục tiêu.
            stockout_arr: Mảng cờ stockout tại ngày mục tiêu (dùng để lọc tập train/clean test).
        """
        if origin_date not in self.date_to_idx:
            raise ValueError(f"Ngày gốc {origin_date} không có trong dữ liệu.")

        origin_idx = self.date_to_idx[origin_date]
        target_date = origin_date + timedelta(days=step)

        if target_date not in self.date_to_idx:
            raise ValueError(f"Ngày mục tiêu {target_date} không có trong dữ liệu.")
        target_idx = self.date_to_idx[target_date]

        # 1. Đặc trưng lịch sử <= origin_idx
        # Rolling means kết thúc tại origin_idx
        w7_start = max(0, origin_idx - 6)
        mean_7 = np.mean(self.qty_matrix[:, w7_start : origin_idx + 1], axis=1)

        w14_start = max(0, origin_idx - 13)
        mean_14 = np.mean(self.qty_matrix[:, w14_start : origin_idx + 1], axis=1)

        w28_start = max(0, origin_idx - 27)
        mean_28 = np.mean(self.qty_matrix[:, w28_start : origin_idx + 1], axis=1)

        # Lượng bán tại ngày gốc origin
        qty_at_origin = self.qty_matrix[:, origin_idx]

        # Lượng bán cùng thứ ở các tuần trước origin_idx
        # Ngày cùng thứ gần nhất <= origin_date:
        # lùi từ target_date một số bội số của 7 sao cho <= origin_date
        # Với step h >= 1, số tuần cần lùi là k = ceil(h / 7)
        k_weeks = int(math.ceil(step / 7.0))
        same_dow_date_1w = target_date - timedelta(days=7 * k_weeks)
        same_dow_date_2w = target_date - timedelta(days=7 * (k_weeks + 1))

        if same_dow_date_1w in self.date_to_idx:
            idx_dow1 = self.date_to_idx[same_dow_date_1w]
            same_dow_sale_1w = self.qty_matrix[:, idx_dow1]
        else:
            same_dow_sale_1w = mean_7

        if same_dow_date_2w in self.date_to_idx:
            idx_dow2 = self.date_to_idx[same_dow_date_2w]
            same_dow_sale_2w = self.qty_matrix[:, idx_dow2]
        else:
            same_dow_sale_2w = mean_14

        # 2. Đặc trưng của NGÀY MỤC TIÊU
        cal_feats = extract_target_date_calendar_features(target_date)
        promo_flag_target = self.promo_flag_matrix[:, target_idx].astype(int)
        promo_pct_target = self.promo_pct_matrix[:, target_idx]

        # 3. Tạo DataFrame đặc trưng
        data = {
            "branch_id": self.branch_ids,
            "product_id": self.product_ids,
            "group_code": self.group_codes,
            "horizon_step": np.full(self.n_series, step, dtype=int),
            "rolling_mean_7": mean_7,
            "rolling_mean_14": mean_14,
            "rolling_mean_28": mean_28,
            "qty_at_origin": qty_at_origin,
            "same_dow_sale_1w": same_dow_sale_1w,
            "same_dow_sale_2w": same_dow_sale_2w,
            "target_day_of_week": np.full(self.n_series, cal_feats["target_day_of_week"], dtype=int),
            "target_day_of_month": np.full(self.n_series, cal_feats["target_day_of_month"], dtype=int),
            "target_month": np.full(self.n_series, cal_feats["target_month"], dtype=int),
            "target_is_weekend": np.full(self.n_series, cal_feats["target_is_weekend"], dtype=int),
            "target_is_holiday": np.full(self.n_series, cal_feats["target_is_holiday"], dtype=int),
            "target_is_tet_pre": np.full(self.n_series, cal_feats["target_is_tet_pre"], dtype=int),
            "target_promo_flag": promo_flag_target,
            "target_promo_discount_pct": promo_pct_target,
        }

        X_df = pd.DataFrame(data)
        # Chuyển kiểu biến phân loại
        X_df["branch_id"] = X_df["branch_id"].astype("category")
        X_df["product_id"] = X_df["product_id"].astype("category")
        X_df["group_code"] = X_df["group_code"].astype("category")

        y_arr = self.qty_matrix[:, target_idx]
        stockout_arr = self.stockout_matrix[:, target_idx]

        return X_df, y_arr, stockout_arr

    def build_dataset_for_horizon(
        self,
        horizon: int,
        origin_dates: Sequence[date],
        max_target_date: Optional[date] = None,
        exclude_stockout: bool = True,
    ) -> Tuple[pd.DataFrame, np.ndarray]:
        """Tạo tập dữ liệu cho tầm horizon trên danh sách các origin_dates.

        Tham số:
            horizon: Tầm dự báo H (ví dụ 7 hoặc 30).
            origin_dates: Danh sách các ngày gốc t.
            max_target_date: Ngày mục tiêu tối đa (t+h <= max_target_date). Ràng buộc nghiêm ngặt
                             để nhãn không bao giờ chồng sang tập test (2026-10-01).
            exclude_stockout: Nếu True, loại bỏ các mẫu mà ngày mục tiêu bị stockout (cho tập train).
        """
        all_X: List[pd.DataFrame] = []
        all_y: List[np.ndarray] = []

        for origin in origin_dates:
            for step in range(1, horizon + 1):
                target_d = origin + timedelta(days=step)
                if max_target_date is not None and target_d > max_target_date:
                    continue
                if target_d not in self.date_to_idx:
                    continue

                X_step, y_step, stockout_step = self.extract_features_for_origin_and_step(
                    origin_date=origin,
                    step=step,
                )

                if exclude_stockout:
                    valid_mask = ~stockout_step
                    X_step = X_step[valid_mask]
                    y_step = y_step[valid_mask]

                all_X.append(X_step)
                all_y.append(y_step)

        if not all_X:
            empty_df = pd.DataFrame(columns=[
                "branch_id", "product_id", "group_code", "horizon_step",
                "rolling_mean_7", "rolling_mean_14", "rolling_mean_28",
                "qty_at_origin", "same_dow_sale_1w", "same_dow_sale_2w",
                "target_day_of_week", "target_day_of_month", "target_month",
                "target_is_weekend", "target_is_holiday", "target_is_tet_pre",
                "target_promo_flag", "target_promo_discount_pct",
            ])
            return empty_df, np.array([], dtype=float)

        X_combined = pd.concat(all_X, ignore_index=True)
        y_combined = np.concatenate(all_y)
        return X_combined, y_combined

    def build_test_features_matrix(
        self,
        cutoff_date: date,
        horizon: int,
    ) -> List[pd.DataFrame]:
        """Tạo danh sách các DataFrame đặc trưng cho tập kiểm tra (test set) từ điểm mốc cutoff_date.

        Trả về danh sách H phần tử, mỗi phần tử là DataFrame đặc trưng cho ngày mục tiêu cutoff_date + step.
        """
        feature_dfs: List[pd.DataFrame] = []
        for step in range(1, horizon + 1):
            X_step, _, _ = self.extract_features_for_origin_and_step(
                origin_date=cutoff_date,
                step=step,
            )
            feature_dfs.append(X_step)
        return feature_dfs
