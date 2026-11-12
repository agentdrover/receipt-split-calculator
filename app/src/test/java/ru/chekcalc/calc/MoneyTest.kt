package ru.chekcalc.calc

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
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
