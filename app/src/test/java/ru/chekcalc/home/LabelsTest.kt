package ru.chekcalc.home

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.chekcalc.ui.home.historyLabel
import ru.chekcalc.ui.home.positionsLabel

class LabelsTest {

    @Test
    fun `позиции склоняются по числу`() {
        assertEquals("1 позиция", positionsLabel(1))
        assertEquals("2 позиции", positionsLabel(2))
        assertEquals("4 позиции", positionsLabel(4))
        assertEquals("5 позиций", positionsLabel(5))
        assertEquals("11 позиций", positionsLabel(11))
        assertEquals("21 позиция", positionsLabel(21))
        assertEquals("22 позиции", positionsLabel(22))
    }

    @Test
    fun `история склоняется по числу`() {
        assertEquals("пока пусто", historyLabel(0))
        assertEquals("1 сохранённый чек", historyLabel(1))
        assertEquals("2 сохранённых чека", historyLabel(2))
        assertEquals("4 сохранённых чека", historyLabel(4))
        assertEquals("5 сохранённых чеков", historyLabel(5))
        assertEquals("11 сохранённых чеков", historyLabel(11))
        assertEquals("21 сохранённый чек", historyLabel(21))
        assertEquals("22 сохранённых чека", historyLabel(22))
    }
}
