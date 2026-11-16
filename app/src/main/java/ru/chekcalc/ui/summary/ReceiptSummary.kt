package ru.chekcalc.ui.summary

import kotlinx.serialization.Serializable
import ru.chekcalc.calc.Money
import ru.chekcalc.storage.Payer
import ru.chekcalc.ui.select.Choice
import ru.chekcalc.ui.select.SelectionState

/**
 * Готовая к показу разбивка суммы.
 *
 * Считается один раз из выбора: экран итога ничего не пересчитывает сам,
 * иначе цифры разъедутся с тем, что человек видел на предыдущем шаге.
 */
@Serializable
data class ReceiptSummary(
    val itemsShareKopecks: Long = 0,
    val discountKopecks: Long = 0,
    val serviceKopecks: Long = 0,
    val tipKopecks: Long = 0,
    val totalKopecks: Long = 0,
    val currency: String = "RUB",
    val ownItems: Int = 0,
    val sharedItems: Int = 0,
) {
    /** Строки разбивки: подпись и сумма; пустые строки не показываем. */
    fun lines(): List<Pair<String, String>> =
        buildList {
            add("Ваши позиции" to Money.format(itemsShareKopecks, currency))
            if (discountKopecks > 0) add("Скидка" to "−" + Money.format(discountKopecks, currency))
            if (serviceKopecks > 0) add("Обслуживание" to Money.format(serviceKopecks, currency))
            if (tipKopecks > 0) add("Чаевые" to Money.format(tipKopecks, currency))
            add("Итого" to Money.format(totalKopecks, currency))
        }

    /**
     * Текст для копирования и шаринга по §FR-6: сумма, слагаемые и реквизиты,
     * чтобы получатель понял, кому и сколько переводить, без приложения.
     */
    fun shareText(payer: Payer = Payer()): String {
        // Строки считаем снаружи buildString: внутри него lines() разрешается
        // в расширение String и тип теряется.
        val rows = lines()
        return buildString {
            appendLine("Расчёт по чеку")
            for ((label, value) in rows) {
                appendLine("$label: $value")
            }
            // Итог уже есть строкой выше: вторая такая же сумма — шум (#2327).
            val who = payer.display()
            if (who.isNotBlank()) append("Перевод на $who")
        }
    }

    companion object {
        fun from(state: SelectionState): ReceiptSummary =
            ReceiptSummary(
                itemsShareKopecks = state.itemsShareExactKopecks,
                discountKopecks = state.discountShareKopecks,
                serviceKopecks = state.serviceShareKopecks,
                tipKopecks = state.tipKopecks,
                totalKopecks = state.totalKopecks,
                currency = state.currency,
                ownItems = state.items.count { it.choice == Choice.Whole },
                sharedItems = state.items.count { it.choice is Choice.Shared },
            )
    }
}
