package ru.chekcalc.ui.review

import kotlinx.serialization.Serializable
import ru.chekcalc.calc.Money
import ru.chekcalc.calc.rublesToKopecks
import ru.chekcalc.model.Receipt
import java.math.BigDecimal
import java.math.RoundingMode
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
        get() =
            BigDecimal
                .valueOf(unitPriceKopecks)
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

    /**
     * Невозможные данные — блокируют «Дальше» и игнорируют расхождение.
     *
     * Это не то же самое, что расхождение OCR: пропущенная строка объясняется
     * предупреждением и правится, а отрицательная скидка или обслуживание без
     * позиций — ошибка ввода, с которой считать нельзя.
     */
    val blockingError: String?
        get() =
            when {
                items.isEmpty() -> "В чеке нет ни одной позиции — добавьте строку или исправьте чек"
                discountKopecks < 0 || serviceKopecks < 0 || totalKopecks < 0 ->
                    "Суммы не могут быть отрицательными — исправьте"
                discountKopecks > itemsSumKopecks -> "Скидка больше суммы позиций — исправьте"
                itemsSumKopecks == 0L && serviceKopecks > 0 ->
                    "Обслуживание без позиций — исправьте"
                else -> null
            }

    /** «Дальше» блокируется только невозможными данными, не расхождением OCR. */
    val canProceed: Boolean get() = blockingError == null

    fun rename(
        id: Long,
        name: String,
    ): ReceiptDraft = copy(items = items.map { if (it.id == id) it.copy(name = name) else it })

    fun changeUnitPrice(
        id: Long,
        kopecks: Long,
    ): ReceiptDraft =
        copy(items = items.map { if (it.id == id) it.copy(unitPriceKopecks = kopecks.coerceAtLeast(0)) else it })

    /** Количество: цена за единицу сохраняется, сумма пересчитывается. */
    fun changeQty(
        id: Long,
        qty: Double?,
    ): ReceiptDraft {
        require(qty == null || (qty.isFinite() && qty >= 0.0 && qty <= MAX_QTY)) {
            "количество должно быть числом от 0 до $MAX_QTY"
        }
        return copy(items = items.map { if (it.id == id) it.copy(qty = qty) else it })
    }

    fun changeTotal(kopecks: Long): ReceiptDraft = copy(totalKopecks = kopecks.coerceAtLeast(0))

    fun changeDiscount(kopecks: Long): ReceiptDraft = copy(discountKopecks = kopecks.coerceAtLeast(0))

    fun changeService(kopecks: Long): ReceiptDraft = copy(serviceKopecks = kopecks.coerceAtLeast(0))

    fun addItem(
        name: String = "Новая позиция",
        unitPriceKopecks: Long = 0,
    ): ReceiptDraft {
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
        // Подсказка называет только те поля, которые вообще участвуют в расчёте:
        // при скидке бессмысленно искать «пропущенную строку», а без неё —
        // отправлять человека к скидке.
        val reason =
            when {
                discountKopecks > 0 && serviceKopecks > 0 ->
                    "расхождение может быть в скидке, обслуживании или цене строки — проверьте их"
                discountKopecks > 0 ->
                    "расхождение может быть в скидке или цене строки — проверьте их"
                serviceKopecks > 0 ->
                    "расхождение может быть в обслуживании или цене строки — проверьте их"
                residualKopecks > 0 ->
                    "в чеке больше — возможно, строка не распознана"
                else ->
                    "в чеке меньше — возможно, цена строки прочитана неверно"
            }
        return "По позициям ожидалось ${Money.format(expected, currency)}, в чеке " +
            "${Money.format(totalKopecks, currency)}: разница " +
            "${Money.format(residualKopecks, currency)}. Причина: $reason."
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
        fun draftItemFor(
            id: Long,
            item: ru.chekcalc.model.ReceiptItem,
        ): DraftItem {
            val amountKopecks = rublesToKopecks(item.amount)
            val qty =
                item.qty?.takeIf { it > 0.0 && it <= MAX_QTY } ?: return DraftItem(
                    id = id,
                    name = item.name,
                    qty = null,
                    unitPriceKopecks = amountKopecks,
                )
            val candidate = item.unitPrice?.let { rublesToKopecks(it) } ?: Math.round(amountKopecks / qty)
            val restored =
                BigDecimal
                    .valueOf(candidate.toLong())
                    .multiply(BigDecimal.valueOf(qty))
                    .setScale(0, RoundingMode.HALF_UP)
                    .toLong()
            return if (restored == amountKopecks) {
                DraftItem(id, item.name, qty, candidate)
            } else {
                DraftItem(id, item.name, null, amountKopecks)
            }
        }

        fun fromReceipt(receipt: Receipt): ReceiptDraft =
            ReceiptDraft(
                items = receipt.items.mapIndexed { index, item -> draftItemFor(index + 1L, item) },
                // Как и RecognitionClient: сумма без present — это ноль, а не скидка.
                discountKopecks =
                    if (receipt.discount.present) {
                        rublesToKopecks(receipt.discount.amount)
                    } else {
                        0
                    },
                serviceKopecks =
                    if (receipt.serviceCharge.present) {
                        rublesToKopecks(receipt.serviceCharge.amount)
                    } else {
                        0
                    },
                totalKopecks = rublesToKopecks(receipt.total),
                currency = receipt.currency,
            )
    }
}

/**
 * Что изменилось после глубокого распознавания: сколько позиций добавилось
 * и как поехало расхождение с итогом. Одной строкой, чтобы человек не
 * сравнивал два экрана глазами.
 */
fun deepRecognizeSummary(
    before: ReceiptDraft,
    after: ReceiptDraft,
): String {
    val added = after.items.size - before.items.size
    val residualBefore = before.residualKopecks
    val residualAfter = after.residualKopecks
    val items =
        when {
            added > 0 -> "добавлено позиций: $added"
            added < 0 -> "позиций стало меньше на ${-added}"
            else -> "позиций столько же"
        }
    return if (residualBefore == residualAfter) {
        "$items; расхождение с итогом не изменилось"
    } else {
        val was = Money.format(residualBefore, before.currency)
        val now = Money.format(residualAfter, after.currency)
        "$items; расхождение: было $was, стало $now"
    }
}
