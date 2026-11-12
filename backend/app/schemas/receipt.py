"""Схема receipt.v1 — контракт между провайдером распознавания и приложением."""

from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, Field, field_validator


class ReceiptItem(BaseModel):
    """Одна позиция чека. ``qty=None`` — строку не прочитали, но сумма известна."""

    name: str = Field(min_length=1, max_length=200)
    qty: Decimal | None = Field(default=None, ge=0)
    unit_price: Decimal | None = Field(default=None, ge=0)
    amount: Decimal = Field(ge=0)


class Discount(BaseModel):
    present: bool = False
    amount: Decimal = Field(default=Decimal("0"), ge=0)


class ServiceCharge(BaseModel):
    present: bool = False
    percent: Decimal | None = Field(default=None, ge=0, le=100)
    amount: Decimal = Field(default=Decimal("0"), ge=0)


class Receipt(BaseModel):
    """Чек целиком. Обязательны только позиции, валюта и итог."""

    merchant: str | None = None
    date: datetime | None = None
    currency: str = "RUB"
    items: list[ReceiptItem] = Field(min_length=1)
    discount: Discount = Field(default_factory=Discount)
    service_charge: ServiceCharge = Field(default_factory=ServiceCharge)
    total: Decimal = Field(ge=0)
    confidence: float | None = Field(default=None, ge=0, le=1)
    warnings: list[str] = Field(default_factory=list)

    @field_validator("currency")
    @classmethod
    def currency_is_upper(cls, value: str) -> str:
        return value.strip().upper()
