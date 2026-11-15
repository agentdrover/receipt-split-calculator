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
 * Правило флага сбоя: неудача ставит свой вид, успех гасит только свой.
 * Иначе успешное удаление прятало бы предупреждение о несохранённом чеке.
 */
internal fun nextFailure(
    current: HistoryFailure?,
    kind: HistoryFailure?,
    succeeded: Boolean,
): HistoryFailure? = when {
    !succeeded -> kind ?: current
    kind != null && current == kind -> null
    else -> current
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

    /** Сбой операции истории: показываем его, а не делаем вид, что всё прошло. */
    var failure by mutableStateOf<HistoryFailure?>(null)
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
            failure = nextFailure(failure, kind, succeeded = updated != null)
            updated?.let { entries = it }
        }
    }
}
