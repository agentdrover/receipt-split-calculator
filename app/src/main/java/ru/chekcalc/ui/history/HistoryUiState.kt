package ru.chekcalc.ui.history

import ru.chekcalc.storage.HistoryEntry

/** Состояние истории, которое видит экран. */
data class HistoryUiState(
    val entries: List<HistoryEntry> = emptyList(),
    val failures: Set<HistoryFailure> = emptySet(),
    val saving: HistorySaveState = HistorySaveState(),
)

/**
 * Применение результата записи к состоянию — единственное место, где решается,
 * что показать после попытки.
 *
 * Чистая функция: тест проверяет переход целиком (список, очередь, сбои), а не
 * отдельные поля, и не пропустит потерю публикации записанного списка.
 */
internal fun applySaveResult(
    state: HistoryUiState,
    result: HistorySaveState,
): HistoryUiState =
    state.copy(
        entries = result.saved ?: state.entries,
        failures =
            nextFailuresAfterSave(
                current = state.failures,
                failure = result.failure,
                anySaved = result.saved != null,
            ),
        saving = result,
    )
