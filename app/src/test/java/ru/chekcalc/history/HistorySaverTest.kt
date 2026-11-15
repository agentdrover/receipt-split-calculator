package ru.chekcalc.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.chekcalc.storage.HistoryEntry
import ru.chekcalc.storage.SaveOutcome
import ru.chekcalc.ui.history.HistorySaver
import ru.chekcalc.ui.history.SaveFailure
import ru.chekcalc.ui.review.DraftItem
import ru.chekcalc.ui.review.ReceiptDraft
import ru.chekcalc.ui.select.Choice
import ru.chekcalc.ui.select.SelectableItem
import ru.chekcalc.ui.select.SelectionState
import java.io.IOException

class HistorySaverTest {

    private fun entry(id: String, name: String = "Кофе"): HistoryEntry = HistoryEntry(
        id = id,
        createdAt = 1_700_000_000_000L,
        merchant = "Кафе",
        draft = ReceiptDraft(
            items = listOf(DraftItem(1, name, 1.0, 10_000)),
            totalKopecks = 10_000,
        ),
        selection = SelectionState(
            items = listOf(SelectableItem(1, name, 10_000, Choice.Whole)),
        ),
    )

    /** Хранилище в памяти: новые записи сверху, как у настоящего HistoryStore. */
    private class MemoryStore {
        val written = mutableListOf<HistoryEntry>()
        val attempts = mutableListOf<String>()
        var failure: Failure? = null
        var preservedCopy = false
        var failAfter: Int? = null

        enum class Failure { READ, WRITE }

        fun write(entry: HistoryEntry): SaveOutcome {
            attempts += entry.id
            if (failAfter != null && written.size >= failAfter!!) throw IOException("нет места")
            when (failure) {
                Failure.READ -> return SaveOutcome.ReadFailure()
                Failure.WRITE -> throw IOException("нет места")
                null -> Unit
            }
            written.add(0, entry)
            return SaveOutcome.Saved(written.toList(), preservedCopy)
        }
    }

    @Test
    fun `сбой записи оставляет ожидающую запись`() {
        val store = MemoryStore().apply { failure = MemoryStore.Failure.WRITE }

        val state = HistorySaver(store::write).submit(entry("a"))

        assertEquals(listOf("a"), state.pending.map { it.id })
        assertEquals(SaveFailure.SAVE, state.failure)
        assertEquals(null, state.saved)
    }

    @Test
    fun `повтор после сбоя сохраняет чек и очищает ожидание`() {
        val store = MemoryStore().apply { failure = MemoryStore.Failure.WRITE }
        val saver = HistorySaver(store::write)
        saver.submit(entry("a"))
        store.failure = null

        val state = saver.retry()

        assertTrue(state.pending.isEmpty())
        assertEquals(null, state.failure)
        assertEquals(listOf("a"), store.written.map { it.id })
        assertEquals(listOf("a"), state.saved?.map { it.id })
    }

    @Test
    fun `повторный сбой не теряет запись`() {
        val store = MemoryStore().apply { failure = MemoryStore.Failure.WRITE }
        val saver = HistorySaver(store::write)
        saver.submit(entry("a"))

        val state = saver.retry()

        assertEquals(listOf("a"), state.pending.map { it.id })
        assertEquals(SaveFailure.SAVE, state.failure)
    }

    @Test
    fun `успешная запись не оставляет ожидания`() {
        val store = MemoryStore()

        val state = HistorySaver(store::write).submit(entry("a"))

        assertTrue(state.pending.isEmpty())
        assertEquals(null, state.failure)
        assertEquals(listOf("a"), state.saved?.map { it.id })
    }

    @Test
    fun `повтор сохраняет всю очередь`() {
        val store = MemoryStore().apply { failure = MemoryStore.Failure.WRITE }
        val saver = HistorySaver(store::write)
        saver.submit(entry("a"))
        saver.submit(entry("b"))
        store.failure = null

        val state = saver.retry()

        assertTrue(state.pending.isEmpty())
        assertEquals(listOf("b", "a"), store.written.map { it.id })
        assertEquals(listOf("b", "a"), state.saved?.map { it.id })
    }

    @Test
    fun `частичный успех публикует последний записанный список`() {
        val store = MemoryStore().apply { failure = MemoryStore.Failure.WRITE }
        val saver = HistorySaver(store::write)
        saver.submit(entry("a"))
        saver.submit(entry("b"))
        store.failure = null
        store.failAfter = 1

        val state = saver.retry()

        assertEquals(listOf("b"), state.pending.map { it.id })
        assertEquals(listOf("a"), state.saved?.map { it.id })
        assertEquals(SaveFailure.SAVE, state.failure)
    }

    @Test
    fun `повторный submit того же id заменяет запись`() {
        val store = MemoryStore().apply { failure = MemoryStore.Failure.WRITE }
        val saver = HistorySaver(store::write)
        saver.submit(entry("a", name = "Старое имя"))

        val state = saver.submit(entry("a", name = "Новое имя"))

        assertEquals(1, state.pending.size)
        assertEquals("Новое имя", state.pending.single().draft.items.single().name)
    }

    @Test
    fun `новый чек не обгоняет ожидающий`() {
        val store = MemoryStore().apply { failure = MemoryStore.Failure.WRITE }
        val saver = HistorySaver(store::write)
        saver.submit(entry("a"))
        store.failure = null

        val state = saver.submit(entry("b"))

        // Ожидающая A пишется раньше B, поэтому в истории B выше A.
        assertEquals(listOf("a", "b"), store.attempts.takeLast(2))
        assertEquals(listOf("b", "a"), state.saved?.map { it.id })
        assertTrue(state.pending.isEmpty())
    }

    @Test
    fun `флаг копии не теряется следующими записями`() {
        val store = MemoryStore().apply { failure = MemoryStore.Failure.READ }
        val saver = HistorySaver(store::write)
        saver.submit(entry("a"))
        store.failure = null
        store.preservedCopy = true
        saver.retry()
        store.preservedCopy = false

        val state = saver.submit(entry("b"))

        assertTrue(state.preservedCopy)
    }

    @Test
    fun `причина отказа различается`() {
        val readStore = MemoryStore().apply { failure = MemoryStore.Failure.READ }
        val readState = HistorySaver(readStore::write).submit(entry("a"))

        assertEquals(SaveFailure.READ, readState.failure)
        assertEquals(listOf("a"), readState.pending.map { it.id })

        val writeStore = MemoryStore().apply { failure = MemoryStore.Failure.WRITE }
        val writeState = HistorySaver(writeStore::write).submit(entry("a"))

        assertEquals(SaveFailure.SAVE, writeState.failure)
        assertEquals(listOf("a"), writeState.pending.map { it.id })
    }

    @Test
    fun `удаление снимает запись из очереди`() {
        val store = MemoryStore().apply { failure = MemoryStore.Failure.WRITE }
        val saver = HistorySaver(store::write)
        saver.submit(entry("a"))
        saver.submit(entry("b"))

        val remaining = saver.removed("a")

        assertEquals(listOf("b"), remaining.pending.map { it.id })
        assertEquals(SaveFailure.SAVE, remaining.failure)

        val empty = saver.removed("b")

        assertTrue(empty.pending.isEmpty())
        assertEquals(null, empty.failure)
    }

    @Test
    fun `очистка снимает очередь и флаг копии`() {
        val store = MemoryStore().apply { failure = MemoryStore.Failure.WRITE }
        val saver = HistorySaver(store::write, initialPreservedCopy = true)
        saver.submit(entry("a"))

        val state = saver.cleared()

        assertTrue(state.pending.isEmpty())
        assertEquals(null, state.failure)
        assertEquals(false, state.preservedCopy)
        assertEquals(emptyList<HistoryEntry>(), state.saved)
    }

    @Test
    fun `повтор пустой очереди ничего не пишет`() {
        val store = MemoryStore()

        val state = HistorySaver(store::write).retry()

        assertTrue(state.pending.isEmpty())
        assertTrue(store.attempts.isEmpty())
    }
}
