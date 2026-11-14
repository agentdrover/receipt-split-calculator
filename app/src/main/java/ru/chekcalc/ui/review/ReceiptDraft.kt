package ru.chekcalc.ui.review

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import ru.chekcalc.calc.Money
import ru.chekcalc.calc.rublesToKopecks
import ru.chekcalc.model.Receipt
import kotlin.math.abs

/**
 * Позиция в черновике: храним цену за единицу и количество, сумма считается.
 * Так правка количества или цены не теряет вторую величину и не копит ошибку.
 */
@Serializable
data class DraftItem(
    val id: Long,
    val name: String = "",
    val qty: Double? = null,
    val unitPriceKopecks: Long = 0,
) {
    /**
     * Сумма строки: количество × цена; без количества считаем одну единицу.
     * Считаем в BigDecimal: 199 ₽ × 0,205 через Double даёт 40,79 вместо 40,80.
     */
    val amountKopecks: Long
        get() = BigDecimal.valueOf(unitPriceKopecks)
            .multiply(BigDecimal.valueOf(qty ?: 1.0))
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
}

/**
 * Черновик чека на экране проверки.
 *
 * Всё в копейках: правка цены не должна накапливать ошибку округления.
 * residual = итог − (сумма позиций − скидка + обслуживание), норма — до рубля.
 */
@Serializable
data class ReceiptDraft(
    val items: List<DraftItem> = emptyList(),
    val discountKopecks: Long = 0,
    val serviceKopecks: Long = 0,
    val totalKopecks: Long = 0,
    val currency: String = "RUB",
) {
    val itemsSumKopecks: Long get() = items.sumOf { it.amountKopecks }

    val residualKopecks: Long
        get() = totalKopecks - (itemsSumKopecks - discountKopecks + serviceKopecks)

    val residualOk: Boolean get() = abs(residualKopecks) <= RESIDUAL_TOLERANCE_KOPECKS

    /** «Дальше» не блокируется расхождением — предупреждение просто остаётся. */
    val canProceed: Boolean get() = true

    fun rename(id: Long, name: String): ReceiptDraft =
        copy(items = items.map { if (it.id == id) it.copy(name = name) else it })

    fun changeUnitPrice(id: Long, kopecks: Long): ReceiptDraft =
        copy(items = items.map { if (it.id == id) it.copy(unitPriceKopecks = kopecks.coerceAtLeast(0)) else it })

    /** Количество: цена за единицу сохраняется, сумма пересчитывается. */
    fun changeQty(id: Long, qty: Double?): ReceiptDraft {
        require(qty == null || (qty.isFinite() && qty >= 0.0 && qty <= MAX_QTY)) {
            "количество должно быть числом от 0 до $MAX_QTY"
        }
        return copy(items = items.map { if (it.id == id) it.copy(qty = qty) else it })
    }

    fun changeTotal(kopecks: Long): ReceiptDraft = copy(totalKopecks = kopecks.coerceAtLeast(0))

    fun changeDiscount(kopecks: Long): ReceiptDraft = copy(discountKopecks = kopecks.coerceAtLeast(0))

    fun changeService(kopecks: Long): ReceiptDraft = copy(serviceKopecks = kopecks.coerceAtLeast(0))

    fun addItem(name: String = "Новая позиция", unitPriceKopecks: Long = 0): ReceiptDraft {
        val nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1
        return copy(items = items + DraftItem(nextId, name, qty = 1.0, unitPriceKopecks = unitPriceKopecks))
    }

    fun removeItem(id: Long): ReceiptDraft = copy(items = items.filterNot { it.id == id })

    /**
     * Текст предупреждения о расхождении; null, если всё сошлось.
     *
     * Подсказка зависит от знака разницы: если итог больше суммы позиций,
     * дело скорее в пропущенной строке или обслуживании; если меньше —
     * в скидке или неверно прочитанной цене.
     */
    fun residualWarning(): String? {
        if (residualOk) return null
        // Сравниваем с тем, что должно быть по формуле: позиции − скидка + обслуживание.
        val expected = itemsSumKopecks - discountKopecks + serviceKopecks
        val reason = if (residualKopecks > 0) {
            "в чеке больше — проверьте, не пропущена ли строка или обслуживание"
        } else {
            "в чеке меньше — проверьте скидку и цены строк"
        }
        return "По позициям ожидалось ${Money.format(expected)}, в чеке " +
            "${Money.format(totalKopecks)}: разница ${Money.format(residualKopecks)}. " +
            "Причина: $reason."
    }

    companion object {
        /** Норма расхождения — рубль (§6.3 спеки). */
        const val RESIDUAL_TOLERANCE_KOPECKS = 100L

        /** Верхняя граница количества: 1e4 × 1e11 копеек остаётся в Long. */
        const val MAX_QTY = 10_000.0

        /**
         * Позиция из распознанного чека. Количество берём, только если оно
         * разумное и воспроизводит сумму: иначе считаем позицию одной штукой,
         * чтобы распознанные деньги не потерялись и не утроились.
         */
        fun draftItemFor(id: Long, item: ru.chekcalc.model.ReceiptItem): DraftItem {
            val amountKopecks = rublesToKopecks(item.amount)
            val qty = item.qty?.takeIf { it > 0.0 && it <= MAX_QTY } ?: return DraftItem(
                id = id,
                name = item.name,
                qty = null,
                unitPriceKopecks = amountKopecks,
            )
            val candidate = item.unitPrice?.let { rublesToKopecks(it) } ?: Math.round(amountKopecks / qty)
            val restored = BigDecimal.valueOf(candidate.toLong())
                .multiply(BigDecimal.valueOf(qty))
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()
            return if (restored == amountKopecks) {
                DraftItem(id, item.name, qty, candidate)
            } else {
                DraftItem(id, item.name, null, amountKopecks)
            }
        }

        fun fromReceipt(receipt: Receipt): ReceiptDraft = ReceiptDraft(
            items = receipt.items.mapIndexed { index, item -> draftItemFor(index + 1L, item) },
            discountKopecks = rublesToKopecks(receipt.discount.amount),
            serviceKopecks = rublesToKopecks(receipt.serviceCharge.amount),
            totalKopecks = rublesToKopecks(receipt.total),
            currency = receipt.currency,
        )
    }
}
