package ru.chekcalc.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Контракт receipt.v1 — то, что провайдер возвращает и что приложение разбирает.
 *
 * Суммы здесь в единицах валюты (как в чеке), а не в копейках: конвертация
 * в целые копейки — забота расчёта, а не разбора.
 */
@Serializable
data class Receipt(
    val merchant: String? = null,
    val date: String? = null,
    val currency: String,
    val items: List<ReceiptItem>,
    val discount: Discount = Discount(),
    @SerialName("service_charge") val serviceCharge: ServiceCharge = ServiceCharge(),
    val total: Double,
    val confidence: Double? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class ReceiptItem(
    val name: String,
    val qty: Double? = null,
    @SerialName("unit_price") val unitPrice: Double? = null,
    val amount: Double,
)

@Serializable
data class Discount(
    val present: Boolean = false,
    val amount: Double = 0.0,
)

@Serializable
data class ServiceCharge(
    val present: Boolean = false,
    val percent: Double? = null,
    val amount: Double = 0.0,
)
