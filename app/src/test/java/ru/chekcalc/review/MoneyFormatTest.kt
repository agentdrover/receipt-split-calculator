package ru.chekcalc.review

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.chekcalc.ui.review.formatKopecks
import ru.chekcalc.ui.review.formatRublesGrouped

class MoneyFormatTest {

    @Test
    fun `сумма с копейками и разрядами как в макете`() {
        assertEquals("1 240,00 ₽", formatKopecks(124_000))
        assertEquals("590,00 ₽", formatKopecks(59_000))
        assertEquals("1 240,50 ₽", formatKopecks(124_050))
        assertEquals("1 234 567,89 ₽", formatKopecks(123_456_789))
        assertEquals("0,00 ₽", formatKopecks(0))
    }

    @Test
    fun `подзаголовок с разрядами без лишних копеек`() {
        assertEquals("1 240 ₽", formatRublesGrouped(124_000))
        assertEquals("1 240,50 ₽", formatRublesGrouped(124_050))
        assertEquals("1 234 567 ₽", formatRublesGrouped(123_456_700))
        assertEquals("0 ₽", formatRublesGrouped(0))
    }
}
