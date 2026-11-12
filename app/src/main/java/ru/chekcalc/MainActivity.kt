package ru.chekcalc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.chekcalc.storage.EncryptedSecretStore
import ru.chekcalc.storage.SettingsRepository
import ru.chekcalc.ui.ChekCalcTheme
import ru.chekcalc.ui.settings.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChekCalcTheme {
                val context = LocalContext.current
                val repository = remember {
                    SettingsRepository(EncryptedSecretStore(context.applicationContext))
                }
                var showSettings by remember { mutableStateOf(false) }

                if (showSettings) {
                    SettingsScreen(repository = repository, onBack = { showSettings = false })
                } else {
                    HomeScreen(onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(onOpenSettings: () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(modifier = Modifier.padding(inner).padding(22.dp)) {
            Text("Расчёт по чеку", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Сфотографируйте чек — приложение найдёт позиции и посчитает вашу часть.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            Button(onClick = onOpenSettings) { Text("Настройки") }
        }
    }
}
