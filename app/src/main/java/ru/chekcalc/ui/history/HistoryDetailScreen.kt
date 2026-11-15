package ru.chekcalc.ui.history

import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.chekcalc.storage.HistoryEntry
import ru.chekcalc.ui.review.DraftItem
import ru.chekcalc.ui.review.formatKopecks
import ru.chekcalc.ui.select.Choice
import androidx.activity.compose.BackHandler

/**
 * Просмотр сохранённого чека: позиции, отмеченные доли, чаевые и итог.
 * Только чтение: расчёт в истории не меняется.
 */
@Composable
fun HistoryDetailScreen(entry: HistoryEntry, onBack: () -> Unit) {
    // Системный жест «назад» возвращает к списку истории.
    BackHandler { onBack() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") {
            Column {
                TextButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text("‹ Назад", color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = entry.merchant?.takeIf { it.isNotBlank() } ?: "Чек",
                    style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatDate(entry.createdAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item(key = "totals") { TotalsCard(entry) }

        item(key = "items") {
            val choices = entry.selection.items.associateBy { it.id }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                entry.draft.items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ItemRow(
                        item = item,
                        choice = choices[item.id]?.choice,
                    )
                }
            }
        }
    }
}

@Composable
private fun TotalsCard(entry: HistoryEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        RowValue("Итог чека", formatKopecks(entry.draft.totalKopecks, entry.draft.currency))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        RowValue("Скидка", formatKopecks(entry.draft.discountKopecks, entry.draft.currency))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        RowValue("Обслуживание", formatKopecks(entry.draft.serviceKopecks, entry.draft.currency))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        RowValue("Чаевые", formatKopecks(entry.selection.tipKopecks))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        RowValue("Ваша часть", formatKopecks(entry.myShareKopecks, entry.selection.currency), bold = true)
    }
}

@Composable
private fun ItemRow(item: DraftItem, choice: Choice?) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name.ifBlank { "Без названия" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(item.qtyText())
                    append(" × ")
                    append(formatKopecks(item.unitPriceKopecks))
                    if (choice != null) {
                        append(" · ")
                        append(choiceLabel(choice))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            text = formatKopecks(item.amountKopecks),
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

private fun DraftItem.qtyText(): String =
    qty?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "1"

private fun choiceLabel(choice: Choice): String = when (choice) {
    Choice.None -> "не моё"
    Choice.Whole -> "моё"
    is Choice.Shared -> "1/${choice.denominator}"
}

@Composable
private fun RowValue(label: String, value: String, bold: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = (if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                .copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
        )
    }
}
