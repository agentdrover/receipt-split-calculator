package ru.chekcalc.ui.history

import ru.chekcalc.storage.HistoryEntry
import ru.chekcalc.storage.SaveOutcome

/** Почему ожидающая запись не ушла: история не читается или запись не удалась. */
enum class SaveFailure { READ, SAVE }

/**
 * Что осталось от записи после попытки.
 *
 * [pending] — очередь в порядке завершения чеков; [saved] — последний
 * записанный список, который надо опубликовать; [preservedCopy] — липкий
 * флаг: прежняя история не читалась, но сохранена копией.
 */
data class HistorySaveState(
    val pending: List<HistoryEntry> = emptyList(),
    val failure: SaveFailure? = null,
    val saved: List<HistoryEntry>? = null,
    val preservedCopy: Boolean = false,
)

/**
 * Очередь записей истории.
 *
 * Один слот терял первый чек, если человек завершал второй до устранения
 * сбоя: вторая запись затирала первую. Очередь пишет по порядку и не теряет
 * неудачную запись. Механика без Android: ViewModel подставляет реальную
 * запись, тест — фейковую.
 */
class HistorySaver(
    private val write: (HistoryEntry) -> SaveOutcome,
    initialPreservedCopy: Boolean = false,
) {
    private var pending: List<HistoryEntry> = emptyList()
    private var failure: SaveFailure? = null
    private var preservedCopy: Boolean = initialPreservedCopy

    val state: HistorySaveState
        get() = HistorySaveState(pending, failure, saved = null, preservedCopy)

    /** Новая запись встаёт в конец очереди; повторный id заменяет старую версию. */
    fun submit(entry: HistoryEntry): HistorySaveState {
        val queue = if (pending.any { it.id == entry.id }) {
            pending.map { if (it.id == entry.id) entry else it }
        } else {
            pending + entry
        }
        return flush(queue)
    }

    /** Повтор всей очереди в том же порядке. */
    fun retry(): HistorySaveState = flush(pending)

    /** Удаление чека снимает его из очереди; опустевшая очередь снимает сбой. */
    fun removed(id: String): HistorySaveState {
        pending = pending.filterNot { it.id == id }
        if (pending.isEmpty()) failure = null
        return HistorySaveState(pending, failure, saved = null, preservedCopy)
    }

    /** Успешная очистка: очередь и сбой снимаются, копия прежней истории неактуальна. */
    fun cleared(): HistorySaveState {
        pending = emptyList()
        failure = null
        preservedCopy = false
        return HistorySaveState(pending, failure, saved = emptyList(), preservedCopy)
    }

    private fun flush(queue: List<HistoryEntry>): HistorySaveState {
        pending = queue
        var saved: List<HistoryEntry>? = null
        var failure: SaveFailure? = null
        while (pending.isNotEmpty()) {
            val next = pending.first()
            val outcome = try {
                write(next)
            } catch (error: Exception) {
                null
            }
            when (outcome) {
                is SaveOutcome.Saved -> {
                    saved = outcome.entries
                    if (outcome.preservedCopy) preservedCopy = true
                    pending = pending.drop(1)
                }
                is SaveOutcome.ReadFailure -> {
                    failure = SaveFailure.READ
                    break
                }
                null -> {
                    failure = SaveFailure.SAVE
                    break
                }
            }
        }
        // Опустевшая очередь доказывает, что повторять нечего.
        this.failure = if (pending.isEmpty()) null else failure
        return HistorySaveState(pending, this.failure, saved, preservedCopy)
    }
}
