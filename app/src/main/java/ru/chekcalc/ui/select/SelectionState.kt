package ru.chekcalc.ui.select

import kotlinx.serialization.Serializable
import ru.chekcalc.calc.CalcInput
import ru.chekcalc.calc.CalcLine
import ru.chekcalc.calc.ShareCalculator
import ru.chekcalc.ui.review.ReceiptDraft
import java.math.BigDecimal
import java.math.RoundingMode

/** Чья позиция: не моя, целиком моя или общая на нескольких. */
@Serializable
sealed interface Choice {
    @Serializable
    data object None : Choice

    @Serializable
    data object Whole : Choice

    /** Общая на [denominator] человек: доля 1/denominator. */
    @Serializable
    data class Shared(val denominator: Int) : Choice
}

@Serializable
data class SelectableItem(
    val id: Long,
    val name: String,
    val amountKopecks: Long,
    val choice: Choice = Choice.None,
)

/**
 * Выбор своих позиций и чаевые.
 *
 * Деньги считает ядро: здесь только то, что человек отметил. Проценты чаевых
 * берутся от своей части до чаевых — так их и понимают в чеке.
 */
@Serializable
data class SelectionState(
    val items: List<SelectableItem> = emptyList(),
    val discountKopecks: Long = 0,
    val serviceKopecks: Long = 0,
    val tipPercent: Int? = null,
    val tipFixedKopecks: Long = 0,
) {
    val denominatorChoices: List<Int> get() = listOf(2, 3, 4, 5, 6)

    /** Галочка: снять можно и целую позицию, и общую с долей. */
    fun toggleWhole(id: Long): SelectionState = copy(
        items = items.map { item ->
            when {
                item.id != id -> item
                item.choice is Choice.None -> item.copy(choice = Choice.Whole)
                else -> item.copy(choice = Choice.None)
            }
        },
    )

    fun setShared(id: Long, denominator: Int): SelectionState {
        require(denominator in 2..6) { "доля 1/2…1/6" }
        return copy(
            items = items.map { item ->
                when {
                    item.id != id -> item
                    item.choice == Choice.Shared(denominator) -> item.copy(choice = Choice.None)
                    else -> item.copy(choice = Choice.Shared(denominator))
                }
            },
        )
    }

    fun setTipPercent(percent: Int?): SelectionState {
        require(percent == null || percent in 0..100) { "процент чаевых 0…100" }
        return copy(tipPercent = percent, tipFixedKopecks = 0)
    }

    fun setTipFixed(kopecks: Long): SelectionState =
        copy(tipFixedKopecks = kopecks.coerceAtLeast(0), tipPercent = null)

    /** Вклад позиций без чаевых и без скидки/обслуживания. */
    val itemsShareKopecks: Long
        get() = ShareCalculator.calculate(
            CalcInput(lines = lines()),
        ).totalKopecks

    /** Своя часть в копейках точно, без округления — база для процента. */
    val billShareExactKopecks: BigDecimal
        get() = ShareCalculator.totalExact(
            CalcInput(
                lines = lines(),
                discountKopecks = discountKopecks,
                serviceKopecks = serviceKopecks,
            ),
        )

    /** Своя часть чека до чаевых: скидка и обслуживание распределены. */
    val billShareKopecks: Long
        get() = ShareCalculator.calculate(
            CalcInput(
                lines = lines(),
                discountKopecks = discountKopecks,
                serviceKopecks = serviceKopecks,
            ),
        ).totalKopecks

    /** Чаевые точно, без округления: округляем только итог (§6.4). */
    val tipExact: BigDecimal
        get() {
            tipPercent?.let { percent ->
                if (percent == 0) return BigDecimal.ZERO
                // Десяти знаков не хватало: сумма «чуть ниже» 10950 уходила
                // вниз при округлении итога. Считаем с запасом.
                return billShareExactKopecks
                    .multiply(BigDecimal.valueOf(percent.toLong()))
                    .divide(BigDecimal.valueOf(100), 40, RoundingMode.HALF_UP)
            }
            return BigDecimal.valueOf(tipFixedKopecks)
        }

    /** Чаевые для показа: 10,05 ₽. */
    val tipKopecks: Long
        get() = tipExact.setScale(0, RoundingMode.HALF_UP).toLong()

    val totalKopecks: Long
        get() {
            val percent = tipPercent
            if (percent != null) {
                // Одна дробь на всё: иначе приближения теряют рубль на ровной границе.
                return ShareCalculator.totalWithPercentTip(baseInput(), percent)
                    .setScale(-2, RoundingMode.HALF_UP)
                    .toLong()
            }
            return (billShareExactKopecks + BigDecimal.valueOf(tipFixedKopecks))
                .setScale(-2, RoundingMode.HALF_UP)
                .toLong()
        }

    private fun baseInput() = CalcInput(
        lines = lines(),
        discountKopecks = discountKopecks,
        serviceKopecks = serviceKopecks,
    )

    private fun lines(): List<CalcLine> = items.map { item ->
        when (val choice = item.choice) {
            is Choice.None -> CalcLine(item.amountKopecks, 0, 1)
            is Choice.Whole -> CalcLine(item.amountKopecks, 1, 1)
            is Choice.Shared -> CalcLine(item.amountKopecks, 1, choice.denominator)
        }
    }

    companion object {
        fun fromDraft(draft: ReceiptDraft): SelectionState = SelectionState(
            items = draft.items.map { SelectableItem(it.id, it.name, it.amountKopecks) },
            discountKopecks = draft.discountKopecks,
            serviceKopecks = draft.serviceKopecks,
        )
    }
}
