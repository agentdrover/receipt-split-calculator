package ru.chekcalc.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.chekcalc.ui.review.DraftItem
import ru.chekcalc.ui.review.ReceiptDraft
import ru.chekcalc.ui.select.Choice
import ru.chekcalc.ui.select.SelectableItem
import ru.chekcalc.ui.select.SelectionState
import java.nio.file.Files
import java.util.UUID

class HistoryStoreTest {

    private fun file() = Files.createTempDirectory("history-test").toFile().resolve("history.json")

    private fun entry(
        id: String = UUID.randomUUID().toString(),
        createdAt: Long = 1_700_000_000_000L,
        name: String = "Кофе",
        merchant: String = "Кафе",
    ): HistoryEntry = HistoryEntry(
        id = id,
        createdAt = createdAt,
        merchant = merchant,
        draft = ReceiptDraft(
            items = listOf(DraftItem(1, name, 1.0, 10_000)),
            totalKopecks = 10_000,
        ),
        selection = SelectionState(
            items = listOf(SelectableItem(1, name, 10_000, Choice.Whole)),
        ),
    )

    @Test
    fun `сохранённый чек читается и переживает перезапуск`() {
        val file = file()
        val store = HistoryStore(file)
        store.save(entry(id = "first"))

        val reopened = HistoryStore(file).list()

        assertEquals(1, reopened.size)
        assertEquals("first", reopened.single().id)
        assertEquals("Кафе", reopened.single().merchant)
        assertEquals(10_000, reopened.single().myShareKopecks)
    }

    @Test
    fun `новые записи сверху`() {
        val store = HistoryStore(file())
        store.save(entry(id = "old", createdAt = 1))
        store.save(entry(id = "new", createdAt = 2, name = "Чай"))

        assertEquals(listOf("new", "old"), store.list().map { it.id })
    }

    @Test
    fun `удаление не восстанавливает запись`() {
        val file = file()
        val store = HistoryStore(file)
        store.save(entry(id = "a"))
        store.save(entry(id = "b", name = "Чай"))

        store.delete("a")

        assertEquals(listOf("b"), store.list().map { it.id })
        assertEquals(listOf("b"), HistoryStore(file).list().map { it.id })
    }

    @Test
    fun `удалить всё очищает историю`() {
        val store = HistoryStore(file())
        store.save(entry(id = "a"))
        store.save(entry(id = "b", name = "Чай"))

        store.clear()

        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `лимит записей держится`() {
        val store = HistoryStore(file())
        repeat(HistoryStore.MAX_ENTRIES + 5) { index ->
            store.save(entry(id = "id-$index", createdAt = index.toLong(), name = "Позиция $index"))
        }

        assertEquals(HistoryStore.MAX_ENTRIES, store.list().size)
        assertEquals("id-${HistoryStore.MAX_ENTRIES + 4}", store.list().first().id)
    }

    @Test
    fun `повторное сохранение той же сессии не создаёт дубль`() {
        val store = HistoryStore(file())
        store.save(entry(id = "a"))
        val updated = store.save(entry(id = "a"))

        assertEquals(1, updated.size)
        assertEquals("a", updated.single().id)
    }

    @Test
    fun `одинаковые чеки разных сессий сохраняются оба`() {
        val store = HistoryStore(file())
        store.save(entry(id = "a"))

        val updated = store.save(entry(id = "b"))

        assertEquals(2, updated.size)
    }


    @Test
    fun `битый файл не роняет историю`() {
        val file = file()
        file.parentFile?.mkdirs()
        file.writeText("{ это не json")

        val store = HistoryStore(file)

        assertTrue(store.list().isEmpty())
        assertEquals(1, store.save(entry(id = "a")).size)
    }
}
