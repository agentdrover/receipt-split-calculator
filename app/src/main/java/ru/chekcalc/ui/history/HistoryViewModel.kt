package ru.chekcalc.ui.history

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.chekcalc.storage.DeleteOutcome
import ru.chekcalc.storage.HistoryEntry
import ru.chekcalc.storage.HistoryLoad
import ru.chekcalc.storage.HistoryStore
import java.io.File

/** Какая операция истории не удалась: от этого зависит текст для человека. */
enum class HistoryFailure { READ, SAVE, DELETE, CLEAR }

/**
 * Правило сбоев: каждый вид живёт сам по себе. Неудача добавляет свой вид,
 * успех снимает только свой; успешная очистка снимает и ошибку удаления —
 * удалять больше нечего — и сбой сохранения: очередь снята.
 *
 * READ снимается любой успешной операцией: она доказывает, что текущий файл
 * читается, даже если читали его ради записи.
 */
internal fun nextFailures(
    current: Set<HistoryFailure>,
    kind: HistoryFailure?,
    succeeded: Boolean,
): Set<HistoryFailure> {
    val base = if (succeeded) current - HistoryFailure.READ else current
    return when {
        !succeeded -> if (kind != null) base + kind else base
        kind == null -> base
        kind == HistoryFailure.CLEAR -> base - HistoryFailure.CLEAR - HistoryFailure.DELETE -
            HistoryFailure.SAVE
        else -> base - kind
    }
}

/**
 * Сбои после попытки записи очереди.
 *
 * Успешно записанная запись доказывает, что файл читается: READ снимается,
 * даже если следующая запись в очереди упала.
 */
internal fun nextFailuresAfterSave(
    current: Set<HistoryFailure>,
    failure: SaveFailure?,
    anySaved: Boolean,
): Set<HistoryFailure> {
    val base = if (anySaved) current - HistoryFailure.READ else current
    return when (failure) {
        null -> base - HistoryFailure.SAVE
        SaveFailure.READ -> (base - HistoryFailure.SAVE) + HistoryFailure.READ
        SaveFailure.SAVE -> base + HistoryFailure.SAVE
    }
}

/**
 * История чеков, которая переживает поворот экрана.
 *
 * Хранилище и запись живут в ViewModel: область rememberCoroutineScope
 * отменяется вместе с Activity, и сохранение могло не начаться вовсе.
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val store = HistoryStore(File(application.filesDir, "history.json"))

    // Записи идут через очередь: неудачная не теряется, а ждёт повтора.
    private val saver = HistorySaver(write = { entry -> store.save(entry) })

    // Операции и публикация списка — под одним замком: иначе два быстрых
    // удаления могли применить устаревший снимок и вернуть удалённую запись.
    private val mutex = Mutex()

    var entries by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set

    /** Сбои операций истории: показываем их, а не делаем вид, что всё прошло. */
    var failures by mutableStateOf<Set<HistoryFailure>>(emptySet())
        private set

    /** Очередь записей и её состояние: что ждёт, почему, и флаг копии. */
    var saving by mutableStateOf(HistorySaveState())
        private set

    init {
        viewModelScope.launch { loadLocked() }
    }

    fun save(entry: HistoryEntry) {
        viewModelScope.launch { saveLocked(entry) }
    }

    /** Повтор всей очереди: кнопка «Сохранить ещё раз» на главном экране. */
    fun retrySaves() {
        viewModelScope.launch { retryLocked() }
    }

    fun delete(id: String) {
        viewModelScope.launch { deleteLocked(id) }
    }

    fun clear() {
        viewModelScope.launch { clearLocked() }
    }

    private suspend fun loadLocked() {
        mutex.withLock {
            when (val result = withContext(Dispatchers.IO) { store.load() }) {
                is HistoryLoad.Data -> {
                    entries = result.entries
                    failures = nextFailures(failures, null, succeeded = true)
                }
                HistoryLoad.NoData -> {
                    entries = emptyList()
                    failures = nextFailures(failures, null, succeeded = true)
                }
                is HistoryLoad.ReadFailure ->
                    failures = nextFailures(failures, HistoryFailure.READ, succeeded = false)
            }
        }
    }

    private suspend fun saveLocked(entry: HistoryEntry) {
        mutex.withLock {
            publish(withContext(Dispatchers.IO) { saver.submit(entry) })
        }
    }

    private suspend fun retryLocked() {
        mutex.withLock {
            publish(withContext(Dispatchers.IO) { saver.retry() })
        }
    }

    private fun publish(result: HistorySaveState) {
        saving = result
        result.saved?.let { entries = it }
        failures = nextFailuresAfterSave(
            current = failures,
            failure = result.failure,
            anySaved = result.saved != null,
        )
    }

    private suspend fun deleteLocked(id: String) {
        mutex.withLock {
            val outcome = withContext(Dispatchers.IO) { runCatching { store.delete(id) } }.getOrNull()
            if (outcome == null) {
                failures = nextFailures(failures, HistoryFailure.DELETE, succeeded = false)
                return@withLock
            }
            when (outcome) {
                is DeleteOutcome.Deleted -> {
                    entries = outcome.entries
                    failures = nextFailures(failures, HistoryFailure.DELETE, succeeded = true)
                    // Удалённый чек не должен вернуться из очереди.
                    val queue = withContext(Dispatchers.IO) { saver.removed(id) }
                    saving = queue
                    if (queue.pending.isEmpty()) failures = failures - HistoryFailure.SAVE
                }
                is DeleteOutcome.ReadFailure ->
                    failures = nextFailures(failures, HistoryFailure.READ, succeeded = false)
            }
        }
    }

    private suspend fun clearLocked() {
        mutex.withLock {
            val updated = withContext(Dispatchers.IO) { runCatching { store.clear() } }.getOrNull()
            failures = nextFailures(failures, HistoryFailure.CLEAR, succeeded = updated != null)
            if (updated != null) {
                entries = updated
                saving = withContext(Dispatchers.IO) { saver.cleared() }
            }
        }
    }
}
