"""Распознавание чека: фото на входе, receipt.v1 на выходе."""

import time
from collections import defaultdict, deque
from typing import Annotated

from fastapi import APIRouter, File, Header, HTTPException, UploadFile

from app.llm_client import LlmNotConfigured, ProviderFailed, UnreadableReceipt, get_client
from app.schemas.receipt import Receipt

router = APIRouter(prefix="/api/v1/receipts", tags=["receipts"])

MAX_BYTES = 8 * 1024 * 1024
# Запас на multipart-обвязку: по Content-Length проверяем раньше, чем FastAPI
# разложит тело на диск.
MAX_UPLOAD_BYTES = MAX_BYTES + 64 * 1024
RATE_LIMIT = 20
RATE_WINDOW_SECONDS = 3600.0
MAX_TRACKED_INSTALLATIONS = 5000

# Счётчик живёт в памяти процесса: сервис один, база ни к чему.
_RECENT: dict[str, deque[float]] = defaultdict(deque)


def content_length_exceeded(content_length: str | None) -> bool:
    """Тело заведомо больше лимита — отбиваем до разбора multipart."""
    if not content_length:
        return False
    try:
        value = int(content_length)
    except ValueError:
        return False
    return value > MAX_UPLOAD_BYTES


def _prune(window_start: float) -> None:
    """Выкидывает установки, чьи запросы все вышли из окна."""
    if len(_RECENT) <= MAX_TRACKED_INSTALLATIONS:
        return
    stale = [key for key, hits in _RECENT.items() if not hits or hits[-1] < window_start]
    for key in stale:
        del _RECENT[key]


def _allow(installation_id: str, now: float | None = None) -> bool:
    """Не больше RATE_LIMIT запросов за окно на установку."""
    moment = time.monotonic() if now is None else now
    window_start = moment - RATE_WINDOW_SECONDS
    _prune(window_start)
    hits = _RECENT[installation_id]
    while hits and hits[0] < window_start:
        hits.popleft()
    if len(hits) >= RATE_LIMIT:
        return False
    hits.append(moment)
    return True


@router.post("/recognize", response_model=Receipt)
def recognize(
    file: Annotated[UploadFile, File()],
    installation_id: Annotated[str, Header(alias="X-Installation-Id")] = "",
) -> Receipt:
    data = file.file.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise HTTPException(413, {"error": "too_large"})
    if not data:
        raise HTTPException(422, {"error": "empty"})
    if not _allow(installation_id or "anonymous"):
        raise HTTPException(429, {"error": "rate_limited"})

    try:
        client = get_client()
    except LlmNotConfigured as exc:
        raise HTTPException(503, {"error": "not_configured"}) from exc

    try:
        return client.recognize(data, file.content_type or "image/jpeg")
    except UnreadableReceipt as exc:
        raise HTTPException(422, {"error": "unreadable"}) from exc
    except ProviderFailed as exc:
        raise HTTPException(502, {"error": "provider_failed"}) from exc
