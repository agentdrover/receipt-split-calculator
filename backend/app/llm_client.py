"""Клиент провайдера распознавания: изображение → receipt.v1.

Провайдер за этим интерфейсом — OpenAI-совместимый /chat/completions:
смена модели или поставщика делается переменными окружения, а не кодом.
"""

import base64
import json
import os

import httpx
from pydantic import ValidationError

from app.schemas.receipt import Receipt

DEFAULT_BASE_URL = "https://api.openai.com/v1"
DEFAULT_MODEL = "gpt-4o-mini"
DEFAULT_TIMEOUT = 25.0

PROMPT = (
    "Распознай фискальный чек на фото и верни строго JSON по схеме receipt.v1: "
    '{"merchant": str|null, "date": ISO-8601|null, "currency": "RUB", '
    '"items": [{"name": str, "qty": number|null, "unit_price": number|null, "amount": number}], '
    '"discount": {"present": bool, "amount": number}, '
    '"service_charge": {"present": bool, "percent": number|null, "amount": number}, '
    '"total": number, "confidence": 0..1, "warnings": [str]}. '
    "Служебные строки (итог, скидка, обслуживание, НДС, «итого без скидки») в items не попадают. "
    "Нечитаемую строку верни с qty=null и упомяни в warnings. Суммы — в рублях, "
    "числа без пробелов и знака валюты. Если чек не читается совсем — верни пустой items."
)


class LlmError(RuntimeError):
    """Базовая ошибка клиента."""


class LlmNotConfigured(LlmError):
    """Не задан ключ провайдера."""


class UnreadableReceipt(LlmError):
    """Модель ответила, но позиций нет — чек не прочитан."""


class ProviderFailed(LlmError):
    """Провайдер не дал валидный ответ даже после повтора."""


class VisionClient:
    def __init__(
        self,
        *,
        base_url: str,
        model: str,
        api_key: str,
        timeout: float = DEFAULT_TIMEOUT,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._model = model
        self._api_key = api_key
        self._timeout = timeout
        self._transport = transport

    def recognize(self, image: bytes, mime: str) -> Receipt:
        """Распознаёт чек; невалидный ответ модели повторяет один раз."""
        last_error: ProviderFailed | None = None
        for _ in range(2):
            try:
                return self._call(image, mime)
            except ProviderFailed as exc:
                last_error = exc
        raise last_error or ProviderFailed("provider failed")

    def _call(self, image: bytes, mime: str) -> Receipt:
        payload = {
            "model": self._model,
            "temperature": 0,
            "response_format": {"type": "json_object"},
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": PROMPT},
                        {
                            "type": "image_url",
                            "image_url": {"url": _data_url(image, mime)},
                        },
                    ],
                }
            ],
        }
        try:
            with httpx.Client(timeout=self._timeout, transport=self._transport) as http:
                response = http.post(
                    f"{self._base_url}/chat/completions",
                    json=payload,
                    headers={"Authorization": f"Bearer {self._api_key}"},
                )
        except httpx.HTTPError as exc:
            raise ProviderFailed(f"network error: {exc}") from exc

        if response.status_code != 200:
            raise ProviderFailed(f"provider status {response.status_code}")

        try:
            content = response.json()["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise ProviderFailed("unexpected provider payload") from exc

        try:
            data = json.loads(content)
        except (TypeError, ValueError) as exc:
            raise ProviderFailed("provider returned not json") from exc

        try:
            receipt = Receipt.model_validate(data)
        except ValidationError as exc:
            raise ProviderFailed("provider returned invalid receipt") from exc

        if not receipt.items:
            raise UnreadableReceipt("no items recognised")
        return receipt


def _data_url(image: bytes, mime: str) -> str:
    encoded = base64.b64encode(image).decode("ascii")
    return f"data:{mime};base64,{encoded}"


def get_client() -> VisionClient:
    """Клиент из окружения. Без ключа — LlmNotConfigured, а не тихий отказ."""
    api_key = os.environ.get("LLM_API_KEY", "").strip()
    if not api_key:
        raise LlmNotConfigured("LLM_API_KEY is not set")
    return VisionClient(
        base_url=os.environ.get("LLM_BASE_URL", DEFAULT_BASE_URL),
        model=os.environ.get("LLM_MODEL", DEFAULT_MODEL),
        api_key=api_key,
    )
