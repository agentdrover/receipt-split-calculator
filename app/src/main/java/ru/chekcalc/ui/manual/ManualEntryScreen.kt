package ru.chekcalc.ui.manual

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.chekcalc.calc.Money
import ru.chekcalc.ui.review.MoneyField
import ru.chekcalc.ui.review.QuantityField
import ru.chekcalc.ui.review.ReceiptDraft
import ru.chekcalc.ui.review.formatQty
import ru.chekcalc.ui.review.parseQty
import ru.chekcalc.ui.review.parseRubles
import ru.chekcalc.ui.review.rublesText

/**
 * Ручной ввод чека: позиции, итог, скидка и обслуживание.
 *
 * Форма живёт на том же черновике, что и экран проверки: «Дальше» отдаёт
 * ReceiptDraft, из него собирается receipt.v1, и дальше путь общий с
 * распознанным чеком. Пустые строки не считаются позициями — их можно
 * добавить и не заполнить, чек от этого не станет невалидным.
 */
@Composable
fun ManualEntryScreen(
    draft: ReceiptDraft,
    onChange: (ReceiptDraft) -> Unit,
    onNext: (ReceiptDraft) -> Unit,
    onBack: () -> Unit = {},
) {
    // Системный Back и жест должны вести на главный экран: экраны
    // переключаются состоянием, а не back stack-ом Activity.
    BackHandler { onBack() }

    // Текст полей храним на экране: LazyColumn пересоздаёт строки при прокрутке,
    // и состояние внутри строки терялось бы вместе с подсветкой ошибки.
    // Карта переживает поворот: иначе очищенное или недописанное поле после
    // поворота вернулось бы к прежнему числу из черновика.
    val texts = rememberSaveable(saver = TextMapSaver) { mutableStateMapOf<String, String>() }

    fun text(key: String, value: String): String = texts[key] ?: value

    val totalsInvalid = listOf("total", "discount", "service").any { key ->
        val fallback = when (key) {
            "total" -> rublesText(draft.totalKopecks)
            "discount" -> rublesText(draft.discountKopecks)
            else -> rublesText(draft.serviceKopecks)
        }
        parseRubles(text(key, fallback)) == null
    }
    val linesInvalid = draft.items.any { item ->
        val price = texts[priceKey(item.id)] ?: rublesText(item.unitPriceKopecks)
        val qty = texts[qtyKey(item.id)] ?: item.qty?.let { formatQty(it) } ?: ""
        val qtyValue = if (qty.isBlank()) null else parseQty(qty)
        parseRubles(price) == null ||
            (qty.isNotBlank() && (qtyValue == null || qtyValue <= 0.0)) ||
            item.amountKopecks > ManualReceipt.MAX_AMOUNT_KOPECKS
    }
    val hasItems = ManualReceipt.isReady(draft)
    val ready = hasItems && !totalsInvalid && !linesInvalid

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Column {
                TextButton(onClick = onBack) { Text("Назад") }
                Text("Введите чек вручную", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Внесите позиции как в чеке. Дальше покажем экран проверки — там можно поправить.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MoneyField(
                        label = "Итог",
                        text = text("total", rublesText(draft.totalKopecks)),
                        invalid = parseRubles(text("total", rublesText(draft.totalKopecks))) == null,
                        onTextChange = { raw ->
                            texts["total"] = raw
                            parseRubles(raw)?.let { onChange(draft.changeTotal(it)) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    MoneyField(
                        label = "Скидка",
                        text = text("discount", rublesText(draft.discountKopecks)),
                        invalid = parseRubles(text("discount", rublesText(draft.discountKopecks))) == null,
                        onTextChange = { raw ->
                            texts["discount"] = raw
                            parseRubles(raw)?.let { onChange(draft.changeDiscount(it)) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    MoneyField(
                        label = "Обслуж.",
                        text = text("service", rublesText(draft.serviceKopecks)),
                        invalid = parseRubles(text("service", rublesText(draft.serviceKopecks))) == null,
                        onTextChange = { raw ->
                            texts["service"] = raw
                            parseRubles(raw)?.let { onChange(draft.changeService(it)) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        items(draft.items, key = { it.id }) { item ->
            val price = texts[priceKey(item.id)] ?: rublesText(item.unitPriceKopecks)
            val qty = texts[qtyKey(item.id)] ?: item.qty?.let { formatQty(it) } ?: ""
            val qtyValue = if (qty.isBlank()) null else parseQty(qty)
            val qtyInvalid = qty.isNotBlank() && (qtyValue == null || qtyValue <= 0.0)
            val nameMissing = item.name.isBlank() && item.amountKopecks != 0L
            val amountTooBig = item.amountKopecks > ManualReceipt.MAX_AMOUNT_KOPECKS

            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = item.name,
                    onValueChange = { onChange(draft.rename(item.id, it)) },
                    label = { Text("Позиция") },
                    singleLine = true,
                    isError = nameMissing,
                    supportingText = if (nameMissing) {
                        { Text("Введите название") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuantityField(
                        text = qty,
                        invalid = qtyInvalid,
                        onTextChange = { raw ->
                            texts[qtyKey(item.id)] = raw
                            val parsed = parseQty(raw)
                            if (raw.isBlank() || parsed != null) onChange(draft.changeQty(item.id, parsed))
                        },
                        modifier = Modifier.weight(1f),
                    )
                    MoneyField(
                        label = "Цена",
                        text = price,
                        invalid = parseRubles(price) == null,
                        onTextChange = { raw ->
                            texts[priceKey(item.id)] = raw
                            parseRubles(raw)?.let { onChange(draft.changeUnitPrice(item.id, it)) }
                        },
                        modifier = Modifier.weight(1.2f),
                    )
                    Text(
                        text = Money.format(item.amountKopecks),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (amountTooBig) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        ),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                    )
                    TextButton(onClick = {
                        texts.remove(priceKey(item.id))
                        texts.remove(qtyKey(item.id))
                        onChange(draft.removeItem(item.id))
                    }) { Text("×") }
                }
                if (amountTooBig) {
                    Text(
                        text = "Строка больше 1 млрд ₽ — схема чек не примет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item(key = "actions") {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    onChange(draft.addItem(name = "", unitPriceKopecks = 0))
                }) { Text("Добавить позицию") }
                if (!ready) {
                    Text(
                        // Подсказка называет настоящую причину: позиций нет,
                        // строка пустая или в полях ошибка. «Добавьте позицию»
                        // при уже добавленной строке вводила бы в заблуждение.
                        text = when {
                            draft.items.isEmpty() -> "Добавьте хотя бы одну позицию с названием."
                            draft.items.all { it.name.isBlank() && it.amountKopecks == 0L } ->
                                "Заполните позицию или удалите пустую строку."
                            else -> "Проверьте выделенные поля."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Button(
                    enabled = ready,
                    onClick = { onNext(draft) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Дальше")
                }
            }
        }
    }
}

/** Ключ текста цены позиции в карте полей. */
private fun priceKey(id: Long): String = "price:$id"

/** Ключ текста количества позиции в карте полей. */
private fun qtyKey(id: Long): String = "qty:$id"

/**
 * Сохранение карты текстов полей: плоский список «ключ, значение, …».
 * Список строк переживает пересоздание Activity, SnapshotStateMap — нет.
 */
private val TextMapSaver: Saver<SnapshotStateMap<String, String>, List<String>> = Saver(
    save = { map -> map.flatMap { (key, value) -> listOf(key, value) } },
    restore = { saved ->
        mutableStateMapOf<String, String>().apply {
            saved.chunked(2).forEach { pair ->
                if (pair.size == 2) put(pair[0], pair[1])
            }
        }
    },
)
