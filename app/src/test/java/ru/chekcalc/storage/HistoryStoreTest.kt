package ru.chekcalc.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.chekcalc.ui.review.DraftItem
import ru.chekcalc.ui.review.ReceiptDraft
import ru.chekcalc.ui.select.Choice
import ru.chekcalc.ui.select.SelectableItem
import ru.chekcalc.ui.select.SelectionState
import java.io.File
import java.io.IOException
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

    private fun corrupt(target: File, text: String = "{ это не json") {
        target.parentFile?.mkdirs()
        target.writeText(text)
    }

    private fun HistoryStore.entries(): List<HistoryEntry> =
        (load() as HistoryLoad.Data).entries

    private fun copiesOf(target: File): List<File> =
        target.parentFile!!.listFiles().orEmpty()
            .filter { it.name.startsWith("${target.name}.corrupt") }
            .sortedBy { it.name }

    @Test
    fun `сохранённый чек читается и переживает перезапуск`() {
        val file = file()
        val saved = HistoryStore(file).save(entry(id = "first"))
        assertTrue(saved is SaveOutcome.Saved)

        val reopened = HistoryStore(file)
        assertEquals(1, reopened.entries().size)
        assertEquals("first", reopened.entries().single().id)
        assertEquals("Кафе", reopened.entries().single().merchant)
        assertEquals(10_000, reopened.entries().single().myShareKopecks)
    }

    @Test
    fun `новые записи сверху`() {
        val store = HistoryStore(file())
        store.save(entry(id = "old", createdAt = 1))
        store.save(entry(id = "new", createdAt = 2, name = "Чай"))

        assertEquals(listOf("new", "old"), store.entries().map { it.id })
    }

    @Test
    fun `удаление не восстанавливает запись`() {
        val file = file()
        val store = HistoryStore(file)
        store.save(entry(id = "a"))
        store.save(entry(id = "b", name = "Чай"))

        store.delete("a")

        assertEquals(listOf("b"), store.entries().map { it.id })
        assertEquals(listOf("b"), HistoryStore(file).entries().map { it.id })
    }

    @Test
    fun `удалить всё очищает историю`() {
        val store = HistoryStore(file())
        store.save(entry(id = "a"))
        store.save(entry(id = "b", name = "Чай"))

        store.clear()

        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `лимит записей держится`() {
        val store = HistoryStore(file())
        repeat(HistoryStore.MAX_ENTRIES + 5) { index ->
            store.save(entry(id = "id-$index", createdAt = index.toLong(), name = "Позиция $index"))
        }

        assertEquals(HistoryStore.MAX_ENTRIES, store.entries().size)
        assertEquals("id-${HistoryStore.MAX_ENTRIES + 4}", store.entries().first().id)
    }

    @Test
    fun `повторное сохранение той же сессии не создаёт дубль`() {
        val store = HistoryStore(file())
        store.save(entry(id = "a"))
        val updated = store.save(entry(id = "a")) as SaveOutcome.Saved

        assertEquals(1, updated.entries.size)
        assertEquals("a", updated.entries.single().id)
    }

    @Test
    fun `одинаковые чеки разных сессий сохраняются оба`() {
        val store = HistoryStore(file())
        store.save(entry(id = "a"))

        val updated = store.save(entry(id = "b")) as SaveOutcome.Saved

        assertEquals(2, updated.entries.size)
    }

    @Test
    fun `битый файл даёт ошибку чтения и не меняет его на диске`() {
        val file = file()
        corrupt(file)

        val store = HistoryStore(file)

        assertTrue(store.load() is HistoryLoad.ReadFailure)
        assertTrue(store.load() is HistoryLoad.ReadFailure)
        assertEquals("{ это не json", file.readText())
        assertTrue(copiesOf(file).isEmpty())
    }

    @Test
    fun `сохранение после сбоя чтения оставляет копию прежних данных`() {
        val file = file()
        corrupt(file)

        val outcome = HistoryStore(file).save(entry(id = "a"))

        assertTrue(outcome is SaveOutcome.Saved)
        assertTrue((outcome as SaveOutcome.Saved).preservedCopy)
        val copy = File(file.parentFile, "history.json.corrupt")
        assertTrue("копия прежней истории должна существовать", copy.exists())
        assertEquals("{ это не json", copy.readText())
        // Новая запись записана поверх: история снова читается.
        assertEquals(listOf("a"), HistoryStore(file).entries().map { it.id })
    }

    @Test
    fun `вторая копия не затирает первую`() {
        val file = file()
        corrupt(file, "первое повреждение")
        HistoryStore(file).save(entry(id = "first"))

        corrupt(file, "второе повреждение")
        val outcome = HistoryStore(file).save(entry(id = "second")) as SaveOutcome.Saved

        assertTrue(outcome.preservedCopy)
        val copies = copiesOf(file)
        assertEquals(listOf("history.json.corrupt", "history.json.corrupt.2"), copies.map { it.name })
        assertEquals("первое повреждение", copies[0].readText())
        assertEquals("второе повреждение", copies[1].readText())
    }

    @Test
    fun `отказ копирования не перезаписывает историю`() {
        val file = file()
        corrupt(file)
        val store = HistoryStore(file, copyFile = { _, _ -> throw IOException("нет места") })

        assertTrue(store.save(entry(id = "a")) is SaveOutcome.ReadFailure)
        assertEquals("{ это не json", file.readText())
        assertTrue(copiesOf(file).isEmpty())
    }

    @Test
    fun `отсутствующий и пустой файл это не ошибка`() {
        val missing = file()
        assertEquals(HistoryLoad.NoData, HistoryStore(missing).load())

        val empty = file()
        empty.parentFile?.mkdirs()
        empty.writeText("")
        assertEquals(HistoryLoad.NoData, HistoryStore(empty).load())
    }

    @Test
    fun `удаление при нечитаемой истории не пишет файл`() {
        val file = file()
        corrupt(file)

        val outcome = HistoryStore(file).delete("a")

        assertTrue(outcome is DeleteOutcome.ReadFailure)
        assertEquals("{ это не json", file.readText())
        assertTrue(copiesOf(file).isEmpty())
    }

    @Test
    fun `очистка при нечитаемой истории не создаёт копию`() {
        val file = file()
        corrupt(file)

        val updated = HistoryStore(file).clear()

        assertTrue(updated.isEmpty())
        assertTrue(HistoryStore(file).load() is HistoryLoad.Data)
        assertTrue(HistoryStore(file).entries().isEmpty())
        assertTrue(copiesOf(file).isEmpty())
    }

    @Test
    fun `неудачная запись после копирования убирает копию и повтор срабатывает`() {
        val file = file()
        corrupt(file)
        // Каталог на месте временного файла ломает запись уже после копирования.
        val blocker = File(file.parentFile, "history.json.tmp")
        blocker.mkdirs()

        try {
            assertThrows(IOException::class.java) { HistoryStore(file).save(entry(id = "a")) }
            assertEquals("{ это не json", file.readText())
            assertTrue("слот копии не должен тратиться", copiesOf(file).isEmpty())
        } finally {
            blocker.delete()
        }

        val retry = HistoryStore(file).save(entry(id = "a")) as SaveOutcome.Saved

        assertTrue(retry.preservedCopy)
        assertEquals("{ это не json", copiesOf(file).single().readText())
    }

    @Test
    fun `пять копий блокируют сохранение без записи`() {
        val file = file()
        corrupt(file)
        val parent = file.parentFile!!
        for (index in 1..HistoryStore.MAX_COPIES) {
            val name = if (index == 1) "history.json.corrupt" else "history.json.corrupt.$index"
            File(parent, name).writeText("копия $index")
        }

        assertTrue(HistoryStore(file).save(entry(id = "a")) is SaveOutcome.ReadFailure)
        assertEquals("{ это не json", file.readText())
        assertEquals(HistoryStore.MAX_COPIES, copiesOf(file).size)
        assertFalse(File(parent, "history.json.corrupt.6").exists())
    }

    @Test
    fun `срыв копирования не оставляет частичный файл`() {
        val file = file()
        corrupt(file)
        val broken = HistoryStore(file, copyFile = { _, target ->
            target.writeText("половина")
            throw IOException("сбой посреди копирования")
        })

        assertTrue(broken.save(entry(id = "a")) is SaveOutcome.ReadFailure)
        assertEquals("{ это не json", file.readText())
        assertTrue("частичная копия должна быть удалена", copiesOf(file).isEmpty())

        val retry = HistoryStore(file).save(entry(id = "a")) as SaveOutcome.Saved

        assertTrue(retry.preservedCopy)
        assertEquals("{ это не json", copiesOf(file).single().readText())
    }
}
