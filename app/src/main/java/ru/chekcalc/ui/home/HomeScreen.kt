package ru.chekcalc.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.chekcalc.calc.Money
import ru.chekcalc.calc.rublesToKopecks
import ru.chekcalc.model.Receipt
import ru.chekcalc.ui.LocalChekPalette
import java.util.Locale

/**
 * Главный экран по макету docs/mockups/home-settings-apple.png.
 *
 * Порядок чтения сверху вниз: что сделать (заголовок), одно большое действие
 * (зелёная карточка), что было в прошлый раз, и «Ещё» — остальные входы.
 */
@Composable
fun HomeScreen(
    lastReceipt: Receipt?,
    onImport: () -> Unit,
    onManual: () -> Unit,
    onOpenSettings: () -> Unit,
    // История появится вместе с #2299; до неё строка не показывается.
    onHistory: (() -> Unit)? = null,
    historyCount: Int = 0,
    historyFailure: Boolean = false,
    historyReadFailure: Boolean = false,
    historyPending: Int = 0,
    onRetryHistory: (() -> Unit)? = null,
) {
    val palette = LocalChekPalette.current

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 8.dp),
    ) {
        Text(
            // Размер подобран под 320–390 dp: на 33 sp «Сфотографируйте»
            // рвалось по слогам на узком экране.
            text = "Сфотографируйте чек",
            style =
                MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontSize = 30.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.5).sp,
                ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "Найдём позиции, распределим скидку и обслуживание, посчитаем вашу часть.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
            modifier = Modifier.padding(top = 6.dp),
        )

        HeroCard(onClick = onImport, modifier = Modifier.padding(top = 22.dp))

        lastReceipt?.let { receipt ->
            LastReceiptRow(receipt, modifier = Modifier.padding(top = 14.dp))
        }

        Text(
            text = "ЕЩЁ",
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted,
            modifier = Modifier.padding(top = 18.dp, bottom = 8.dp, start = 2.dp),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface),
        ) {
            GroupRow(
                glyph = "✎",
                title = "Ввести вручную",
                subtitle = "если фото нет или распознавание не удалось",
                onClick = onManual,
            )
            HorizontalDivider(color = palette.line)
            onHistory?.let { openHistory ->
                GroupRow(
                    glyph = "◷",
                    title = "История",
                    // «Пока пусто» здесь означало бы, что записей нет; при сбое
                    // чтения это неправда.
                    subtitle =
                        if (historyReadFailure) {
                            "не прочитана — чеки не показаны"
                        } else {
                            historyLabel(historyCount)
                        },
                    onClick = openHistory,
                )
                HorizontalDivider(color = palette.line)
            }
            GroupRow(
                glyph = "⚙",
                title = "Настройки",
                subtitle = "провайдер, модель, ключ",
                onClick = onOpenSettings,
            )
        }

        if (historyReadFailure) {
            Text(
                text = "История не прочитана: сохранённые чеки не показаны. Откройте историю, чтобы увидеть состояние.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (historyFailure) {
            Text(
                text = "Не удалось сохранить чек в историю. Попробуйте ещё раз.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        // Кнопка видна по непустой очереди, а не по виду сбоя: чек мог
        // не сохраниться и без флага на этом экране.
        if (historyPending > 0) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    text = pendingLabel(historyPending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                onRetryHistory?.let { retry ->
                    TextButton(onClick = retry) {
                        Text("Сохранить ещё раз", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** «3 позиции», «21 позиция», «11 позиций» — русское склонение по числу. */
internal fun positionsLabel(count: Int): String =
    "$count " +
        when {
            count % 10 == 1 && count % 100 != 11 -> "позиция"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "позиции"
            else -> "позиций"
        }

/** Подпись строки истории: «пока пусто», «1 сохранённый чек», «2 сохранённых чека». */
internal fun historyLabel(count: Int): String =
    when {
        count <= 0 -> "пока пусто"
        count % 10 == 1 && count % 100 != 11 -> "$count сохранённый чек"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "$count сохранённых чека"
        else -> "$count сохранённых чеков"
    }

/** Подпись очереди: «1 чек ждёт записи», «2 чека ждут записи», «5 чеков ждут записи». */
internal fun pendingLabel(count: Int): String =
    when {
        count % 10 == 1 && count % 100 != 11 -> "$count чек ждёт записи"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "$count чека ждут записи"
        else -> "$count чеков ждут записи"
    }

@Composable
private fun HeroCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalChekPalette.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(palette.hero)
                .clickable(onClick = onClick)
                .heightIn(min = 92.dp)
                .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
        ) {
            Text("⌾", fontSize = 24.sp, color = palette.heroInk)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Сфотографировать чек",
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        lineHeight = 18.sp,
                    ),
                color = palette.heroInk,
            )
            Text(
                text = "камера или галерея",
                style = MaterialTheme.typography.bodySmall,
                color = palette.heroInk.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Text("→", fontSize = 18.sp, color = palette.heroInk.copy(alpha = 0.85f))
    }
}

@Composable
private fun LastReceiptRow(
    receipt: Receipt,
    modifier: Modifier = Modifier,
) {
    val palette = LocalChekPalette.current
    val items = receipt.items.size
    // rublesToKopecks округляет, а не отбрасывает: 19,99 не становится 19,98.
    // Валюта показывается своя: зарубежный чек — не рубли.
    val total =
        if (receipt.currency == "RUB") {
            Money.format(rublesToKopecks(receipt.total))
        } else {
            "%s %s".format(Locale.US, "%.2f".format(Locale.US, receipt.total), receipt.currency)
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Последний чек",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${positionsLabel(items)} · $total",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun GroupRow(
    glyph: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val palette = LocalChekPalette.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = glyph,
            color = palette.glyph,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.size(width = 26.dp, height = 20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text("›", fontSize = 19.sp, color = palette.faint)
    }
}
