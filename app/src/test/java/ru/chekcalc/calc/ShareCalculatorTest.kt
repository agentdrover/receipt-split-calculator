package ru.chekcalc.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCalculatorTest {

    @Test
    fun `все позиции целиком дают итог чека`() {
        val input = CalcInput(
            lines = listOf(
                CalcLine(78_000, 1, 1),
                CalcLine(69_000, 1, 1),
                CalcLine(60_000, 1, 1),
                CalcLine(45_000, 1, 1),
            ),
            discountKopecks = 25_200,
            serviceKopecks = 22_700,
        )

        // 2520 − 252 + 227 = 2495 ₽
        assertEquals(249_500, ShareCalculator.calculate(input).totalKopecks)
    }

    @Test
    fun `скидка распределяется пропорционально доле`() {
        val input = CalcInput(
            lines = listOf(CalcLine(300_000, 1, 3)),
            discountKopecks = 30_000,
        )

        val result = ShareCalculator.calculate(input)

        assertEquals(10_000, result.discountKopecks)
        assertEquals(90_000, result.totalKopecks)
    }

    @Test
    fun `обслуживание добавляется к своей части`() {
        val input = CalcInput(
            lines = listOf(CalcLine(100_000, 1, 1)),
            serviceKopecks = 10_000,
        )

        assertEquals(110_000, ShareCalculator.calculate(input).totalKopecks)
    }

    @Test
    fun `чаевые прибавляются к своей части`() {
        val input = CalcInput(
            lines = listOf(CalcLine(100_000, 1, 1)),
            tipKopecks = 10_000,
        )

        assertEquals(110_000, ShareCalculator.calculate(input).totalKopecks)
    }

    @Test
    fun `ничего своего остаются только чаевые`() {
        val input = CalcInput(
            lines = listOf(CalcLine(100_000, 0, 1), CalcLine(50_000, 0, 1)),
            discountKopecks = 10_000,
            serviceKopecks = 5_000,
            tipKopecks = 7_000,
        )

        assertEquals(7_000, ShareCalculator.calculate(input).totalKopecks)
    }

    @Test
    fun `скидка на весь чек обнуляет позиции`() {
        val input = CalcInput(
            lines = listOf(CalcLine(100_000, 1, 1)),
            discountKopecks = 100_000,
            tipKopecks = 4_500,
        )

        val result = ShareCalculator.calculate(input)

        assertEquals(0, result.itemsKopecks - result.discountKopecks)
        assertEquals(4_500, result.totalKopecks)
    }

    @Test
    fun `скидка на весь чек не теряет обслуживание`() {
        val input = CalcInput(
            lines = listOf(CalcLine(100_000, 1, 1)),
            discountKopecks = 100_000,
            serviceKopecks = 10_000,
        )

        // 1000 − 1000 + 100 = 100 ₽: кто взял весь чек, тот платит и обслуживание
        assertEquals(10_000, ShareCalculator.calculate(input).totalKopecks)
    }

    @Test
    fun `половина рубля округляется вверх, а не вниз`() {
        // 300 − 0,50 = 299,50 ₽ → 300 ₽
        val input = CalcInput(
            lines = listOf(CalcLine(30_000, 1, 1)),
            discountKopecks = 50,
        )

        assertEquals(30_000, ShareCalculator.calculate(input).totalKopecks)
    }

    @Test
    fun `треть с копейками не теряет полтинник`() {
        // 301,50 ₽ / 3 = 100,50 ₽ → 101 ₽
        val input = CalcInput(lines = listOf(CalcLine(30_150, 1, 3)))

        assertEquals(10_100, ShareCalculator.calculate(input).totalKopecks)
    }

    @Test
    fun `пустой чек даёт ноль`() {
        assertEquals(0, ShareCalculator.calculate(CalcInput()).totalKopecks)
    }

    @Test
    fun `увеличение доли не уменьшает итог`() {
        var previous = Long.MIN_VALUE
        for (step in 0..10) {
            val total = ShareCalculator.calculate(
                CalcInput(
                    lines = listOf(CalcLine(100_000, step, 10), CalcLine(50_000, 0, 1)),
                    tipKopecks = 1_000,
                ),
            ).totalKopecks
            assertTrue("итог упал на доле $step/10", total >= previous)
            previous = total
        }
    }

    @Test
    fun `итог округляется до рубля`() {
        val input = CalcInput(lines = listOf(CalcLine(149_999, 1, 1)))

        assertEquals(150_000, ShareCalculator.calculate(input).totalKopecks)
    }

    @Test
    fun `residual считает расхождение с итогом чека`() {
        assertEquals(0, ShareCalculator.residualKopecks(249_500, 252_000, 25_200, 22_700))
        assertEquals(5_000, ShareCalculator.residualKopecks(249_500, 247_000, 25_200, 22_700))
    }
}
