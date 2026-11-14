package ru.chekcalc.ui.review

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    photo: ByteArray? = null,
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
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(22.dp),
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

        // Фото под списком: сверять распознанное с картинкой.
        photo?.let { bytes ->
            item(key = "photo") { ReviewPhotoBlock(photo = bytes) }
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
internal fun MoneyField(
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
internal fun QuantityField(
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

/**
 * Блок фото под списком позиций: сверять распознанное с картинкой.
 *
 * Фото приходит сжатым JPEG в памяти; декодируем на IO, чтобы не подвешивать
 * экран. Переключатель скрывает блок целиком, щипок увеличивает фото.
 */
@Composable
private fun ReviewPhotoBlock(photo: ByteArray) {
    var shown by rememberSaveable { mutableStateOf(true) }
    // Масштаб переживает поворот, как и видимость блока.
    val view = rememberSaveable(stateSaver = PhotoViewStateSaver) {
        mutableStateOf(PhotoViewState())
    }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, photo) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(photo, 0, photo.size)?.asImageBitmap()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { shown = !shown }) {
            Text(if (shown) "Скрыть фото" else "Показать фото")
        }
        if (!shown) return@Column
        val image = bitmap
        if (image == null) {
            Text(
                text = "Готовим фото…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            return@Column
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                // Жест не перехватываем, пока фото не увеличено: иначе один
                // палец по картинке не прокручивает список. Щипок и панорама
                // увеличенного фото поглощают движение сознательно.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zooming = false
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (event.changes.count { it.pressed } > 1 || zoom != 1f) {
                                zooming = true
                            }
                            if (zooming || view.value.scale > PhotoViewState.MIN_SCALE) {
                                view.value = view.value.zoom(zoom).let { state ->
                                    if (state.scale > PhotoViewState.MIN_SCALE) state.pan(pan) else state
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = "Фото чека",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = view.value.scale,
                        scaleY = view.value.scale,
                        translationX = view.value.offset.x,
                        translationY = view.value.offset.y,
                    ),
            )
        }
        if (view.value.scale > PhotoViewState.MIN_SCALE) {
            TextButton(onClick = { view.value = view.value.reset() }) { Text("Сбросить масштаб") }
        }
    }
}

/** Сохранение масштаба и сдвига фото: список float-ов переживает поворот. */
private val PhotoViewStateSaver = listSaver<PhotoViewState, Float>(
    save = { listOf(it.scale, it.offset.x, it.offset.y) },
    restore = { values -> PhotoViewState(values[0], Offset(values[1], values[2])) },
)
