"""Publisher phát sự kiện forecast.completed lên RabbitMQ (#118).

Tiêu chí kỹ thuật:
- Exchange: topic exchange `greenmart.events` (durable).
- Routing key: `forecast.completed`.
- Queue nhận: `greenmart.integration.events` (đã bind sẵn trong infra/definitions.json và RabbitTopology.java).
- Payload: runId, status, MAE, WAPE, baseline MAE/WAPE, cutoffDate, modelVersion, eventId, timestamp.
- Chịu lỗi (Resilience): Retry tối đa 3 lần với exponential backoff.
  Nếu RabbitMQ lỗi hoặc không khả dụng, log ERROR nhưng KHÔNG làm hỏng hay rollback forecast_run đã DONE.
"""

from __future__ import annotations

from datetime import datetime, timezone
import json
import logging
import os
import time
from typing import Any, Callable, Dict, Optional
import uuid

import pika

logger = logging.getLogger(__name__)

DEFAULT_EXCHANGE = "greenmart.events"
DEFAULT_ROUTING_KEY = "forecast.completed"


def get_rabbitmq_connection_parameters() -> pika.ConnectionParameters:
    """Đọc cấu hình kết nối RabbitMQ từ biến môi trường."""
    host = os.environ.get("RABBITMQ_HOST", "rabbitmq")
    port = int(os.environ.get("RABBITMQ_PORT", "5672"))
    user = os.environ.get("RABBITMQ_USER") or os.environ.get("RABBITMQ_DEFAULT_USER", "greenmart")
    password = os.environ.get("RABBITMQ_PASSWORD") or os.environ.get("RABBITMQ_DEFAULT_PASS", "")

    credentials = pika.PlainCredentials(user, password) if user else None
    return pika.ConnectionParameters(
        host=host,
        port=port,
        credentials=credentials,
        connection_attempts=3,
        retry_delay=2,
        socket_timeout=5,
    )


def get_rabbitmq_connection() -> pika.BlockingConnection:
    """Tạo kết nối BlockingConnection mới tới RabbitMQ."""
    params = get_rabbitmq_connection_parameters()
    return pika.BlockingConnection(params)


def build_forecast_completed_payload(
    run_id: int,
    status: str = "DONE",
    mae: Optional[float] = None,
    wape: Optional[float] = None,
    baseline_mae: Optional[float] = None,
    baseline_wape: Optional[float] = None,
    cutoff_date: Optional[str] = None,
    model_version: str = "LightGBM-Tweedie-H7-H30",
    event_id: Optional[str] = None,
    timestamp: Optional[str] = None,
) -> Dict[str, Any]:
    """Tạo cấu trúc dữ liệu payload cho sự kiện forecast.completed."""
    return {
        "eventId": event_id or str(uuid.uuid4()),
        "eventType": DEFAULT_ROUTING_KEY,
        "timestamp": timestamp or datetime.now(timezone.utc).isoformat(),
        "runId": run_id,
        "status": status,
        "modelVersion": model_version,
        "mae": mae,
        "wape": wape,
        "baselineMae": baseline_mae,
        "baselineWape": baseline_wape,
        "cutoffDate": cutoff_date,
    }


def publish_forecast_completed(
    run_id: int,
    status: str = "DONE",
    mae: Optional[float] = None,
    wape: Optional[float] = None,
    baseline_mae: Optional[float] = None,
    baseline_wape: Optional[float] = None,
    cutoff_date: Optional[str] = None,
    model_version: str = "LightGBM-Tweedie-H7-H30",
    exchange: Optional[str] = None,
    routing_key: Optional[str] = None,
    max_retries: int = 3,
    retry_backoff_factor: float = 1.0,
    connection_factory: Optional[Callable[[], Any]] = None,
    sleep_fn: Callable[[float], None] = time.sleep,
) -> bool:
    """Phát sự kiện forecast.completed lên RabbitMQ với cơ chế retry an toàn.

    Tham số:
    - connection_factory: Hàm tạo kết nối RabbitMQ (mặc định get_rabbitmq_connection, cho phép DI mock khi test).
    - sleep_fn: Hàm delay giữa các lần thử lại (cho phép DI mock khi test để không phải chờ thật).

    Trả về:
    - True nếu phát thành công.
    - False nếu thất bại sau tất cả các lần thử lại (chỉ log ERROR, không raise exception).
    """
    target_exchange = exchange or os.environ.get("RABBITMQ_EXCHANGE", DEFAULT_EXCHANGE)
    target_routing_key = routing_key or os.environ.get("RABBITMQ_ROUTING_KEY", DEFAULT_ROUTING_KEY)

    payload = build_forecast_completed_payload(
        run_id=run_id,
        status=status,
        mae=mae,
        wape=wape,
        baseline_mae=baseline_mae,
        baseline_wape=baseline_wape,
        cutoff_date=cutoff_date,
        model_version=model_version,
    )
    body = json.dumps(payload, ensure_ascii=False)

    conn_factory = connection_factory or get_rabbitmq_connection

    for attempt in range(1, max_retries + 1):
        conn = None
        try:
            logger.info(
                "Đang phát sự kiện %s lên exchange %s (run_id=%d, lần thử %d/%d)...",
                target_routing_key,
                target_exchange,
                run_id,
                attempt,
                max_retries,
            )
            conn = conn_factory()
            channel = conn.channel()

            # Khai báo exchange topic bền vững (idempotent)
            channel.exchange_declare(
                exchange=target_exchange,
                exchange_type="topic",
                durable=True,
            )

            # Thuộc tính message persistent (delivery_mode=2)
            properties = pika.BasicProperties(
                delivery_mode=2,
                content_type="application/json",
                message_id=payload["eventId"],
                timestamp=int(datetime.now(timezone.utc).timestamp()),
            )

            channel.basic_publish(
                exchange=target_exchange,
                routing_key=target_routing_key,
                body=body,
                properties=properties,
            )

            logger.info(
                "Phát sự kiện %s thành công cho run_id=%d (eventId=%s)",
                target_routing_key,
                run_id,
                payload["eventId"],
            )
            return True

        except Exception as exc:
            logger.warning(
                "Thử phát sự kiện %s lần %d/%d thất bại: %s",
                target_routing_key,
                attempt,
                max_retries,
                exc,
            )
            if attempt < max_retries:
                delay = retry_backoff_factor * (2 ** (attempt - 1))
                sleep_fn(delay)
        finally:
            if conn is not None:
                try:
                    conn.close()
                except Exception:
                    pass

    # Sau khi thử lại hết số lần mà vẫn lỗi
    logger.error(
        "LỖI: Không thể phát sự kiện %s lên RabbitMQ cho run_id=%d sau %d lần thử. "
        "Hạn chế đã biết: Dữ liệu forecast_run vẫn giữ trạng thái DONE trong CSDL, "
        "không có transactional outbox để gửi bù.",
        target_routing_key,
        run_id,
        max_retries,
    )
    return False
