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
import ru.chekcalc.storage.EncryptedSecretStore
import ru.chekcalc.storage.SettingsRepository
import ru.chekcalc.ui.ChekCalcTheme
import ru.chekcalc.ui.import.PhotoImportScreen
import ru.chekcalc.ui.settings.SettingsScreen

private enum class Screen { HOME, IMPORT, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChekCalcTheme {
                val context = LocalContext.current
                val repository = remember {
                    SettingsRepository(EncryptedSecretStore(context.applicationContext))
                }
                // rememberSaveable: поворот экрана или пересоздание Activity
                // не должны выбрасывать человека с текущего экрана.
                var screenName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
                val screen = Screen.valueOf(screenName)

                when (screen) {
                    Screen.HOME -> HomeScreen(
                        onImport = { screenName = Screen.IMPORT.name },
                        onOpenSettings = { screenName = Screen.SETTINGS.name },
                    )

                    Screen.IMPORT -> PhotoImportScreen(
                        // Клиент распознавания появится в #2306; до тех пор
                        // экран честно говорит, что отправлять некуда.
                        recognize = { "Распознавание ещё не подключено" },
                        onRecognized = { screenName = Screen.HOME.name },
                        onBack = { screenName = Screen.HOME.name },
                    )

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
private fun HomeScreen(onImport: () -> Unit, onOpenSettings: () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(modifier = Modifier.padding(inner).padding(22.dp)) {
            Text("Расчёт по чеку", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Сфотографируйте чек — приложение найдёт позиции и посчитает вашу часть.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Text("Загрузить чек")
            }
            Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) {
                Text("Настройки")
            }
        }
    }
}
