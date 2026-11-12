package ru.chekcalc.calc

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Позиция чека и доля пользователя в ней, заданная дробью.
 *
 * Дробь, а не Double: 1/3 в Double — это 0.3333333333333333, и на чеке
 * 301,50 ₽ такая доля даёт 100,49 вместо 100,50. Числитель и знаменатель
 * маленькие: 1/2, 1/3, …, 1/6.
 */
data class CalcLine(
    val amountKopecks: Long,
    val shareNumerator: Int = 0,
    val shareDenominator: Int = 1,
) {
    init {
        require(shareDenominator > 0) { "знаменатель доли должен быть положительным" }
        require(shareNumerator >= 0) { "доля не может быть отрицательной" }
    }

    internal val share: Fraction
        get() = Fraction.of(shareNumerator.toLong(), shareDenominator.toLong())
}

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
 * прибавляются к ней, округление — один раз, на итоге и до рубля (§6.2–6.4).
 * Промежуточные величины считаются точными дробями, чтобы не сдвинуть half-up.
 */
object ShareCalculator {

    fun calculate(input: CalcInput): Breakdown {
        val subtotal = input.lines.sumOf { it.amountKopecks }
        val mine = input.lines.fold(Fraction.ZERO) { acc, line ->
            acc + Fraction.of(line.amountKopecks) * line.share
        }

        val sub = Fraction.of(subtotal)
        val discount = Fraction.of(input.discountKopecks)
        val service = Fraction.of(input.serviceKopecks)
        val base = sub - discount

        val afterDiscount = if (sub.isZero) mine else mine * (sub - discount) / sub

        // При скидке на весь чек база обнуляется, и делить на неё нельзя.
        // Тогда обслуживание распределяем по доле в сумме позиций: кто взял
        // весь чек, тот платит и всё обслуживание.
        val withService = when {
            !base.isZero -> afterDiscount * (base + service) / base
            service.isZero -> afterDiscount
            sub.isZero -> afterDiscount
            else -> afterDiscount + service * (mine / sub)
        }

        val myDiscount = mine - afterDiscount
        val myService = withService - afterDiscount

        return Breakdown(
            itemsKopecks = mine.roundKopecks(),
            discountKopecks = myDiscount.roundKopecks(),
            serviceKopecks = myService.roundKopecks(),
            tipKopecks = input.tipKopecks,
            totalKopecks = (withService + Fraction.of(input.tipKopecks)).roundToRuble(),
        )
    }

    /** residual = итог чека − (позиции − скидка + обслуживание). Больше 1 ₽ — чек не сошёлся. */
    fun residualKopecks(receiptTotal: Long, linesSum: Long, discount: Long, service: Long): Long =
        receiptTotal - (linesSum - discount + service)
}

/** Точная дробь на BigInteger: без потери точности до самого округления. */
internal class Fraction private constructor(
    private val numerator: BigInteger,
    private val denominator: BigInteger,
) {
    val isZero: Boolean get() = numerator.signum() == 0

    operator fun plus(other: Fraction): Fraction = Fraction(
        numerator * other.denominator + other.numerator * denominator,
        denominator * other.denominator,
    )

    operator fun minus(other: Fraction): Fraction = Fraction(
        numerator * other.denominator - other.numerator * denominator,
        denominator * other.denominator,
    )

    operator fun times(other: Fraction): Fraction =
        Fraction(numerator * other.numerator, denominator * other.denominator)

    operator fun div(other: Fraction): Fraction {
        require(!other.isZero) { "деление на ноль" }
        return Fraction(numerator * other.denominator, denominator * other.numerator)
    }

    fun roundKopecks(): Long = toBigDecimal().setScale(0, RoundingMode.HALF_UP).toLong()

    fun roundToRuble(): Long = toBigDecimal().setScale(-2, RoundingMode.HALF_UP).toLong()

    private fun toBigDecimal(): BigDecimal =
        BigDecimal(numerator).divide(BigDecimal(denominator), 40, RoundingMode.HALF_UP)

    companion object {
        val ZERO = Fraction(BigInteger.ZERO, BigInteger.ONE)

        fun of(value: Long): Fraction = Fraction(BigInteger.valueOf(value), BigInteger.ONE)

        fun of(numerator: Long, denominator: Long): Fraction =
            Fraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator))
    }
}
