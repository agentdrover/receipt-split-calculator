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
import ru.chekcalc.storage.HistoryEntry
import ru.chekcalc.storage.HistoryStore
import java.io.File

/** Какая операция истории не удалась: от этого зависит текст для человека. */
enum class HistoryFailure { SAVE, DELETE, CLEAR }

/**
 * Правило сбоев: каждый вид живёт сам по себе. Неудача добавляет свой вид,
 * успех снимает только свой; успешная очистка снимает и ошибку удаления —
 * удалять больше нечего.
 */
internal fun nextFailures(
    current: Set<HistoryFailure>,
    kind: HistoryFailure?,
    succeeded: Boolean,
): Set<HistoryFailure> = when {
    !succeeded -> if (kind != null) current + kind else current
    kind == null -> current
    kind == HistoryFailure.CLEAR -> current - HistoryFailure.CLEAR - HistoryFailure.DELETE
    else -> current - kind
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

    init {
        viewModelScope.launch { perform(null) { store.list() } }
    }

    fun save(entry: HistoryEntry) {
        viewModelScope.launch { perform(HistoryFailure.SAVE) { store.save(entry) } }
    }

    fun delete(id: String) {
        viewModelScope.launch { perform(HistoryFailure.DELETE) { store.delete(id) } }
    }

    fun clear() {
        viewModelScope.launch { perform(HistoryFailure.CLEAR) { store.clear() } }
    }

    private suspend fun perform(kind: HistoryFailure?, operation: () -> List<HistoryEntry>) {
        mutex.withLock {
            val updated = withContext(Dispatchers.IO) {
                runCatching(operation).getOrNull()
            }
            failures = nextFailures(failures, kind, succeeded = updated != null)
            updated?.let { entries = it }
        }
    }
}
