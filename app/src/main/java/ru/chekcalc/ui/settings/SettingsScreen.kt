package ru.chekcalc.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.chekcalc.api.ProbeResult
import ru.chekcalc.api.ProviderProbe
import ru.chekcalc.storage.ProviderPreset
import ru.chekcalc.storage.ProviderSettings
import ru.chekcalc.storage.SaveResult
import ru.chekcalc.storage.SettingsRepository

/**
 * Экран настроек провайдера.
 *
 * Провайдер выбирается из списка; у каждого свой адрес, модель и ключ.
 * Ключ нигде не логируется и уходит в сеть только по кнопке «Проверить»,
 * с сохранёнными настройками и в фоновом потоке.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    probe: ProviderProbe = remember { ProviderProbe() },
    onBack: () -> Unit = {},
) {
    var provider by remember { mutableStateOf(repository.activeProvider()) }
    var saved by remember { mutableStateOf(repository.load()) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var model by remember { mutableStateOf(saved.model) }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun apply(settings: ProviderSettings) {
        saved = settings
        apiKey = settings.apiKey
        baseUrl = settings.baseUrl
        model = settings.model
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
    ) {
        TextButton(onClick = onBack) { Text("Назад") }
        Text("Настройки", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Приложение ходит к провайдеру напрямую. Ключи хранятся только на этом телефоне, у каждого провайдера свой.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )

        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = provider.title,
                onValueChange = {},
                readOnly = true,
                label = { Text("Провайдер") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
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

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Адрес API (только https)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Модель") },
            singleLine = true,
            supportingText = provider.modelHint?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Ключ ${provider.title}") },
            singleLine = true,
            visualTransformation = if (saved.apiKey.isEmpty()) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text(
            text = if (saved.apiKey.isEmpty()) "Ключ не задан" else "Сохранён: ${repository.maskedKey()}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )

        Button(
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
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text("Сохранить")
        }

        Button(
            enabled = !checking,
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
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(if (checking) "Проверяем…" else "Проверить")
        }

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
