package ru.chekcalc.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.chekcalc.storage.HistoryEntry
import ru.chekcalc.ui.review.formatKopecks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.compose.BackHandler

/**
 * История чеков: список сохранённых расчётов, удаление и «удалить всё».
 * Фото в истории не хранится — только дата, место и суммы.
 */
@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    failures: Set<HistoryFailure>,
    onOpen: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    // Системный жест «назад» возвращает на главный, а не закрывает приложение.
    BackHandler { onBack() }

    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val historyProblems = failures.filter { it != HistoryFailure.SAVE }
            if (historyProblems.isNotEmpty()) {
                item(key = "failure") {
                    Text(
                        text = historyProblems.joinToString(" ") { kind ->
                            when (kind) {
                                HistoryFailure.DELETE -> "Не удалось удалить чек. Попробуйте ещё раз."
                                HistoryFailure.CLEAR -> "Не удалось очистить историю. Попробуйте ещё раз."
                                HistoryFailure.SAVE -> ""
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                    )
                }
            }

            item(key = "header") {
                Column {
                    TextButton(
                        onClick = onBack,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    ) {
                        Text("‹ Назад", color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        text = "История",
                        style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif),
                    )
                    Text(
                        text = if (entries.isEmpty()) "пока пусто" else "${entries.size} ${checksWord(entries.size)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (entries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "Здесь появятся проверенные чеки: дата, место и ваша сумма. Фото не сохраняется.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp),
                    )
                }
            } else {
                item(key = "list") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            HistoryRow(
                                entry = entry,
                                onOpen = { onOpen(entry) },
                                onDelete = { onDelete(entry) },
                            )
                        }
                    }
                }
                item(key = "clear") {
                    TextButton(onClick = { confirmClear = true }) {
                        Text("Удалить всё", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Удалить всю историю?") },
            text = { Text("Все сохранённые чеки исчезнут с этого телефона. Фото и так не хранится.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatDate(entry.createdAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = entry.merchant?.takeIf { it.isNotBlank() } ?: "Чек",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = "${entry.itemsCount} ${positionsWord(entry.itemsCount)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = formatKopecks(entry.myShareKopecks, entry.draft.currency),
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.padding(start = 12.dp),
        )
        TextButton(onClick = onDelete) {
            Text("×", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Дата записи: «14 ноября, 21:40». */
internal fun formatDate(millis: Long): String =
    SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(millis))

internal fun checksWord(count: Int): String = plural(count, "чек", "чека", "чеков")

internal fun positionsWord(count: Int): String = plural(count, "позиция", "позиции", "позиций")

private fun plural(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}
