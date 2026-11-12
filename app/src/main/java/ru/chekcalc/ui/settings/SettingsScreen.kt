package ru.chekcalc.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ru.chekcalc.api.ProbeResult
import ru.chekcalc.api.ProviderProbe
import ru.chekcalc.storage.ProviderSettings
import ru.chekcalc.storage.SaveResult
import ru.chekcalc.storage.SettingsRepository

/**
 * Экран настроек провайдера.
 *
 * Ключ показывается только в поле ввода и маской после сохранения; экран не
 * логирует значения и не отправляет ключ, пока пользователь не нажмёт
 * «Проверить».
 */
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    probe: ProviderProbe = remember { ProviderProbe() },
) {
    val saved = remember { repository.load() }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var model by remember { mutableStateOf(saved.model) }
    var message by remember { mutableStateOf<String?>(null) }
    var showKey by remember { mutableStateOf(saved.apiKey.isEmpty()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Приложение ходит к провайдеру напрямую. Ключ хранится только на этом телефоне.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Ключ провайдера") },
            singleLine = true,
            visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
            else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = if (repository.maskedKey().isEmpty()) "Ключ не задан" else "Сохранён: ${repository.maskedKey()}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Адрес API (только https)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Модель") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Button(
            onClick = {
                when (val result = repository.save(ProviderSettings(apiKey, baseUrl, model))) {
                    is SaveResult.InvalidUrl -> message = result.reason
                    is SaveResult.Saved -> {
                        apiKey = repository.load().apiKey
                        showKey = false
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
            onClick = {
                message = when (val result = probe.check(baseUrl, apiKey.ifBlank { repository.load().apiKey })) {
                    is ProbeResult.Ok -> "Провайдер отвечает, ключ принят"
                    is ProbeResult.Failed -> result.reason
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Проверить")
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
