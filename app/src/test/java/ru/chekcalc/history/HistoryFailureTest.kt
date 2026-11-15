package ru.chekcalc.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.chekcalc.ui.history.HistoryFailure
import ru.chekcalc.ui.history.nextFailure

class HistoryFailureTest {

    @Test
    fun `неудача ставит свой вид`() {
        assertEquals(HistoryFailure.SAVE, nextFailure(null, HistoryFailure.SAVE, succeeded = false))
        assertEquals(HistoryFailure.DELETE, nextFailure(null, HistoryFailure.DELETE, succeeded = false))
        assertEquals(HistoryFailure.CLEAR, nextFailure(null, HistoryFailure.CLEAR, succeeded = false))
    }

    @Test
    fun `успех другой операции не гасит сбой сохранения`() {
        assertEquals(
            HistoryFailure.SAVE,
            nextFailure(HistoryFailure.SAVE, HistoryFailure.DELETE, succeeded = true),
        )
        assertEquals(
            HistoryFailure.SAVE,
            nextFailure(HistoryFailure.SAVE, null, succeeded = true),
        )
    }

    @Test
    fun `успех той же операции гасит сбой`() {
        assertNull(nextFailure(HistoryFailure.SAVE, HistoryFailure.SAVE, succeeded = true))
        assertNull(nextFailure(HistoryFailure.DELETE, HistoryFailure.DELETE, succeeded = true))
    }
}
