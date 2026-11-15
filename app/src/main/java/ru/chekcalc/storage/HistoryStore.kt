package ru.chekcalc.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.chekcalc.ui.review.ReceiptDraft
import ru.chekcalc.ui.select.SelectionState
import java.io.File

/**
 * Запись истории: чек, выбор долей и итог.
 *
 * Фото не хранится — только данные расчёта (спека, D7/NFR-3). Формы
 * ReceiptDraft и SelectionState уже сериализуемые, поэтому история — это
 * просто JSON-файл на устройстве.
 */
@Serializable
data class HistoryEntry(
    val id: String,
    val createdAt: Long,
    val merchant: String? = null,
    val draft: ReceiptDraft,
    val selection: SelectionState,
) {
    val totalKopecks: Long get() = draft.totalKopecks

    val myShareKopecks: Long get() = selection.totalKopecks

    val itemsCount: Int get() = draft.items.size
}

/**
 * Локальная история чеков: JSON-файл в filesDir.
 *
 * Новые записи сверху; повторный расчёт того же чека с тем же выбором
 * не создаёт дубль. Лимит [MAX_ENTRIES] держит файл маленьким.
 */
class HistoryStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Замок общий для всех экземпляров: после поворота Activity создаёт новый
    // store, а запись, начатая старым, всё ещё может выполняться.
    private val lock: Any get() = GLOBAL_LOCK

    fun list(): List<HistoryEntry> = synchronized(lock) {
        runCatching { read() }.getOrDefault(emptyList())
    }

    fun save(entry: HistoryEntry): List<HistoryEntry> = synchronized(lock) {
        val current = runCatching { read() }.getOrDefault(emptyList())
        // Идентификатор выдаётся на одну сессию расчёта: повторное нажатие
        // «Дальше» по тому же чеку не создаёт дубль, а новый чек — создаёт.
        if (current.firstOrNull()?.id == entry.id) {
            return@synchronized current
        }
        val updated = (listOf(entry) + current).take(MAX_ENTRIES)
        write(updated)
        updated
    }

    fun delete(id: String): List<HistoryEntry> = synchronized(lock) {
        val updated = runCatching { read() }.getOrDefault(emptyList()).filterNot { it.id == id }
        write(updated)
        updated
    }

    fun clear(): List<HistoryEntry> = synchronized(lock) {
        write(emptyList())
        emptyList()
    }

    private fun read(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        return json.decodeFromString(ListSerializer, text)
    }

    private fun write(entries: List<HistoryEntry>) {
        val target = file.absoluteFile
        val parent = target.parentFile
        parent?.mkdirs()
        val text = json.encodeToString(ListSerializer, entries)
        // Пишем во временный файл и переименовываем: обрыв процесса посреди
        // записи не должен обнулить уже сохранённую историю.
        val temp = File(parent, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            // Прямая перезапись обнулила бы историю при обрыве: лучше
            // сообщить об ошибке, сохранив прежний файл.
            temp.delete()
            throw java.io.IOException("не удалось заменить файл истории")
        }
    }

    companion object {
        const val MAX_ENTRIES = 50

        private val GLOBAL_LOCK = Any()

        private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(HistoryEntry.serializer())
    }
}
