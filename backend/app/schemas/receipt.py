"""Схема receipt.v1 — контракт между провайдером распознавания и приложением."""

from datetime import datetime
from decimal import Decimal
from typing import Annotated

from pydantic import BaseModel, Field, PlainSerializer, field_validator

# В JSON суммы и количества — числа, а не строки: клиент на Kotlin читает их
# как Double. Внутри считаем Decimal, чтобы не ловить 0.1 + 0.2.
Number = Annotated[
    Decimal,
    PlainSerializer(lambda value: float(value), return_type=float, when_used="json"),
]


class ReceiptItem(BaseModel):
    """Одна позиция чека. ``qty=None`` — строку не прочитали, но сумма известна."""

    name: str = Field(min_length=1, max_length=200)
    qty: Number | None = Field(default=None, ge=0)
    unit_price: Number | None = Field(default=None, ge=0)
    amount: Number = Field(ge=0)


class Discount(BaseModel):
    present: bool = False
    amount: Number = Field(default=Decimal("0"), ge=0)


class ServiceCharge(BaseModel):
    present: bool = False
    percent: Number | None = Field(default=None, ge=0, le=100)
    amount: Number = Field(default=Decimal("0"), ge=0)


class Receipt(BaseModel):
    """Чек целиком. Пустой ``items`` допустим: нечитаемый чек решает клиент."""

    merchant: str | None = None
    date: datetime | None = None
    currency: str = "RUB"
    items: list[ReceiptItem] = Field(default_factory=list)
    discount: Discount = Field(default_factory=Discount)
    service_charge: ServiceCharge = Field(default_factory=ServiceCharge)
    total: Number = Field(ge=0)
    confidence: float | None = Field(default=None, ge=0, le=1)
    warnings: list[str] = Field(default_factory=list)

    @field_validator("currency")
    @classmethod
    def currency_is_upper(cls, value: str) -> str:
        return value.strip().upper()
