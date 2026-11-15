package ru.chekcalc.ui.select

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.chekcalc.calc.Money
import ru.chekcalc.ui.review.parseRubles
import ru.chekcalc.ui.review.rublesText
import androidx.activity.compose.BackHandler
import ru.chekcalc.ui.components.BackButton

/**
 * Экран «Что ваше»: отметки позиций, доли общих и чаевые.
 *
 * По умолчанию не отмечено ничего: человек платит только за то, что выбрал сам.
 * Сумма в нижней панели считается ядром расчёта на каждое нажатие.
 */
@Composable
fun SelectionScreen(
    state: SelectionState,
    onChange: (SelectionState) -> Unit,
    onNext: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    // Системный Back и жест возвращают к проверке, а не закрывают приложение.
    BackHandler { onBack() }

    var tipText by rememberSaveable { mutableStateOf(if (state.tipFixedKopecks > 0) rublesText(state.tipFixedKopecks) else "") }

    // Android 15 принудительно edge-to-edge: без инсетов «Назад» уходит под
    // статус-бар, а «Дальше» — под панель навигации.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(22.dp),
    ) {
        BackButton(onClick = onBack)
        Text("Что ваше", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Отметьте свои позиции. Общие можно разделить на 2–6 человек.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.items, key = { it.id }) { item ->
                val whole = item.choice is Choice.Whole
                val shared = item.choice as? Choice.Shared

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = whole || shared != null,
                                onCheckedChange = { onChange(state.toggleWhole(item.id)) },
                            )
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(Money.format(item.amountKopecks, state.currency))
                        }
                        if (whole || shared != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .padding(start = 48.dp, top = 4.dp)
                                    .horizontalScroll(rememberScrollState()),
                            ) {
                                state.denominatorChoices.forEach { denominator ->
                                    FilterChip(
                                        selected = shared?.denominator == denominator,
                                        onClick = { onChange(state.setShared(item.id, denominator)) },
                                        label = { Text("1/$denominator") },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = "Чаевые",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 5, 10, 15).forEach { percent ->
                FilterChip(
                    selected = state.tipPercent == percent,
                    onClick = {
                        tipText = ""
                        onChange(state.setTipPercent(percent))
                    },
                    label = { Text("$percent %") },
                )
            }
        }
        val tipCaption = when {
            state.tipPercent != null && state.tipKopecks > 0 ->
                "% от вашей части: ${Money.format(state.tipKopecks, state.currency)}"
            state.tipPercent != null -> "% от вашей части"
            state.tipKopecks > 0 -> "своя сумма: ${Money.format(state.tipKopecks, state.currency)}"
            else -> "% от вашей части"
        }
        Text(
            text = tipCaption,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )

        // Своя сумма: текст живёт отдельно от состояния, иначе каждый введённый
        // символ переформатировал поле и набор ломался.
        val tipInvalid = tipText.isNotBlank() && parseRubles(tipText) == null
        OutlinedTextField(
            value = tipText,
            onValueChange = { raw ->
                tipText = raw
                val parsed = parseRubles(raw)
                // Пустое поле — это ноль чаевых, а не «прежняя сумма осталась».
                onChange(if (parsed == null) state.setTipFixed(0) else state.setTipFixed(parsed))
            },
            label = { Text("Своя сумма чаевых") },
            singleLine = true,
            isError = tipInvalid,
            supportingText = if (tipInvalid) {
                { Text("Введите число") }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Ваша часть: ${Money.format(state.totalKopecks, state.currency)}",
                    style = MaterialTheme.typography.titleLarge,
                )
                if (state.tipKopecks > 0) {
                    Text(
                        "в том числе чаевые ${Money.format(state.tipKopecks, state.currency)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Button(
            enabled = !tipInvalid,
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Дальше")
        }
    }
}
