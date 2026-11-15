package ru.chekcalc.calc

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
    @Test
    fun `формат с валютой чека`() {
        assertEquals("1240,50 ₽", Money.format(124_050))
        assertEquals("1240,50 ₽", Money.format(124_050, "RUB"))
        assertEquals("174600 KRW", Money.format(17_460_000, "KRW"))
        assertEquals("12,34 USD", Money.format(1_234, "USD"))
    }

    @Test
    fun `целые рубли печатаются без копеек`() {
        assertEquals("1394 ₽", Money.format(139_400))
    }

    @Test
    fun `копейки печатаются двумя знаками`() {
        assertEquals("12,34 ₽", Money.format(1_234))
    }

    @Test
    fun `минус остаётся перед числом`() {
        assertEquals("−128 ₽", Money.format(-12_800))
    }

    @Test
    fun `минимальный Long не ломает формат`() {
        assertEquals("−92233720368547758,08 ₽", Money.format(Long.MIN_VALUE))
    }
}
