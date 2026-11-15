package ru.chekcalc.history

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.chekcalc.ui.history.HistoryFailure
import ru.chekcalc.ui.history.nextFailures

class HistoryFailureTest {

    @Test
    fun `неудача добавляет свой вид`() {
        assertEquals(
            setOf(HistoryFailure.SAVE),
            nextFailures(emptySet(), HistoryFailure.SAVE, succeeded = false),
        )
        assertEquals(
            setOf(HistoryFailure.SAVE, HistoryFailure.DELETE),
            nextFailures(setOf(HistoryFailure.SAVE), HistoryFailure.DELETE, succeeded = false),
        )
    }

    @Test
    fun `успех гасит только свой вид`() {
        assertEquals(
            setOf(HistoryFailure.SAVE),
            nextFailures(setOf(HistoryFailure.SAVE, HistoryFailure.DELETE), HistoryFailure.DELETE, succeeded = true),
        )
        assertEquals(
            emptySet<HistoryFailure>(),
            nextFailures(setOf(HistoryFailure.SAVE), HistoryFailure.SAVE, succeeded = true),
        )
    }

    @Test
    fun `успешная очистка снимает и ошибку удаления`() {
        assertEquals(
            emptySet<HistoryFailure>(),
            nextFailures(setOf(HistoryFailure.DELETE), HistoryFailure.CLEAR, succeeded = true),
        )
    }

    @Test
    fun `загрузка списка не трогает сбои`() {
        assertEquals(
            setOf(HistoryFailure.SAVE),
            nextFailures(setOf(HistoryFailure.SAVE), null, succeeded = true),
        )
    }

    @Test
    fun `сбой чтения добавляется и не мешает другим`() {
        assertEquals(
            setOf(HistoryFailure.READ),
            nextFailures(emptySet(), HistoryFailure.READ, succeeded = false),
        )
        assertEquals(
            setOf(HistoryFailure.READ, HistoryFailure.SAVE),
            nextFailures(setOf(HistoryFailure.SAVE), HistoryFailure.READ, succeeded = false),
        )
    }

    @Test
    fun `любой успех снимает сбой чтения`() {
        val read = setOf(HistoryFailure.READ)
        assertEquals(emptySet<HistoryFailure>(), nextFailures(read, null, succeeded = true))
        assertEquals(
            emptySet<HistoryFailure>(),
            nextFailures(read, HistoryFailure.SAVE, succeeded = true),
        )
        assertEquals(
            emptySet<HistoryFailure>(),
            nextFailures(read, HistoryFailure.DELETE, succeeded = true),
        )
        assertEquals(
            emptySet<HistoryFailure>(),
            nextFailures(read, HistoryFailure.CLEAR, succeeded = true),
        )
    }

    @Test
    fun `неудача записи не снимает сбой чтения`() {
        assertEquals(
            setOf(HistoryFailure.READ, HistoryFailure.SAVE),
            nextFailures(setOf(HistoryFailure.READ), HistoryFailure.SAVE, succeeded = false),
        )
    }
}
