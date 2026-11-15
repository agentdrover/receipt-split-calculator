package ru.chekcalc.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.chekcalc.ui.review.ReceiptDraft
import ru.chekcalc.ui.select.SelectionState
import java.io.File
import java.io.IOException

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

/** Чтение истории: «нет данных» — это не ошибка и не повод что-то затирать. */
sealed interface HistoryLoad {
    data class Data(val entries: List<HistoryEntry>) : HistoryLoad
    data object NoData : HistoryLoad
    data class ReadFailure(val cause: Throwable? = null) : HistoryLoad
}

/**
 * Запись новой истории.
 *
 * [Saved.preservedCopy] означает, что прежний файл не читался, поэтому перед
 * записью его байты остались копией на устройстве.
 */
sealed interface SaveOutcome {
    data class Saved(val entries: List<HistoryEntry>, val preservedCopy: Boolean = false) : SaveOutcome
    data class ReadFailure(val cause: Throwable? = null) : SaveOutcome
}

/** Удаление: при нечитаемой истории файл не переписывается, иначе удалять нечего. */
sealed interface DeleteOutcome {
    data class Deleted(val entries: List<HistoryEntry>) : DeleteOutcome
    data class ReadFailure(val cause: Throwable? = null) : DeleteOutcome
}

/**
 * Локальная история чеков: JSON-файл в filesDir.
 *
 * Новые записи сверху; повторный расчёт того же чека с тем же выбором
 * не создаёт дубль. Лимит [MAX_ENTRIES] держит файл маленьким.
 *
 * Ошибка чтения отличается от отсутствия записей: иначе первый же save
 * уничтожил бы историю, которую ещё можно было достать из копии.
 */
class HistoryStore(
    private val file: File,
    // Копию подменяем в тестах: иначе срыв посреди копирования не воспроизвести.
    private val copyFile: (File, File) -> Unit = { source, target ->
        source.copyTo(target, overwrite = false)
    },
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Замок общий для всех экземпляров: после поворота Activity создаёт новый
    // store, а запись, начатая старым, всё ещё может выполняться.
    private val lock: Any get() = GLOBAL_LOCK

    fun load(): HistoryLoad = synchronized(lock) { read() }

    fun save(entry: HistoryEntry): SaveOutcome = synchronized(lock) {
        when (val current = read()) {
            is HistoryLoad.ReadFailure -> saveAfterReadFailure(entry, current)
            is HistoryLoad.Data -> writeEntry(entry, current.entries, preservedCopy = false)
            HistoryLoad.NoData -> writeEntry(entry, emptyList(), preservedCopy = false)
        }
    }

    fun delete(id: String): DeleteOutcome = synchronized(lock) {
        when (val current = read()) {
            is HistoryLoad.ReadFailure -> DeleteOutcome.ReadFailure(current.cause)
            HistoryLoad.NoData -> DeleteOutcome.Deleted(emptyList())
            is HistoryLoad.Data -> {
                val updated = current.entries.filterNot { it.id == id }
                write(updated)
                DeleteOutcome.Deleted(updated)
            }
        }
    }

    /** Очистка — явное удаление: файл стирается без чтения и без копии. */
    fun clear(): List<HistoryEntry> = synchronized(lock) {
        write(emptyList())
        emptyList()
    }

    private fun writeEntry(
        entry: HistoryEntry,
        current: List<HistoryEntry>,
        preservedCopy: Boolean,
    ): SaveOutcome {
        // Идентификатор выдаётся на одну сессию расчёта: повторное нажатие
        // «Дальше» по тому же чеку не создаёт дубль, а новый чек — создаёт.
        if (current.firstOrNull()?.id == entry.id) {
            return SaveOutcome.Saved(current, preservedCopy)
        }
        val updated = (listOf(entry) + current).take(MAX_ENTRIES)
        write(updated)
        return SaveOutcome.Saved(updated, preservedCopy)
    }

    /**
     * Сохранение поверх нечитаемого файла: сначала копия, потом запись.
     *
     * Если копию сохранить нельзя, файл не трогаем: отказ лучше, чем потеря
     * единственной копии прежней истории.
     */
    private fun saveAfterReadFailure(
        entry: HistoryEntry,
        failure: HistoryLoad.ReadFailure,
    ): SaveOutcome {
        val copy = preserveCopy() ?: return SaveOutcome.ReadFailure(failure.cause)
        return try {
            val updated = listOf(entry).take(MAX_ENTRIES)
            write(updated)
            SaveOutcome.Saved(updated, preservedCopy = true)
        } catch (error: IOException) {
            // Запись не удалась: слот копии не тратим, исходник цел, повтор сработает.
            copy.delete()
            throw error
        }
    }

    /**
     * Копия нечитаемого файла. Слоты нумеруются, существующие не
     * перезаписываются: вторая копия не должна затирать первую, а срыв
     * посреди копирования не оставляет недописанный файл.
     */
    private fun preserveCopy(): File? {
        val target = copyTarget() ?: return null
        return try {
            copyFile(file, target)
            target
        } catch (error: IOException) {
            target.delete()
            null
        }
    }

    /** Первый свободный слот копии; когда заняты все, копию не делаем. */
    private fun copyTarget(): File? {
        val parent = file.absoluteFile.parentFile ?: return null
        for (index in 1..MAX_COPIES) {
            val name = if (index == 1) {
                "${file.name}$CORRUPT_SUFFIX"
            } else {
                "${file.name}$CORRUPT_SUFFIX.$index"
            }
            val candidate = File(parent, name)
            if (!candidate.exists()) return candidate
        }
        return null
    }

    private fun read(): HistoryLoad {
        if (!file.exists()) return HistoryLoad.NoData
        return try {
            val text = file.readText()
            if (text.isBlank()) {
                HistoryLoad.NoData
            } else {
                HistoryLoad.Data(json.decodeFromString(ListSerializer, text))
            }
        } catch (error: Exception) {
            HistoryLoad.ReadFailure(error)
        }
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
            throw IOException("не удалось заменить файл истории")
        }
    }

    companion object {
        const val MAX_ENTRIES = 50

        /** Сколько копий нечитаемой истории держим одновременно. */
        const val MAX_COPIES = 5

        private const val CORRUPT_SUFFIX = ".corrupt"

        private val GLOBAL_LOCK = Any()

        private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(HistoryEntry.serializer())
    }
}
