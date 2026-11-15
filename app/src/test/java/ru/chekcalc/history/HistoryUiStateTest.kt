package ru.chekcalc.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.chekcalc.storage.HistoryEntry
import ru.chekcalc.ui.history.HistoryFailure
import ru.chekcalc.ui.history.HistorySaveState
import ru.chekcalc.ui.history.HistoryUiState
import ru.chekcalc.ui.history.SaveFailure
import ru.chekcalc.ui.history.applySaveResult
import ru.chekcalc.ui.review.DraftItem
import ru.chekcalc.ui.review.ReceiptDraft
import ru.chekcalc.ui.select.Choice
import ru.chekcalc.ui.select.SelectableItem
import ru.chekcalc.ui.select.SelectionState

class HistoryUiStateTest {

    private fun entry(id: String): HistoryEntry = HistoryEntry(
        id = id,
        createdAt = 1_700_000_000_000L,
        merchant = "Кафе",
        draft = ReceiptDraft(
            items = listOf(DraftItem(1, "Кофе", 1.0, 10_000)),
            totalKopecks = 10_000,
        ),
        selection = SelectionState(
            items = listOf(SelectableItem(1, "Кофе", 10_000, Choice.Whole)),
        ),
    )

    @Test
    fun `частичный успех публикует записанное и оставляет очередь`() {
        val before = HistoryUiState(entries = emptyList())

        val after = applySaveResult(
            before,
            HistorySaveState(
                pending = listOf(entry("b")),
                failure = SaveFailure.SAVE,
                saved = listOf(entry("a")),
            ),
        )

        assertEquals(listOf("a"), after.entries.map { it.id })
        assertEquals(listOf("b"), after.saving.pending.map { it.id })
        assertEquals(setOf(HistoryFailure.SAVE), after.failures)
    }

    @Test
    fun `полный успех очищает очередь и сбои`() {
        val before = HistoryUiState(
            entries = emptyList(),
            failures = setOf(HistoryFailure.SAVE, HistoryFailure.READ),
            saving = HistorySaveState(pending = listOf(entry("a")), failure = SaveFailure.SAVE),
        )

        val after = applySaveResult(
            before,
            HistorySaveState(pending = emptyList(), failure = null, saved = listOf(entry("a"))),
        )

        assertEquals(listOf("a"), after.entries.map { it.id })
        assertTrue(after.saving.pending.isEmpty())
        assertTrue(after.failures.isEmpty())
    }

    @Test
    fun `сбой чтения не меняет опубликованный список`() {
        val before = HistoryUiState(entries = listOf(entry("old")))

        val after = applySaveResult(
            before,
            HistorySaveState(pending = listOf(entry("a")), failure = SaveFailure.READ),
        )

        assertEquals(listOf("old"), after.entries.map { it.id })
        assertEquals(setOf(HistoryFailure.READ), after.failures)
    }

    @Test
    fun `флаг копии доходит до состояния`() {
        val after = applySaveResult(
            HistoryUiState(),
            HistorySaveState(
                pending = emptyList(),
                failure = null,
                saved = listOf(entry("a")),
                preservedCopy = true,
            ),
        )

        assertTrue(after.saving.preservedCopy)
    }

    @Test
    fun `пустой результат не стирает список`() {
        val before = HistoryUiState(entries = listOf(entry("old")))

        val after = applySaveResult(before, HistorySaveState(pending = listOf(entry("a"))))

        assertEquals(listOf("old"), after.entries.map { it.id })
    }
}
