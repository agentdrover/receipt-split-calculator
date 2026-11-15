package ru.chekcalc.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.chekcalc.api.ProbeResult
import ru.chekcalc.api.ProviderProbe
import ru.chekcalc.storage.ProviderPreset
import ru.chekcalc.storage.ProviderSettings
import ru.chekcalc.storage.SaveResult
import ru.chekcalc.storage.SettingsRepository
import ru.chekcalc.ui.LocalChekPalette
import ru.chekcalc.ui.components.BackButton

/**
 * Настройки по макету docs/mockups/home-settings-apple.png.
 *
 * Логика прежняя: провайдер выбирается из списка, у каждого свой адрес, модель
 * и ключ. Ключ нигде не логируется и уходит в сеть только по кнопке
 * «Проверить», с сохранёнными настройками и в фоновом потоке.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    probe: ProviderProbe = remember { ProviderProbe() },
    onBack: () -> Unit = {},
) {
    var provider by remember { mutableStateOf(repository.activeProvider()) }
    // Системный Back и жест возвращают на главный экран.
    BackHandler { onBack() }

    var saved by remember { mutableStateOf(repository.load()) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var model by remember { mutableStateOf(saved.model) }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val palette = LocalChekPalette.current

    fun apply(settings: ProviderSettings) {
        saved = settings
        apiKey = settings.apiKey
        baseUrl = settings.baseUrl
        model = settings.model
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 8.dp),
    ) {
        BackButton(onClick = onBack)
        Text(
            text = "Настройки",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = FontFamily.Serif,
                fontSize = 33.sp,
                lineHeight = 35.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.6).sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = "Приложение ходит к провайдеру напрямую. Ключи хранятся только на этом телефоне.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
            modifier = Modifier.padding(top = 6.dp),
        )

        // Провайдер: строка-группа, по нажатию — список.
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.padding(top = 16.dp),
        ) {
            GroupCard(modifier = Modifier.menuAnchor()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = true }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Провайдер",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = provider.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text("›", fontSize = 19.sp, color = palette.faint)
                }
            }
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ProviderPreset.entries.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.title) },
                        onClick = {
                            expanded = false
                            provider = preset
                            apply(repository.switchTo(preset))
                            message = null
                        },
                    )
                }
            }
        }

        Caption("Подключение", topPadding = 16.dp)
        GroupCard {
            ValueRow(
                label = "Адрес API",
                value = baseUrl,
                onValueChange = { baseUrl = it },
                keyboardType = KeyboardType.Uri,
            )
            HorizontalDivider(color = palette.line)
            ValueRow(
                label = "Модель",
                value = model,
                onValueChange = { model = it },
                keyboardType = KeyboardType.Text,
            )
        }
        provider.modelHint?.let { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp),
            )
        }

        Caption("Ключ", topPadding = 16.dp)
        GroupCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                Text(
                    text = "Ключ ${provider.title}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                BasicTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        color = palette.muted,
                        textAlign = TextAlign.End,
                    ),
                    cursorBrush = SolidColor(palette.hero),
                    visualTransformation = if (saved.apiKey.isEmpty()) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    // TalkBack должен назвать поле, иначе строка читается как значение.
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .clipToBounds()
                        .semantics { contentDescription = "Ключ ${provider.title}" },
                )
            }
        }
        Text(
            text = if (saved.apiKey.isEmpty()) "Ключ не задан" else "Сохранён: ${repository.maskedKey()}",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        ) {
            PillButton(
                text = "Сохранить",
                primary = true,
                modifier = Modifier.weight(1f),
                onClick = {
                    when (val result = repository.save(ProviderSettings(provider.id, apiKey, baseUrl, model))) {
                        is SaveResult.InvalidUrl -> message = result.reason
                        is SaveResult.Saved -> {
                            apply(repository.load())
                            message = if (result.keyReentryRequired) {
                                "Адрес изменён — введите ключ заново"
                            } else {
                                "Сохранено"
                            }
                        }
                    }
                },
            )
            PillButton(
                text = if (checking) "Проверяем…" else "Проверить",
                primary = false,
                enabled = !checking,
                modifier = Modifier.weight(1f),
                onClick = {
                    val typedUrl = baseUrl.trim().trimEnd('/')
                    when {
                        typedUrl != saved.baseUrl -> message = "Сначала сохраните настройки: адрес изменился"
                        saved.apiKey.isBlank() -> message = "Сначала задайте ключ"
                        else -> {
                            // Снимок до запуска: переключение провайдера во время
                            // проверки не должно отправить ключ одного на адрес другого.
                            val target = saved
                            val targetProvider = provider
                            checking = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    probe.check(target.baseUrl, target.apiKey, targetProvider.probePath)
                                }
                                checking = false
                                // Пока шла проверка, провайдера могли переключить:
                                // чужой ответ на экране не показываем.
                                if (provider == targetProvider) {
                                    message = when (result) {
                                        is ProbeResult.Ok -> "Провайдер отвечает, ключ принят"
                                        is ProbeResult.Failed -> result.reason
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 14.dp, start = 2.dp),
            )
        }

        Text(
            text = "Ключ уходит в сеть только по кнопке «Проверить» и не пишется в логи и бэкапы.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            modifier = Modifier.padding(top = 14.dp, bottom = 8.dp, start = 2.dp),
        )
    }
}

@Composable
private fun Caption(text: String, topPadding: androidx.compose.ui.unit.Dp = 12.dp) {
    val palette = LocalChekPalette.current
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
        color = palette.muted,
        modifier = Modifier.padding(top = topPadding, bottom = 8.dp, start = 2.dp),
    )
}

@Composable
private fun GroupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        content()
    }
}

@Composable
private fun ValueRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
) {
    val palette = LocalChekPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
            modifier = Modifier.weight(1f),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(palette.hero),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .clipToBounds()
                .semantics { contentDescription = label },
        )
    }
}

@Composable
private fun PillButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalChekPalette.current
    val background = if (primary) palette.hero else MaterialTheme.colorScheme.surface
    val content = if (primary) palette.heroInk else MaterialTheme.colorScheme.onSurface

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) content else content.copy(alpha = 0.5f),
        )
    }
}
