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

    /** Сбой записи: показываем его, а не делаем вид, что чек сохранён. */
    var failure by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { perform { store.list() } }
    }

    fun save(entry: HistoryEntry) {
        viewModelScope.launch { perform { store.save(entry) } }
    }

    fun delete(id: String) {
        viewModelScope.launch { perform { store.delete(id) } }
    }

    fun clear() {
        viewModelScope.launch { perform { store.clear() } }
    }

    private suspend fun perform(operation: () -> List<HistoryEntry>) {
        mutex.withLock {
            val updated = withContext(Dispatchers.IO) {
                runCatching(operation).getOrNull()
            }
            if (updated == null) {
                failure = true
            } else {
                entries = updated
                failure = false
            }
        }
    }
}
