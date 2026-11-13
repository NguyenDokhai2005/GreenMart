"""Pydantic schemas for forecast API (#114).

Responses use camelCase naming convention via alias_generator=to_camel.
Errors use standard structure: {"code": "...", "message": "..."}.
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    """Base model with camelCase serialization and alias population support."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
    )


class ForecastRunCreatedResponse(CamelModel):
    """Response returned immediately by POST /forecast/run (HTTP 202 Accepted)."""

    run_id: int
    status: Literal["RUNNING", "DONE", "FAILED"]
    started_at: datetime


class ForecastRunDetailResponse(CamelModel):
    """Detailed response returned by GET /forecast/runs/{runId} (HTTP 200 OK)."""

    run_id: int
    status: Literal["RUNNING", "DONE", "FAILED"]
    started_at: datetime
    finished_at: Optional[datetime] = None
    model_version: str
    mae: Optional[float] = None
    wape: Optional[float] = None
    baseline_mae: Optional[float] = None
    baseline_wape: Optional[float] = None


class ErrorResponse(BaseModel):
    """Standard error response structure."""

    code: str
    message: str
