"""Unit test cho module forecast_runner (Issue #113).

Kiểm thử:
1. Tạo lượt chạy mới create_forecast_run (RUNNING).
2. Kiểm tra ràng buộc horizon chỉ nhận 7 hoặc 30.
3. Kiểm tra tính duy nhất của bộ khóa (branch_id, product_id, horizon, target_date).
4. Kiểm tra chặn dưới không âm cho predicted_qty.
5. Cập nhật hoàn tất complete_forecast_run và đánh dấu thất bại fail_forecast_run.
6. Hàm assemble_forecast_results_dataframe ghép nối chính xác 37 ngày cho mỗi chuỗi.
"""

from datetime import date, timedelta
from unittest.mock import MagicMock
import numpy as np
import pandas as pd
import pytest

from app.pipelines.forecast_runner import (
    assemble_forecast_results_dataframe,
    complete_forecast_run,
    create_forecast_run,
    fail_forecast_run,
    save_forecast_results,
)


def test_create_forecast_run_returns_run_id():
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur
    mock_cur.fetchone.return_value = (42,)

    run_id = create_forecast_run(mock_conn, model_version="LightGBM-Tweedie-H7-H30")
    assert run_id == 42
    assert mock_cur.execute.called
    query_call = mock_cur.execute.call_args[0][0]
    assert "INSERT INTO forecast_run" in query_call
    assert "RUNNING" in query_call


def test_save_forecast_results_horizon_constraint():
    mock_conn = MagicMock()
    # Invalid horizon 14
    df_invalid = pd.DataFrame({
        "branch_id": [1],
        "product_id": [10],
        "horizon": [14],
        "target_date": [date(2026, 10, 2)],
        "predicted_qty": [2.5],
    })
    with pytest.raises(ValueError, match="chỉ chấp nhận 7 hoặc 30"):
        save_forecast_results(mock_conn, run_id=1, results_df=df_invalid)


def test_save_forecast_results_duplicate_key_rejected():
    mock_conn = MagicMock()
    # Duplicate (branch_id, product_id, horizon, target_date)
    df_dup = pd.DataFrame({
        "branch_id": [1, 1],
        "product_id": [10, 10],
        "horizon": [7, 7],
        "target_date": [date(2026, 10, 2), date(2026, 10, 2)],
        "predicted_qty": [2.0, 3.0],
    })
    with pytest.raises(ValueError, match="trùng lặp bộ khóa"):
        save_forecast_results(mock_conn, run_id=1, results_df=df_dup)


def test_save_forecast_results_clips_negative_and_batches():
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur

    df_valid = pd.DataFrame({
        "branch_id": [1, 2],
        "product_id": [101, 102],
        "horizon": [7, 30],
        "target_date": [date(2026, 10, 2), date(2026, 10, 3)],
        "predicted_qty": [-1.5, 4.123456],
    })

    inserted_count = save_forecast_results(mock_conn, run_id=99, results_df=df_valid, batch_size=1)
    assert inserted_count == 2
    assert mock_cur.executemany.call_count == 2

    # Verify first record has predicted_qty clipped to 0.0
    first_call_args = mock_cur.executemany.call_args_list[0][0][1]
    assert first_call_args[0][0] == 99  # run_id
    assert first_call_args[0][5] == 0.0  # clipped negative
    # Second record rounded to 4 decimals
    second_call_args = mock_cur.executemany.call_args_list[1][0][1]
    assert second_call_args[0][5] == 4.1235


def test_complete_forecast_run_updates_status():
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur

    complete_forecast_run(
        mock_conn,
        run_id=10,
        mae=0.256,
        wape=1.6117,
        baseline_mae=0.2743,
        baseline_wape=1.7268,
    )
    assert mock_cur.execute.called
    call_args = mock_cur.execute.call_args[0]
    assert "UPDATE forecast_run" in call_args[0]
    assert "DONE" in call_args[0]
    assert call_args[1] == (0.256, 1.6117, 0.2743, 1.7268, 10)


def test_fail_forecast_run_updates_status():
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur

    fail_forecast_run(mock_conn, run_id=10)
    assert mock_cur.execute.called
    call_args = mock_cur.execute.call_args[0]
    assert "UPDATE forecast_run" in call_args[0]
    assert "FAILED" in call_args[0]
    assert call_args[1] == (10,)


def test_assemble_forecast_results_dataframe():
    idx = pd.MultiIndex.from_tuples([(1, 10), (1, 20)], names=["branch_id", "product_id"])
    pred_h7 = np.full((2, 7), 5.0)
    pred_h30 = np.full((2, 30), 3.0)
    cutoff = date(2026, 10, 1)

    df_assembled = assemble_forecast_results_dataframe(
        series_index=idx,
        predictions_by_horizon={7: pred_h7, 30: pred_h30},
        cutoff_date=cutoff,
    )

    # Total rows = 2 series * (7 + 30) = 74 rows
    assert len(df_assembled) == 74
    assert set(df_assembled["horizon"].unique()) == {7, 30}
    assert (df_assembled["predicted_qty"] >= 0).all()
    # Check start dates
    h7_dates = df_assembled[df_assembled["horizon"] == 7]["target_date"].unique()
    assert len(h7_dates) == 7
    assert min(h7_dates) == date(2026, 10, 2)
    assert max(h7_dates) == date(2026, 10, 8)

    h30_dates = df_assembled[df_assembled["horizon"] == 30]["target_date"].unique()
    assert len(h30_dates) == 30
    assert min(h30_dates) == date(2026, 10, 2)
    assert max(h30_dates) == date(2026, 10, 31)
