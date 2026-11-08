"""Module triển khai mô hình LightGBM cho bài toán dự báo nhu cầu (Task #112).

Hỗ trợ:
- Các hàm mục tiêu (objectives): regression_l1 (L1/MAE), poisson, tweedie.
- Cố định random_state=2026 (config.RANDOM_SEED) để đảm bảo tính tái lập.
- Xử lý biến phân loại tự động (branch_id, product_id, group_code).
- Chặn dưới dự báo không âm: max(predicted_qty, 0.0).
- Huấn luyện kèm tập xác thực (validation set) để early stopping.
- Lưu và nạp mô hình từ file text.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple, Union
import lightgbm as lgb
import numpy as np
import pandas as pd

from scripts import config


DEFAULT_LIGHTGBM_PARAMS: Dict[str, Any] = {
    "objective": "regression_l1",
    "n_estimators": 100,
    "learning_rate": 0.08,
    "num_leaves": 31,
    "min_child_samples": 20,
    "random_state": config.RANDOM_SEED,
    "n_jobs": -1,
    "verbose": -1,
}


class LightGBMDemandModel:
    """Lớp bao bọc (wrapper) mô hình LightGBM cho dự báo số lượng bán ròng."""

    def __init__(
        self,
        horizon: int,
        objective: str = "regression_l1",
        params: Optional[Dict[str, Any]] = None,
    ):
        """Khởi tạo mô hình LightGBM cho tầm dự báo horizon.

        Tham số:
            horizon: Tầm dự báo (7 hoặc 30 ngày).
            objective: Mục tiêu huấn luyện ('regression_l1', 'poisson', hoặc 'tweedie').
            params: Tham số bổ sung hoặc ghi đè.
        """
        self.horizon = horizon
        self.objective = objective

        self.params = dict(DEFAULT_LIGHTGBM_PARAMS)
        self.params["objective"] = objective

        if objective == "tweedie":
            self.params["tweedie_variance_power"] = 1.2

        if params:
            self.params.update(params)

        self.model: Optional[lgb.LGBMRegressor] = None
        self.booster_: Optional[lgb.Booster] = None
        self.feature_names_: List[str] = []
        self.categorical_features_: List[str] = []
        self.best_iteration_: Optional[int] = None

    def fit(
        self,
        X_train: pd.DataFrame,
        y_train: np.ndarray,
        X_val: Optional[pd.DataFrame] = None,
        y_val: Optional[np.ndarray] = None,
        early_stopping_rounds: int = 15,
    ) -> LightGBMDemandModel:
        """Huấn luyện mô hình LightGBM trên tập dữ liệu train (kèm validation nếu có)."""
        if len(X_train) == 0:
            raise ValueError("Tập dữ liệu huấn luyện X_train rỗng.")

        self.feature_names_ = list(X_train.columns)
        self.categorical_features_ = [
            col for col in X_train.columns if isinstance(X_train[col].dtype, pd.CategoricalDtype)
        ]

        callbacks = []
        eval_set = None
        if X_val is not None and y_val is not None and len(X_val) > 0:
            eval_set = [(X_val, y_val)]
            callbacks.append(lgb.early_stopping(stopping_rounds=early_stopping_rounds, verbose=False))

        # Sử dụng LGBMRegressor
        self.model = lgb.LGBMRegressor(**self.params)

        # Chuyển đổi nhãn không âm cho các hàm mục tiêu đếm (poisson, tweedie)
        y_train_fit = np.clip(y_train, 0.0, None) if self.objective in ("poisson", "tweedie") else y_train

        self.model.fit(
            X_train,
            y_train_fit,
            eval_set=eval_set,
            callbacks=callbacks if callbacks else None,
        )

        self.booster_ = self.model.booster_
        if hasattr(self.model, "best_iteration_"):
            self.best_iteration_ = self.model.best_iteration_

        return self

    def predict(self, X: pd.DataFrame) -> np.ndarray:
        """Dự báo số lượng bán và chặn dưới tại 0.0."""
        if self.booster_ is not None:
            raw_preds = self.booster_.predict(X)
        elif self.model is not None:
            raw_preds = self.model.predict(X)
        else:
            raise RuntimeError("Mô hình chưa được huấn luyện. Hãy gọi fit() trước.")

        # Chặn dưới ở mức 0.0 vì lượng bán nhu cầu dự báo cho tương lai không thể âm
        clipped_preds = np.clip(raw_preds, 0.0, None)
        return np.asarray(clipped_preds, dtype=float)

    def save_model(self, file_path: Union[str, Path]) -> None:
        """Lưu trọng số mô hình ra file text."""
        if self.booster_ is None:
            raise RuntimeError("Không có mô hình để lưu.")
        p = Path(file_path)
        p.parent.mkdir(parents=True, exist_ok=True)
        self.booster_.save_model(str(p))

    def load_model(self, file_path: Union[str, Path]) -> LightGBMDemandModel:
        """Nạp mô hình từ file text."""
        p = Path(file_path)
        if not p.exists():
            raise FileNotFoundError(f"Không tìm thấy file mô hình tại {p}")
        self.booster_ = lgb.Booster(model_file=str(p))
        self.feature_names_ = self.booster_.feature_name()
        return self


def calculate_zero_ratio(preds: np.ndarray, threshold: float = 0.05) -> float:
    """Tính tỷ lệ dự báo bằng 0 hoặc xấp xỉ 0 (<= threshold)."""
    p = np.asarray(preds).ravel()
    if p.size == 0:
        return 0.0
    zero_count = int(np.sum(p <= threshold))
    return float(zero_count / p.size)
