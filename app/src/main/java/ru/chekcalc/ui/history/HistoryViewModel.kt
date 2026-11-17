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
        kind == HistoryFailure.CLEAR ->
            base - HistoryFailure.CLEAR - HistoryFailure.DELETE -
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
class HistoryViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val store = HistoryStore(File(application.filesDir, "history.json"))

    // Записи идут через очередь: неудачная не теряется, а ждёт повтора.
    private val saver = HistorySaver(write = { entry -> store.save(entry) })

    // Операции и публикация списка — под одним замком: иначе два быстрых
    // удаления могли применить устаревший снимок и вернуть удалённую запись.
    private val mutex = Mutex()

    private var state by mutableStateOf(HistoryUiState())

    val entries: List<HistoryEntry> get() = state.entries

    /** Сбои операций истории: показываем их, а не делаем вид, что всё прошло. */
    val failures: Set<HistoryFailure> get() = state.failures

    /** Очередь записей и её состояние: что ждёт, почему, и флаг копии. */
    val saving: HistorySaveState get() = state.saving

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
                is HistoryLoad.Data ->
                    state =
                        state.copy(
                            entries = result.entries,
                            failures = nextFailures(state.failures, null, succeeded = true),
                        )
                HistoryLoad.NoData ->
                    state =
                        state.copy(
                            entries = emptyList(),
                            failures = nextFailures(state.failures, null, succeeded = true),
                        )
                is HistoryLoad.ReadFailure ->
                    state =
                        state.copy(
                            failures = nextFailures(state.failures, HistoryFailure.READ, succeeded = false),
                        )
            }
        }
    }

    private suspend fun saveLocked(entry: HistoryEntry) {
        mutex.withLock {
            apply(withContext(Dispatchers.IO) { saver.submit(entry) })
        }
    }

    private suspend fun retryLocked() {
        mutex.withLock {
            apply(withContext(Dispatchers.IO) { saver.retry() })
        }
    }

    private fun apply(result: HistorySaveState) {
        state = applySaveResult(state, result)
    }

    private suspend fun deleteLocked(id: String) {
        mutex.withLock {
            val outcome = withContext(Dispatchers.IO) { runCatching { store.delete(id) } }.getOrNull()
            if (outcome == null) {
                state =
                    state.copy(
                        failures = nextFailures(state.failures, HistoryFailure.DELETE, succeeded = false),
                    )
                return@withLock
            }
            when (outcome) {
                is DeleteOutcome.Deleted -> {
                    // Удалённый чек не должен вернуться из очереди.
                    val queue = withContext(Dispatchers.IO) { saver.removed(id) }
                    var failures = nextFailures(state.failures, HistoryFailure.DELETE, succeeded = true)
                    if (queue.pending.isEmpty()) failures = failures - HistoryFailure.SAVE
                    state = state.copy(entries = outcome.entries, failures = failures, saving = queue)
                }
                is DeleteOutcome.ReadFailure ->
                    state =
                        state.copy(
                            failures = nextFailures(state.failures, HistoryFailure.READ, succeeded = false),
                        )
            }
        }
    }

    private suspend fun clearLocked() {
        mutex.withLock {
            val updated = withContext(Dispatchers.IO) { runCatching { store.clear() } }.getOrNull()
            if (updated == null) {
                state =
                    state.copy(
                        failures = nextFailures(state.failures, HistoryFailure.CLEAR, succeeded = false),
                    )
                return@withLock
            }
            val queue = withContext(Dispatchers.IO) { saver.cleared() }
            state =
                state.copy(
                    entries = updated,
                    failures = nextFailures(state.failures, HistoryFailure.CLEAR, succeeded = true),
                    saving = queue,
                )
        }
    }
}
