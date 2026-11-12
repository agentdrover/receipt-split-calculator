"""Служебный маршрут: проверка живости сервиса."""

from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter()


class HealthResponse(BaseModel):
    status: str


@router.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    """Отдают балансировщик и проба перед выкатом."""
    return HealthResponse(status="ok")
