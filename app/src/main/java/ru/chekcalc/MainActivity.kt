package ru.chekcalc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import ru.chekcalc.api.RecognitionClient
import ru.chekcalc.model.Receipt
import ru.chekcalc.api.RecognitionResult
import ru.chekcalc.storage.EncryptedSecretStore
import ru.chekcalc.storage.SettingsRepository
import ru.chekcalc.ui.ChekCalcTheme
import ru.chekcalc.ui.import.PhotoImportScreen
import ru.chekcalc.ui.review.ReceiptDraft
import ru.chekcalc.ui.review.ReviewScreen
import ru.chekcalc.ui.settings.SettingsScreen

private enum class Screen { HOME, IMPORT, REVIEW, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChekCalcTheme {
                val context = LocalContext.current
                val repository = remember {
                    SettingsRepository(EncryptedSecretStore(context.applicationContext))
                }
                val recognition = remember { RecognitionClient() }
                // rememberSaveable: поворот экрана или пересоздание Activity
                // не должны выбрасывать человека с текущего экрана.
                var screenName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
                // Чек переживает поворот экрана: храним его JSON-ом.
                val receiptJson = remember { Json { ignoreUnknownKeys = true } }
                var lastReceiptJson by rememberSaveable { mutableStateOf<String?>(null) }
                val lastReceipt = lastReceiptJson?.let {
                    runCatching { receiptJson.decodeFromString<Receipt>(it) }.getOrNull()
                }
                // Черновик проверки переживает поворот: правки не теряются.
                var draftJson by rememberSaveable { mutableStateOf<String?>(null) }
                val draft = draftJson?.let {
                    runCatching { receiptJson.decodeFromString<ReceiptDraft>(it) }.getOrNull()
                }
                val screen = Screen.valueOf(screenName)

                when (screen) {
                    Screen.HOME -> HomeScreen(
                        lastReceipt = lastReceipt,
                        onImport = { screenName = Screen.IMPORT.name },
                        onOpenSettings = { screenName = Screen.SETTINGS.name },
                    )

                    Screen.IMPORT -> PhotoImportScreen(
                        recognize = { bytes ->
                            val settings = repository.load()
                            if (settings.apiKey.isBlank()) {
                                RecognitionResult.Failure(
                                    ru.chekcalc.api.FailureKind.PROVIDER,
                                    "Сначала задайте ключ в настройках",
                                    retryable = false,
                                )
                            } else {
                                recognition.recognize(bytes, settings)
                            }
                        },
                        onRecognized = { receipt ->
                            lastReceiptJson = receiptJson.encodeToString(Receipt.serializer(), receipt)
                            val draftValue = ReceiptDraft.fromReceipt(receipt)
                            draftJson = receiptJson.encodeToString(ReceiptDraft.serializer(), draftValue)
                            screenName = Screen.REVIEW.name
                        },
                        onBack = { screenName = Screen.HOME.name },
                    )

                    Screen.REVIEW -> {
                        val current = draft
                        if (current == null) {
                            screenName = Screen.HOME.name
                        } else {
                            ReviewScreen(
                                draft = current,
                                onChange = { updated ->
                                    draftJson = receiptJson.encodeToString(ReceiptDraft.serializer(), updated)
                                },
                                // Выбор позиций появится в #2297.
                                onNext = { screenName = Screen.HOME.name },
                                onBack = { screenName = Screen.HOME.name },
                            )
                        }
                    }

                    Screen.SETTINGS -> SettingsScreen(
                        repository = repository,
                        onBack = { screenName = Screen.HOME.name },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    lastReceipt: Receipt?,
    onImport: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(modifier = Modifier.padding(inner).padding(22.dp)) {
            Text("Расчёт по чеку", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Сфотографируйте чек — приложение найдёт позиции и посчитает вашу часть.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            lastReceipt?.let { receipt ->
                Text(
                    "Распознан чек: ${receipt.items.size} поз., итог ${receipt.total} ${receipt.currency}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Text("Загрузить чек")
            }
            Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) {
                Text("Настройки")
            }
        }
    }
}
