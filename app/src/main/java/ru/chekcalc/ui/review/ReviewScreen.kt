package ru.chekcalc.ui.review

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.chekcalc.calc.Money
import ru.chekcalc.calc.rublesToKopecks
import androidx.activity.compose.BackHandler
import ru.chekcalc.ui.components.BackButton

/**
 * Экран «Проверьте чек» по макету docs/mockups/review-check-apple.png.
 *
 * Позиции читаются как список: нажатие открывает лист правки, а не правит
 * поле в строке. Так длинное название переносится и не обрезается, а экран
 * не превращается в набор полей ввода.
 */
@Composable
fun ReviewScreen(
    draft: ReceiptDraft,
    onChange: (ReceiptDraft) -> Unit,
    onNext: () -> Unit = {},
    onBack: () -> Unit = {},
    photo: ByteArray? = null,
    // Перезапуск распознавания этого же фото в глубоком режиме.
    onDeepRecognize: (() -> Unit)? = null,
    deepBusy: Boolean = false,
    deepSummary: String? = null,
    // Наружу нужно знать, открыт ли редактор: во время правки позиции
    // замена черновика сдвинула бы идентификаторы строк.
    onEditingChange: (Boolean) -> Unit = {},
) {
    // Системный Back и жест возвращают на главный экран, а не закрывают приложение.
    BackHandler { onBack() }

    // Лист правки переживает поворот: несохранённый ввод не теряется.
    var itemSheetOpen by rememberSaveable { mutableStateOf(false) }
    var totalsSheetOpenForEdit by rememberSaveable { mutableStateOf(false) }
    var editingItem by rememberSaveable(stateSaver = ItemEditStateSaver) {
        mutableStateOf(ItemEditState.new())
    }
    var totalsSheetOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(itemSheetOpen, totalsSheetOpen) {
        onEditingChange(itemSheetOpen || totalsSheetOpen)
    }
    var editingTotals by rememberSaveable(stateSaver = TotalsEditStateSaver) {
        mutableStateOf(TotalsEditState.of(draft))
    }
    var photoShown by rememberSaveable { mutableStateOf(true) }
    var viewerOpen by rememberSaveable { mutableStateOf(false) }
    // Декодируем один раз: карточка и полноэкранный просмотр делят битмап.
    val photoBitmap by produceState<ImageBitmap?>(initialValue = null, photo) {
        value = photo?.let { bytes ->
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }
    }
    // Масштаб фото переживает поворот и сворачивание карточки.
    val photoView = rememberSaveable(stateSaver = PhotoViewStateSaver) {
        mutableStateOf(PhotoViewState())
    }

    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header") {
                ReviewHeader(
                    draft = draft,
                    onBack = onBack,
                    showDeep = onDeepRecognize != null && photo != null,
                    deepBusy = deepBusy,
                    deepSummary = deepSummary,
                    onDeepRecognize = onDeepRecognize,
                )
            }

            if (photo != null) {
                item(key = "photo") {
                    ReviewPhotoCard(
                        bitmap = photoBitmap,
                        expanded = photoShown,
                        view = photoView.value,
                        onViewChange = { photoView.value = it },
                        onToggle = { photoShown = !photoShown },
                        onOpenViewer = { viewerOpen = true },
                    )
                }
            }

            item(key = "totals") {
                TotalsCard(
                    draft = draft,
                    onEdit = {
                        editingTotals = TotalsEditState.of(draft)
                        totalsSheetOpen = true
                    },
                )
            }

            draft.residualWarning()?.let { warning ->
                item(key = "warning") { ResidualWarning(text = warning) }
            }

            item(key = "items") {
                ItemsCard(
                    draft = draft,
                    onEdit = { item ->
                        editingItem = ItemEditState.of(item)
                        itemSheetOpen = true
                    },
                    onAdd = {
                        editingItem = ItemEditState.new()
                        itemSheetOpen = true
                    },
                )
            }
        }

        PrimaryNextButton(onClick = onNext)
    }

    if (viewerOpen && photo != null) {
        PhotoViewer(bitmap = photoBitmap, onClose = { viewerOpen = false })
    }

    if (itemSheetOpen) {
        ItemEditSheet(
            state = editingItem,
            onChange = { editingItem = it },
            onDismiss = { itemSheetOpen = false },
            onSave = { updated ->
                onChange(updated.apply(draft))
                itemSheetOpen = false
            },
            onDelete = editingItem.itemId?.let { id ->
                {
                    onChange(draft.removeItem(id))
                    itemSheetOpen = false
                }
            },
        )
    }

    if (totalsSheetOpen) {
        TotalsEditSheet(
            state = editingTotals,
            onChange = { editingTotals = it },
            onDismiss = { totalsSheetOpen = false },
            onSave = { updated ->
                onChange(updated.apply(draft))
                totalsSheetOpen = false
            },
        )
    }
}

@Composable
private fun ReviewHeader(
    draft: ReceiptDraft,
    onBack: () -> Unit,
    showDeep: Boolean = false,
    deepBusy: Boolean = false,
    deepSummary: String? = null,
    onDeepRecognize: (() -> Unit)? = null,
) {
    Column {
        BackButton(onClick = onBack)
        Text(
            text = "Проверьте чек",
            style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "${draft.items.size} ${positionsWord(draft.items.size)} · итог ${formatRublesGrouped(draft.totalKopecks, draft.currency)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (showDeep && onDeepRecognize != null) {
            // Строки теряются на длинных и мятых чеках: человек видит, что
            // позиций меньше, чем в чеке, и добивает точность одной кнопкой.
            val mismatch = !draft.residualOk
            TextButton(
                onClick = { if (!deepBusy) onDeepRecognize() },
                enabled = !deepBusy,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = if (deepBusy) "Распознаём точнее…" else "Распознать точнее",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (mismatch) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!deepBusy) {
                Text(
                    text = if (mismatch) {
                        "Позиции не сходятся с итогом: глубокий режим может найти пропущенные строки."
                    } else {
                        "Медленнее, но точнее: помогает на длинных и мятых чеках."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                )
            }
            deepSummary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TotalsCard(draft: ReceiptDraft, onEdit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onEdit),
    ) {
        TotalRow("Итог", formatKopecks(draft.totalKopecks, draft.currency), bold = true)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TotalRow("Скидка", formatKopecks(draft.discountKopecks, draft.currency))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TotalRow("Обслуживание", formatKopecks(draft.serviceKopecks, draft.currency))
    }
}

@Composable
private fun TotalRow(label: String, value: String, bold: Boolean = false) {
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

@Composable
private fun ItemsCard(
    draft: ReceiptDraft,
    onEdit: (DraftItem) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        draft.items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ItemRow(item = item, currency = draft.currency, onClick = { onEdit(item) })
        }
        if (draft.items.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAdd)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("＋", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                text = "Добавить позицию",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ItemRow(item: DraftItem, currency: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name.ifBlank { "Без названия" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${item.qty?.let { formatQty(it) } ?: "1"} × ${formatKopecks(item.unitPriceKopecks, currency)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            text = formatKopecks(item.amountKopecks, currency),
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun ResidualWarning(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    )
}

@Composable
private fun PrimaryNextButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(26.dp),
        contentPadding = PaddingValues(horizontal = 22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 10.dp)
            .height(56.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Дальше", style = MaterialTheme.typography.titleMedium)
            Text("→", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Фото чека сверху: раскрытая карточка с зумом или компактная строка.
 * Пока фото не увеличено и нет щипка, жест не перехватывается — список
 * прокручивается как обычно.
 */
@Composable
private fun ReviewPhotoCard(
    bitmap: ImageBitmap?,
    expanded: Boolean,
    view: PhotoViewState,
    onViewChange: (PhotoViewState) -> Unit,
    onToggle: () -> Unit,
    onOpenViewer: () -> Unit,
) {
    if (!expanded) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp, 44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                bitmap?.let { thumb ->
                    Image(
                        bitmap = thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Text(
                text = "Фото чека",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(rememberPhotoGestures(view = view, onViewChange = onViewChange, onTap = onOpenViewer)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image == null) {
            Text(
                text = "Готовим фото…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Image(
                bitmap = image,
                contentDescription = "Фото чека",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(
                    scaleX = view.scale,
                    scaleY = view.scale,
                    translationX = view.offset.x,
                    translationY = view.offset.y,
                ),
            )
        }
        Text(
            text = "Свернуть",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
        Text(
            text = "нажмите · щипок",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp),
        )
        if (view.scale > PhotoViewState.MIN_SCALE) {
            Text(
                text = "Сбросить масштаб",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
                    .clickable { onViewChange(view.reset()) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemEditSheet(
    state: ItemEditState,
    onChange: (ItemEditState) -> Unit,
    onDismiss: () -> Unit,
    onSave: (ItemEditState) -> Unit,
    onDelete: (() -> Unit)?,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                // Альбомная ориентация с клавиатурой не должна обрезать кнопки.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = if (state.itemId == null) "Новая позиция" else "Позиция",
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
            )
            OutlinedTextField(
                value = state.name,
                onValueChange = { onChange(state.copy(name = it)) },
                label = { Text("Название") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = state.qtyText,
                    onValueChange = { onChange(state.copy(qtyText = it)) },
                    label = { Text("Кол-во") },
                    singleLine = true,
                    isError = state.qtyText.isNotBlank() && state.qty == null,
                    supportingText = if (state.qtyText.isNotBlank() && state.qty == null) {
                        { Text("Введите число") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.priceText,
                    onValueChange = { onChange(state.copy(priceText = it)) },
                    label = { Text("Цена") },
                    singleLine = true,
                    isError = parseRubles(state.priceText) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1.2f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onSave(state) },
                    enabled = state.valid,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text("Сохранить")
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.height(52.dp)) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TotalsEditSheet(
    state: TotalsEditState,
    onChange: (TotalsEditState) -> Unit,
    onDismiss: () -> Unit,
    onSave: (TotalsEditState) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Итог чека",
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
            )
            OutlinedTextField(
                value = state.totalText,
                onValueChange = { onChange(state.copy(totalText = it)) },
                label = { Text("Итог") },
                singleLine = true,
                isError = parseRubles(state.totalText) == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = state.discountText,
                    onValueChange = { onChange(state.copy(discountText = it)) },
                    label = { Text("Скидка") },
                    singleLine = true,
                    isError = parseRubles(state.discountText) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.serviceText,
                    onValueChange = { onChange(state.copy(serviceText = it)) },
                    label = { Text("Обслуживание") },
                    singleLine = true,
                    isError = parseRubles(state.serviceText) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = { onSave(state) },
                enabled = state.valid,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp),
            ) {
                Text("Сохранить")
            }
        }
    }
}

/**
 * Жест по фото: щипок и панорама, а короткое нажатие — открыть просмотр.
 * События, поглощённые дочерними кнопками, не перехватываем.
 */
@Composable
private fun rememberPhotoGestures(
    view: PhotoViewState,
    onViewChange: (PhotoViewState) -> Unit,
    onTap: (() -> Unit)?,
): Modifier {
    val current by rememberUpdatedState(view)
    val tap by rememberUpdatedState(onTap)
    return Modifier.pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = true)
            var moved = false
            var zooming = false
            var consumedByChild = false
            var travelled = Offset.Zero
            do {
                val event = awaitPointerEvent()
                if (event.changes.any { it.isConsumed }) {
                    consumedByChild = true
                    break
                }
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (event.changes.count { it.pressed } > 1 || zoom != 1f) zooming = true
                // Суммарный путь, а не шаг события: плавная панорама идёт
                // мелкими шагами, каждый из которых меньше touchSlop.
                travelled += pan
                if (travelled.getDistance() > slop) moved = true
                if (zooming || current.scale > PhotoViewState.MIN_SCALE) {
                    val next = current.zoom(zoom).let { state ->
                        if (state.scale > PhotoViewState.MIN_SCALE) state.pan(pan) else state
                    }
                    onViewChange(next)
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (!consumedByChild && !moved && !zooming) tap?.invoke()
        }
    }
}

/** Полноэкранный просмотр фото: чёрный фон, зум, крестик и системный Back. */
@Composable
private fun PhotoViewer(bitmap: ImageBitmap?, onClose: () -> Unit) {
    // Увеличение в просмотре тоже переживает поворот.
    var view by rememberSaveable(stateSaver = PhotoViewStateSaver) {
        mutableStateOf(PhotoViewState())
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .then(rememberPhotoGestures(view = view, onViewChange = { view = it }, onTap = null)),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            if (image == null) {
                Text(text = "Готовим фото…", color = Color.White)
            } else {
                Image(
                    bitmap = image,
                    contentDescription = "Фото чека",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(
                        scaleX = view.scale,
                        scaleY = view.scale,
                        translationX = view.offset.x,
                        translationY = view.offset.y,
                    ),
                )
            }
            Text(
                text = "×",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x66000000))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
    }
}

/** Сохранение открытого листа позиции: идентификатор и три поля. */
private val ItemEditStateSaver = listSaver<ItemEditState, String>(
    save = { listOf(it.itemId?.toString() ?: "", it.name, it.qtyText, it.priceText) },
    restore = { values ->
        ItemEditState(
            itemId = values[0].takeIf { it.isNotBlank() }?.toLongOrNull(),
            name = values[1],
            qtyText = values[2],
            priceText = values[3],
        )
    },
)

/** Сохранение открытого листа «Итог чека». */
private val TotalsEditStateSaver = listSaver<TotalsEditState, String>(
    save = { listOf(it.totalText, it.discountText, it.serviceText) },
    restore = { values -> TotalsEditState(values[0], values[1], values[2]) },
)

/** Сохранение масштаба и сдвига фото: список float-ов переживает поворот. */
private val PhotoViewStateSaver = listSaver<PhotoViewState, Float>(
    save = { listOf(it.scale, it.offset.x, it.offset.y) },
    restore = { values -> PhotoViewState(values[0], Offset(values[1], values[2])) },
)

/** Правильная форма слова для подзаголовка: 1 позиция, 2 позиции, 5 позиций. */
private fun positionsWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "позиций"
        mod10 == 1 -> "позиция"
        mod10 in 2..4 -> "позиции"
        else -> "позиций"
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
    val normalized = text.trim().filterNot { it.isWhitespace() }.replace(',', '.')
    if (normalized.isEmpty()) return null
    val value = normalized.toDoubleOrNull() ?: return null
    if (!value.isFinite() || value < 0 || value > MAX_RUBLES) return null
    return runCatching { rublesToKopecks(value) }.getOrNull()
}

/** Разбор количества: пусто — null, мусор и перебор — тоже null. */
internal fun parseQty(text: String): Double? {
    val normalized = text.trim().filterNot { it.isWhitespace() }.replace(',', '.')
    if (normalized.isEmpty()) return null
    val value = normalized.toDoubleOrNull() ?: return null
    if (!value.isFinite() || value < 0 || value > ReceiptDraft.MAX_QTY) return null
    return value
}

/** Рубли с разрядами и без лишних копеек: 124000 → «1 240 ₽», 124050 → «1 240,50 ₽». */
internal fun formatRublesGrouped(kopecks: Long, currency: String = "RUB"): String {
    if (kopecks == Long.MIN_VALUE) return "—"
    val sign = if (kopecks < 0) "−" else ""
    val value = kotlin.math.abs(kopecks)
    val rubles = value / 100
    val rest = value % 100
    val grouped = rubles.toString().reversed().chunked(3).joinToString(" ").reversed()
    val suffix = if (currency == "RUB") "₽" else currency
    return if (rest == 0L) {
        "$sign$grouped $suffix"
    } else {
        "$sign$grouped,${rest.toString().padStart(2, '0')} $suffix"
    }
}

/**
 * Сумма для экранов: копейки и разряды, как в макете: 1 240,00 ₽.
 *
 * Валюта чека сохраняется: чек в вонах не должен выглядеть рублёвым (#2327).
 */
internal fun formatKopecks(kopecks: Long, currency: String = "RUB"): String {
    if (kopecks == Long.MIN_VALUE) return "—"
    val sign = if (kopecks < 0) "−" else ""
    val value = kotlin.math.abs(kopecks)
    val rubles = value / 100
    val rest = value % 100
    val grouped = rubles.toString().reversed().chunked(3).joinToString(" ").reversed()
    val suffix = if (currency == "RUB") "₽" else currency
    return "$sign$grouped,${rest.toString().padStart(2, '0')} $suffix"
}

/** Копейки в рубли для поля ввода: 39025 → «390.25». */
internal fun rublesText(kopecks: Long): String =
    (kopecks / 100).toString() + "." + (kopecks % 100).toString().padStart(2, '0')

/** Количество без лишних нулей: 2.0 → «2», 0.384 → «0.384». */
internal fun formatQty(qty: Double): String =
    if (qty == qty.toLong().toDouble()) qty.toLong().toString() else qty.toString()

private const val MAX_RUBLES = 1_000_000_000.0
