"""Распознавание чека: фото на входе, receipt.v1 на выходе."""

import logging
import threading
import time
from collections import defaultdict, deque
from typing import Annotated

from fastapi import APIRouter, File, Header, UploadFile
from fastapi.responses import JSONResponse

from app.llm_client import LlmNotConfigured, ProviderFailed, UnreadableReceipt, get_client
from app.schemas.receipt import Receipt

router = APIRouter(prefix="/api/v1/receipts", tags=["receipts"])

MAX_BYTES = 8 * 1024 * 1024
# Запас на multipart-обвязку: по Content-Length отбиваем раньше разбора.
MAX_UPLOAD_BYTES = MAX_BYTES + 64 * 1024
RATE_LIMIT = 20
RATE_WINDOW_SECONDS = 3600.0
MAX_TRACKED_INSTALLATIONS = 5000

END_PATH = "/api/v1/receipts/recognize"

logger = logging.getLogger("receipts.recognize")

# Счётчик живёт в памяти процесса: сервис один, база ни к чему.
# Эндпоинт синхронный и выполняется в пуле потоков, поэтому словарь под замком.
_RECENT: dict[str, deque[float]] = defaultdict(deque)
_LOCK = threading.Lock()


class BodyTooLarge(Exception):
    """Тело превысило потолок прямо во время чтения."""


def content_length_exceeded(content_length: str | None) -> bool:
    """Тело заведомо больше лимита — отбиваем до разбора multipart."""
    if not content_length:
        return False
    try:
        value = int(content_length)
    except ValueError:
        return False
    return value > MAX_UPLOAD_BYTES


class BodyLimitMiddleware:
    """Потолок тела для одного маршрута.

    Одного Content-Length мало: клиент вправе прислать chunked без заголовка.
    Читаем тело сами, не больше лимита, и отдаём разборщику уже из памяти —
    иначе Starlette пишет файл на диск, а его ошибка разбора превращает наш
    413 в 400.
    """

    def __init__(self, app, max_bytes: int = MAX_UPLOAD_BYTES, path: str = END_PATH) -> None:
        self.app = app
        self.max_bytes = max_bytes
        self.path = path

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope.get("path") != self.path:
            await self.app(scope, receive, send)
            return

        headers = dict(scope.get("headers") or [])
        declared = headers.get(b"content-length", b"").decode("latin-1")
        if content_length_exceeded(declared):
            await _too_large(scope, receive, send)
            return

        chunks: list[bytes] = []
        received = 0
        while True:
            message = await receive()
            if message["type"] != "http.request":
                await _too_large(scope, receive, send)
                return
            body = message.get("body", b"")
            received += len(body)
            if received > self.max_bytes:
                await _too_large(scope, receive, send)
                return
            chunks.append(body)
            if not message.get("more_body", False):
                break

        buffered = b"".join(chunks)
        replayed = False

        async def replay():
            nonlocal replayed
            if replayed:
                return {"type": "http.disconnect"}
            replayed = True
            return {"type": "http.request", "body": buffered, "more_body": False}

        await self.app(scope, replay, send)


async def _too_large(scope, receive, send) -> None:
    # Заголовок отличает отказ по телу от отказа эндпоинта: по нему видно,
    # что до разбора multipart дело не дошло.
    await JSONResponse(
        {"error": "too_large"}, status_code=413, headers={"X-Reject-Reason": "body_limit"}
    )(scope, receive, send)


def _prune(window_start: float) -> None:
    """Выкидывает установки, чьи запросы все вышли из окна."""
    stale = [key for key, hits in _RECENT.items() if not hits or hits[-1] < window_start]
    for key in stale:
        del _RECENT[key]


def _evict_over_cap() -> None:
    """Держит потолок числа установок, вытесняя самые давние.

    Вытеснение сбрасывает квоту вытесненной установки — плата за то, что
    память не растёт от ротации X-Installation-Id.
    """
    while len(_RECENT) > MAX_TRACKED_INSTALLATIONS:
        oldest = min(_RECENT, key=lambda key: _RECENT[key][-1] if _RECENT[key] else 0.0)
        del _RECENT[oldest]


def _allow(installation_id: str, now: float | None = None) -> float | None:
    """None — запрос можно; иначе сколько секунд ждать до освобождения окна."""
    moment = time.monotonic() if now is None else now
    window_start = moment - RATE_WINDOW_SECONDS
    with _LOCK:
        _prune(window_start)
        hits = _RECENT[installation_id]
        while hits and hits[0] < window_start:
            hits.popleft()
        if len(hits) >= RATE_LIMIT:
            return max(1.0, hits[0] + RATE_WINDOW_SECONDS - moment)
        hits.append(moment)
        _evict_over_cap()
    return None


@router.post("/recognize", response_model=Receipt)
def recognize(
    file: Annotated[UploadFile, File()],
    installation_id: Annotated[str, Header(alias="X-Installation-Id")] = "",
) -> Receipt | JSONResponse:
    started = time.perf_counter()
    data = file.file.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        return _log_and_error(started, 413, "too_large")
    if not data:
        return _log_and_error(started, 422, "empty")

    wait_seconds = _allow(installation_id or "anonymous")
    if wait_seconds is not None:
        response = _log_and_error(started, 429, "rate_limited")
        response.headers["Retry-After"] = str(int(wait_seconds) + 1)
        return response

    try:
        client = get_client()
    except LlmNotConfigured:
        return _log_and_error(started, 503, "not_configured")

    try:
        receipt = client.recognize(data, file.content_type or "image/jpeg")
    except UnreadableReceipt:
        return _log_and_error(started, 422, "unreadable")
    except ProviderFailed:
        return _log_and_error(started, 502, "provider_failed")

    logger.info(
        "recognize status=200 duration_ms=%d tokens=%s",
        int((time.perf_counter() - started) * 1000),
        getattr(client, "last_usage", None),
    )
    return receipt


def _log_and_error(started: float, status: int, code: str) -> JSONResponse:
    """Журнал без текста позиций: только статус, длительность и код."""
    logger.info(
        "recognize status=%d error=%s duration_ms=%d",
        status,
        code,
        int((time.perf_counter() - started) * 1000),
    )
    return JSONResponse({"error": code}, status_code=status)
