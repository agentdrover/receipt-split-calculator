package ru.chekcalc.calc

import java.math.BigDecimal
import java.math.RoundingMode

/** Позиция чека и доля пользователя в ней: 0 — не моё, 1 — целиком, 1/k — общее. */
data class CalcLine(
    val amountKopecks: Long,
    val share: Double = 0.0,
)

/** Всё, что нужно для расчёта своей части. */
data class CalcInput(
    val lines: List<CalcLine> = emptyList(),
    val discountKopecks: Long = 0,
    val serviceKopecks: Long = 0,
    val tipKopecks: Long = 0,
)

/** Разбивка для экрана итога; всё в копейках. */
data class Breakdown(
    val itemsKopecks: Long,
    val discountKopecks: Long,
    val serviceKopecks: Long,
    val tipKopecks: Long,
    val totalKopecks: Long,
)

/**
 * Чистый расчёт доли: без UI, сети и хранения.
 *
 * Скидка и обслуживание распределяются пропорционально своей части, чаевые
 * прибавляются к ней, округление — только на итоге и до рубля (§6.2–6.4 спеки).
 */
object ShareCalculator {

    fun calculate(input: CalcInput): Breakdown {
        val subtotal = BigDecimal.valueOf(input.lines.sumOf { it.amountKopecks })
        val mine = input.lines.fold(BigDecimal.ZERO) { acc, line ->
            acc + BigDecimal.valueOf(line.amountKopecks) * BigDecimal.valueOf(line.share)
        }

        val discount = BigDecimal.valueOf(input.discountKopecks)
        val service = BigDecimal.valueOf(input.serviceKopecks)

        val afterDiscount = when {
            subtotal.signum() == 0 -> mine
            else -> mine * (BigDecimal.ONE - ratio(discount, subtotal))
        }

        val base = subtotal - discount
        val withService = when {
            base.signum() <= 0 -> afterDiscount
            else -> afterDiscount * (BigDecimal.ONE + ratio(service, base))
        }

        val myDiscount = mine - afterDiscount
        val myService = withService - afterDiscount
        val total = roundToRuble(withService + BigDecimal.valueOf(input.tipKopecks))

        return Breakdown(
            itemsKopecks = mine.setScale(0, RoundingMode.HALF_UP).toLong(),
            discountKopecks = myDiscount.setScale(0, RoundingMode.HALF_UP).toLong(),
            serviceKopecks = myService.setScale(0, RoundingMode.HALF_UP).toLong(),
            tipKopecks = input.tipKopecks,
            totalKopecks = total,
        )
    }

    /** residual = итог чека − (позиции − скидка + обслуживание). Больше 1 ₽ — чек не сошёлся. */
    fun residualKopecks(receiptTotal: Long, linesSum: Long, discount: Long, service: Long): Long =
        receiptTotal - (linesSum - discount + service)

    private fun ratio(part: BigDecimal, whole: BigDecimal): BigDecimal =
        part.divide(whole, 10, RoundingMode.HALF_UP)

    private fun roundToRuble(value: BigDecimal): Long =
        value.setScale(-2, RoundingMode.HALF_UP).toLong()
}
