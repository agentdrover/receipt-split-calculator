package ru.chekcalc.ui.summary

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.chekcalc.calc.Money
import ru.chekcalc.storage.Payer
import ru.chekcalc.ui.components.BackButton

/**
 * Экран итога: разбивка суммы и отправка её тому, кому переводить.
 *
 * Ничего не пересчитывает: сумма приходит готовой из выбора позиций.
 * Копирование и «Поделиться» отдают один и тот же текст. Экран целиком
 * прокручивается: в альбомной ориентации иначе кнопки вытесняют разбивку.
 */
@Composable
fun SummaryScreen(
    summary: ReceiptSummary,
    payer: Payer = Payer(),
    onPayerChange: (Payer) -> Unit = {},
    onDone: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val text = summary.shareText(payer)
    val rows = summary.lines()

    // Системный «Назад» возвращает к выбору, а не закрывает приложение.
    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(22.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        BackButton(onClick = onBack)
        Text("Итог", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Переведите эту сумму: ${Money.format(summary.totalKopecks)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        // Реквизиты вводятся один раз и запоминаются: без них вторая строка
        // текста не говорит, кому переводить.
        OutlinedTextField(
            value = payer.phone,
            onValueChange = { onPayerChange(payer.copy(phone = it)) },
            label = { Text("Телефон для перевода") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        OutlinedTextField(
            value = payer.name,
            onValueChange = { onPayerChange(payer.copy(name = it)) },
            label = { Text("Имя (не обязательно)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEach { (label, value) ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(text = value, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        Text(
            text = "Итог округлён до рубля — так проще переводить.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )

        if (summary.ownItems + summary.sharedItems > 0) {
            Text(
                text = "Позиций: ${summary.ownItems} своих, ${summary.sharedItems} общих",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("ВАША ЧАСТЬ", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = Money.format(summary.totalKopecks),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }

        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(text)) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text("Скопировать")
        }

        OutlinedButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, "Поделиться расчётом"))
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Поделиться")
        }

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Готово")
        }
    }
}
