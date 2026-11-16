package ru.chekcalc.ui.manual

import ru.chekcalc.model.Discount
import ru.chekcalc.model.Receipt
import ru.chekcalc.model.ReceiptItem
import ru.chekcalc.model.ServiceCharge
import ru.chekcalc.ui.review.ReceiptDraft
import java.math.BigDecimal

/**
 * Ручной чек из формы → контракт receipt.v1.
 *
 * Приложение строит тот же Receipt, что приходит от провайдера: экран проверки
 * и расчёт не знают, откуда чек. Форма живёт в копейках, контракт — в рублях,
 * поэтому перевод сумм собран в одном месте.
 */
object ManualReceipt {
    /** Предел схемы receipt.v1: суммы в рублях не больше миллиарда. */
    const val MAX_AMOUNT_KOPECKS = 100_000_000_000L

    /**
     * Строка, которую человек ещё не заполнил: ни названия, ни денег.
     * Такие строки не попадают в чек и не мешают переходу.
     */
    private fun ReceiptDraft.filledItems() = items.filter { it.name.isNotBlank() || it.amountKopecks != 0L }

    /**
     * Можно ли показать экран проверки: есть хотя бы одна строка, у всех строк
     * есть название, количество не ноль и суммы не выходят за пределы схемы.
     * Ноль штук и сумма сверх миллиарда переживают форму плохо: первый теряет
     * цену при обратном разборе, второй схема receipt.v1 отвергает.
     */
    fun isReady(draft: ReceiptDraft): Boolean {
        val items = draft.filledItems()
        return items.isNotEmpty() &&
            items.all { it.name.isNotBlank() } &&
            items.all { it.qty == null || it.qty > 0.0 } &&
            items.all { it.amountKopecks in 0..MAX_AMOUNT_KOPECKS } &&
            draft.discountKopecks in 0..MAX_AMOUNT_KOPECKS &&
            draft.serviceKopecks in 0..MAX_AMOUNT_KOPECKS &&
            draft.totalKopecks in 0..MAX_AMOUNT_KOPECKS
    }

    fun toReceipt(draft: ReceiptDraft): Receipt {
        val items =
            draft.filledItems().map { item ->
                ReceiptItem(
                    name = item.name.trim(),
                    qty = item.qty,
                    // Цена за единицу имеет смысл только вместе с количеством:
                    // без него сумма строки — единственная правда.
                    unitPrice = item.qty?.let { rubles(item.unitPriceKopecks) },
                    amount = rubles(item.amountKopecks),
                )
            }
        return Receipt(
            currency = draft.currency.ifBlank { "RUB" },
            items = items,
            discount =
                Discount(
                    present = draft.discountKopecks > 0,
                    amount = rubles(draft.discountKopecks),
                ),
            serviceCharge =
                ServiceCharge(
                    present = draft.serviceKopecks > 0,
                    amount = rubles(draft.serviceKopecks),
                ),
            total = rubles(draft.totalKopecks),
        )
    }

    /** Копейки → рубли без хвоста двоичной погрешности: 39025 → 390.25. */
    private fun rubles(kopecks: Long): Double = BigDecimal.valueOf(kopecks).movePointLeft(2).toDouble()
}
