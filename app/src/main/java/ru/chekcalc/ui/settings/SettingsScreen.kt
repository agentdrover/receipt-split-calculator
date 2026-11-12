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
import ru.chekcalc.storage.ProviderSettings
import ru.chekcalc.storage.SaveResult
import ru.chekcalc.storage.SettingsRepository

/**
 * Экран настроек провайдера.
 *
 * Ключ нигде не логируется и не отправляется сам по себе: сеть уходит только
 * по нажатию «Проверить», с сохранёнными настройками и в фоновом потоке.
 */
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    probe: ProviderProbe = remember { ProviderProbe() },
    onBack: () -> Unit = {},
) {
    var saved by remember { mutableStateOf(repository.load()) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var model by remember { mutableStateOf(saved.model) }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
    ) {
        TextButton(onClick = onBack) { Text("Назад") }
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
            visualTransformation = if (saved.apiKey.isEmpty()) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = if (saved.apiKey.isEmpty()) "Ключ не задан" else "Сохранён: ${repository.maskedKey()}",
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
                        saved = repository.load()
                        apiKey = saved.apiKey
                        baseUrl = saved.baseUrl
                        model = saved.model
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
                        checking = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                probe.check(saved.baseUrl, saved.apiKey)
                            }
                            checking = false
                            message = when (result) {
                                is ProbeResult.Ok -> "Провайдер отвечает, ключ принят"
                                is ProbeResult.Failed -> result.reason
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
