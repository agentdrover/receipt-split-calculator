"""Тесты эндпоинта распознавания: провайдер везде замокан."""

import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app.api import receipts
from app.llm_client import LlmNotConfigured, ProviderFailed, UnreadableReceipt, VisionClient
from app.main import create_app
from app.schemas.receipt import Receipt

VALID_PAYLOAD = {
    "merchant": "Кофемания",
    "currency": "RUB",
    "items": [
        {"name": "Капучино", "qty": 2, "unit_price": 390, "amount": 780},
        {"name": "Паста карбонара", "qty": 1, "unit_price": 690, "amount": 690},
    ],
    "discount": {"present": False, "amount": 0},
    "service_charge": {"present": False, "amount": 0},
    "total": 1470,
    "confidence": 0.9,
    "warnings": [],
}


class FakeClient:
    def __init__(self, result=None, error=None):
        self.result = result
        self.error = error
        self.calls = 0

    def recognize(self, image: bytes, mime: str) -> Receipt:
        self.calls += 1
        if self.error:
            raise self.error
        return self.result


@pytest.fixture
def app():
    return create_app()


@pytest.fixture
def client(app):
    return TestClient(app)


@pytest.fixture(autouse=True)
def clear_rate_limit():
    receipts._RECENT.clear()
    yield
    receipts._RECENT.clear()


def _post(client, *, installation_id="dev-1", content=b"\xff\xd8fake-jpeg"):
    return client.post(
        "/api/v1/receipts/recognize",
        files={"file": ("check.jpg", content, "image/jpeg")},
        headers={"X-Installation-Id": installation_id},
    )


def test_recognize_returns_valid_receipt(client, monkeypatch):
    fake = FakeClient(result=Receipt.model_validate(VALID_PAYLOAD))
    monkeypatch.setattr(receipts, "get_client", lambda: fake)

    response = _post(client)

    assert response.status_code == 200
    body = response.json()
    assert body["currency"] == "RUB"
    assert len(body["items"]) == 2
    assert float(body["total"]) == 1470


def test_empty_items_are_unreadable(client, monkeypatch):
    fake = FakeClient(error=UnreadableReceipt("empty"))
    monkeypatch.setattr(receipts, "get_client", lambda: fake)

    response = _post(client)

    assert response.status_code == 422
    assert response.json()["detail"]["error"] == "unreadable"


def test_provider_failure_is_502(client, monkeypatch):
    fake = FakeClient(error=ProviderFailed("bad json"))
    monkeypatch.setattr(receipts, "get_client", lambda: fake)

    response = _post(client)

    assert response.status_code == 502
    assert response.json()["detail"]["error"] == "provider_failed"


def test_missing_key_is_503(client, monkeypatch):
    def boom():
        raise LlmNotConfigured()

    monkeypatch.setattr(receipts, "get_client", boom)

    response = _post(client)

    assert response.status_code == 503
    assert response.json()["detail"]["error"] == "not_configured"


def test_rate_limit_per_installation(client, monkeypatch):
    fake = FakeClient(result=Receipt.model_validate(VALID_PAYLOAD))
    monkeypatch.setattr(receipts, "get_client", lambda: fake)

    for _ in range(receipts.RATE_LIMIT):
        assert _post(client).status_code == 200

    limited = _post(client)
    assert limited.status_code == 429
    assert limited.json()["detail"]["error"] == "rate_limited"
    assert fake.calls == receipts.RATE_LIMIT


def test_oversized_file_is_413(client, monkeypatch):
    fake = FakeClient(result=Receipt.model_validate(VALID_PAYLOAD))
    monkeypatch.setattr(receipts, "get_client", lambda: fake)

    response = _post(client, content=b"x" * (receipts.MAX_BYTES + 1))

    assert response.status_code == 413
    assert response.json()["detail"]["error"] == "too_large"
    assert fake.calls == 0


def test_client_retries_invalid_json_once_then_fails():
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(200, json={"choices": [{"message": {"content": "не json"}}]})

    transport = httpx.MockTransport(handler)
    vision = VisionClient(
        base_url="https://example.test/v1", model="test-model", api_key="k", transport=transport
    )

    with pytest.raises(ProviderFailed):
        vision.recognize(b"img", "image/jpeg")

    assert calls["n"] == 2


def test_client_validates_and_returns_receipt():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, json={"choices": [{"message": {"content": json.dumps(VALID_PAYLOAD)}}]}
        )

    transport = httpx.MockTransport(handler)
    vision = VisionClient(
        base_url="https://example.test/v1", model="test-model", api_key="k", transport=transport
    )

    receipt = vision.recognize(b"img", "image/jpeg")

    assert isinstance(receipt, Receipt)
    assert receipt.items[0].name == "Капучино"


def test_provider_empty_items_are_unreadable(client, monkeypatch):
    """Пустой items от модели — 422, а не 502: схема пустой чек пропускает."""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": json.dumps({"items": [], "total": 0})}}]},
        )

    vision = VisionClient(
        base_url="https://example.test/v1",
        model="m",
        api_key="k",
        transport=httpx.MockTransport(handler),
    )
    monkeypatch.setattr(receipts, "get_client", lambda: vision)

    response = _post(client)

    assert response.status_code == 422
    assert response.json()["detail"]["error"] == "unreadable"


def test_receipt_json_uses_numbers(client, monkeypatch):
    """Клиент на Kotlin ждёт числа, а не строки: Decimal сериализуем как float."""
    fake = FakeClient(result=Receipt.model_validate(VALID_PAYLOAD))
    monkeypatch.setattr(receipts, "get_client", lambda: fake)

    body = _post(client).json()

    assert isinstance(body["total"], (int, float))
    assert isinstance(body["items"][0]["amount"], (int, float))
    assert isinstance(body["items"][0]["qty"], (int, float))


def test_content_length_guard():
    assert receipts.content_length_exceeded(None) is False
    assert receipts.content_length_exceeded("") is False
    assert receipts.content_length_exceeded("не число") is False
    assert receipts.content_length_exceeded(str(receipts.MAX_UPLOAD_BYTES)) is False
    assert receipts.content_length_exceeded(str(receipts.MAX_UPLOAD_BYTES + 1)) is True


def test_rate_limiter_forgets_stale_installations(monkeypatch):
    monkeypatch.setattr(receipts, "MAX_TRACKED_INSTALLATIONS", 2)
    moment = 1_000.0
    for index in range(3):
        assert receipts._allow(f"dev-{index}", now=moment) is True

    later = moment + receipts.RATE_WINDOW_SECONDS + 10
    assert receipts._allow("dev-new", now=later) is True

    assert len(receipts._RECENT) <= 2
