package ru.chekcalc.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCalculatorTest {

    @Test
    fun `все позиции целиком дают итог чека`() {
        val input = CalcInput(
            lines = listOf(
                CalcLine(78_000, 1.0),
                CalcLine(69_000, 1.0),
                CalcLine(60_000, 1.0),
                CalcLine(45_000, 1.0),
            ),
            discountKopecks = 25_200,
            serviceKopecks = 22_700,
        )

        val result = ShareCalculator.calculate(input)

        // 2520 − 252 + 227 = 2495 ₽
        assertEquals(249_500, result.totalKopecks)
    }

    @Test
    fun `скидка распределяется пропорционально доле`() {
        val input = CalcInput(
            lines = listOf(CalcLine(300_000, 1.0 / 3)),
            discountKopecks = 30_000,
        )

        val result = ShareCalculator.calculate(input)

        assertEquals(10_000, result.discountKopecks)
        assertEquals(90_000, result.totalKopecks)
    }

    @Test
    fun `обслуживание добавляется к своей части`() {
        val input = CalcInput(
            lines = listOf(CalcLine(100_000, 1.0)),
            serviceKopecks = 10_000,
        )

        val result = ShareCalculator.calculate(input)

        assertEquals(110_000, result.totalKopecks)
    }

    @Test
    fun `чаевые прибавляются к своей части`() {
        val input = CalcInput(
            lines = listOf(CalcLine(100_000, 1.0)),
            tipKopecks = 10_000,
        )

        assertEquals(110_000, ShareCalculator.calculate(input).totalKopecks)
    }

    @Test
    fun `ничего своего остаются только чаевые`() {
        val input = CalcInput(
            lines = listOf(CalcLine(100_000, 0.0), CalcLine(50_000, 0.0)),
            discountKopecks = 10_000,
            serviceKopecks = 5_000,
            tipKopecks = 7_000,
        )

        assertEquals(7_000, ShareCalculator.calculate(input).totalKopecks)
    }

    @Test
    fun `скидка на весь чек обнуляет позиции`() {
        val input = CalcInput(
            lines = listOf(CalcLine(100_000, 1.0)),
            discountKopecks = 100_000,
            tipKopecks = 4_500,
        )

        val result = ShareCalculator.calculate(input)

        assertEquals(0, result.itemsKopecks - result.discountKopecks)
        assertEquals(4_500, result.totalKopecks)
    }

    @Test
    fun `пустой чек даёт ноль`() {
        assertEquals(0, ShareCalculator.calculate(CalcInput()).totalKopecks)
    }

    @Test
    fun `увеличение доли не уменьшает итог`() {
        val lines = listOf(CalcLine(100_000), CalcLine(50_000))
        var previous = Long.MIN_VALUE
        for (step in 0..10) {
            val share = step / 10.0
            val total = ShareCalculator.calculate(
                CalcInput(lines = listOf(lines[0].copy(share = share), lines[1]), tipKopecks = 1_000),
            ).totalKopecks
            assertTrue("итог упал на доле $share", total >= previous)
            previous = total
        }
    }

    @Test
    fun `итог округляется до рубля`() {
        val input = CalcInput(lines = listOf(CalcLine(149_999, 1.0)))

        // 1499,99 ₽ → 1500 ₽
        assertEquals(150_000, ShareCalculator.calculate(input).totalKopecks)
    }

    @Test
    fun `residual считает расхождение с итогом чека`() {
        assertEquals(0, ShareCalculator.residualKopecks(249_500, 252_000, 25_200, 22_700))
        assertEquals(5_000, ShareCalculator.residualKopecks(249_500, 247_000, 25_200, 22_700))
    }
}
