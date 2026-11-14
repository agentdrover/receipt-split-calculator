package ru.chekcalc.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.chekcalc.calc.Money
import ru.chekcalc.calc.rublesToKopecks

/**
 * Экран «Проверьте чек»: позиции с правкой и сверка с итогом.
 *
 * Текст числовых полей хранится на уровне экрана: у LazyColumn позиции
 * пересоздаются при прокрутке, и состояние внутри строки терялось бы вместе
 * с подсветкой ошибки. Расхождение перехода не блокирует, а невалидный
 * ввод — блокирует: иначе «Дальше» унесло бы прежнее значение.
 */
@Composable
fun ReviewScreen(
    draft: ReceiptDraft,
    onChange: (ReceiptDraft) -> Unit,
    onNext: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    // Тексты полей по id позиции; отсутствие записи — значение из черновика.
    val priceTexts = remember { mutableStateMapOf<Long, String>() }
    val qtyTexts = remember { mutableStateMapOf<Long, String>() }
    val totalText = remember { mutableStateMapOf<String, String>() }

    fun text(key: String, value: String): String = totalText[key] ?: value

    val invalidTotal = listOf("total", "discount", "service").any { key ->
        val fallback = when (key) {
            "total" -> rublesText(draft.totalKopecks)
            "discount" -> rublesText(draft.discountKopecks)
            else -> rublesText(draft.serviceKopecks)
        }
        parseRubles(text(key, fallback)) == null
    }
    val invalidItems = draft.items.any { item ->
        val price = priceTexts[item.id] ?: rublesText(item.unitPriceKopecks)
        val qty = qtyTexts[item.id] ?: item.qty?.let { formatQty(it) } ?: ""
        parseRubles(price) == null || (qty.isNotBlank() && parseQty(qty) == null)
    }
    val hasInvalidInput = invalidTotal || invalidItems

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Column {
                TextButton(onClick = onBack) { Text("Назад") }
                Text("Проверьте чек", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Позиций: ${draft.items.size}, сумма ${Money.format(draft.itemsSumKopecks)}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MoneyField(
                        label = "Итог",
                        text = text("total", rublesText(draft.totalKopecks)),
                        invalid = parseRubles(text("total", rublesText(draft.totalKopecks))) == null,
                        onTextChange = { raw ->
                            totalText["total"] = raw
                            parseRubles(raw)?.let { onChange(draft.changeTotal(it)) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    MoneyField(
                        label = "Скидка",
                        text = text("discount", rublesText(draft.discountKopecks)),
                        invalid = parseRubles(text("discount", rublesText(draft.discountKopecks))) == null,
                        onTextChange = { raw ->
                            totalText["discount"] = raw
                            parseRubles(raw)?.let { onChange(draft.changeDiscount(it)) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    MoneyField(
                        label = "Обслуж.",
                        text = text("service", rublesText(draft.serviceKopecks)),
                        invalid = parseRubles(text("service", rublesText(draft.serviceKopecks))) == null,
                        onTextChange = { raw ->
                            totalText["service"] = raw
                            parseRubles(raw)?.let { onChange(draft.changeService(it)) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        draft.residualWarning()?.let { warning ->
            item(key = "warning") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        items(draft.items, key = { it.id }) { item ->
            val price = priceTexts[item.id] ?: rublesText(item.unitPriceKopecks)
            val qty = qtyTexts[item.id] ?: item.qty?.let { formatQty(it) } ?: ""

            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = item.name,
                    onValueChange = { onChange(draft.rename(item.id, it)) },
                    label = { Text("Позиция") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuantityField(
                        text = qty,
                        invalid = qty.isNotBlank() && parseQty(qty) == null,
                        onTextChange = { raw ->
                            qtyTexts[item.id] = raw
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
                            priceTexts[item.id] = raw
                            parseRubles(raw)?.let { onChange(draft.changeUnitPrice(item.id, it)) }
                        },
                        modifier = Modifier.weight(1.2f),
                    )
                    Text(
                        text = Money.format(item.amountKopecks),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                    )
                    TextButton(onClick = {
                        priceTexts.remove(item.id)
                        qtyTexts.remove(item.id)
                        onChange(draft.removeItem(item.id))
                    }) { Text("×") }
                }
            }
        }

        item(key = "actions") {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onChange(draft.addItem()) }) { Text("Добавить позицию") }
                Button(
                    enabled = !hasInvalidInput,
                    onClick = onNext,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Дальше")
                }
            }
        }
    }
}

/** Денежное поле без собственного состояния: текст живёт в вызывающем экране. */
@Composable
private fun MoneyField(
    label: String,
    text: String,
    invalid: Boolean,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text(label) },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) {
            { Text("Введите число") }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun QuantityField(
    text: String,
    invalid: Boolean,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text("Кол-во") },
        singleLine = true,
        isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/**
 * Разбор суммы из поля ввода: запятая и точка, пустая строка и мусор — null,
 * слишком большое или нечисловое значение не роняет приложение.
 */
internal fun parseRubles(text: String): Long? {
    val normalized = text.trim().replace(',', '.')
    if (normalized.isEmpty()) return null
    val value = normalized.toDoubleOrNull() ?: return null
    if (!value.isFinite() || value < 0 || value > MAX_RUBLES) return null
    return runCatching { rublesToKopecks(value) }.getOrNull()
}

/** Разбор количества: пусто — null, мусор и перебор — тоже null. */
internal fun parseQty(text: String): Double? {
    val normalized = text.trim().replace(',', '.')
    if (normalized.isEmpty()) return null
    val value = normalized.toDoubleOrNull() ?: return null
    if (!value.isFinite() || value < 0 || value > ReceiptDraft.MAX_QTY) return null
    return value
}

/** Копейки в рубли для поля ввода: 39025 → «390.25». */
internal fun rublesText(kopecks: Long): String =
    (kopecks / 100).toString() + "." + (kopecks % 100).toString().padStart(2, '0')

/** Количество без лишних нулей: 2.0 → «2», 0.384 → «0.384». */
internal fun formatQty(qty: Double): String =
    if (qty == qty.toLong().toDouble()) qty.toLong().toString() else qty.toString()

private const val MAX_RUBLES = 1_000_000_000.0
