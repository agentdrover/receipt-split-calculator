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
import ru.chekcalc.storage.SaveOutcome
import java.io.File

/** Какая операция истории не удалась: от этого зависит текст для человека. */
enum class HistoryFailure { READ, SAVE, DELETE, CLEAR }

/**
 * Правило сбоев: каждый вид живёт сам по себе. Неудача добавляет свой вид,
 * успех снимает только свой; успешная очистка снимает и ошибку удаления —
 * удалять больше нечего.
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
        kind == HistoryFailure.CLEAR -> base - HistoryFailure.CLEAR - HistoryFailure.DELETE
        else -> base - kind
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

    // Операции и публикация списка — под одним замком: иначе два быстрых
    // удаления могли применить устаревший снимок и вернуть удалённую запись.
    private val mutex = Mutex()

    var entries by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set

    /** Сбои операций истории: показываем их, а не делаем вид, что всё прошло. */
    var failures by mutableStateOf<Set<HistoryFailure>>(emptySet())
        private set

    /** Прежняя история не читалась, но её байты сохранены копией на устройстве. */
    var copyNotice by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { loadLocked() }
    }

    fun save(entry: HistoryEntry) {
        viewModelScope.launch { saveLocked(entry) }
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
            // Исключение записи — отдельный сбой: файл читается, но записать не вышло.
            val outcome = withContext(Dispatchers.IO) { runCatching { store.save(entry) } }.getOrNull()
            if (outcome == null) {
                failures = nextFailures(failures, HistoryFailure.SAVE, succeeded = false)
                return@withLock
            }
            when (outcome) {
                is SaveOutcome.Saved -> {
                    entries = outcome.entries
                    failures = nextFailures(failures, HistoryFailure.SAVE, succeeded = true)
                    if (outcome.preservedCopy) copyNotice = true
                }
                is SaveOutcome.ReadFailure ->
                    failures = nextFailures(failures, HistoryFailure.READ, succeeded = false)
            }
        }
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
                copyNotice = false
            }
        }
    }
}
