package ru.chekcalc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.chekcalc.ui.ChekCalcTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChekCalcTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    Text(
                        text = "Сфотографируйте чек — посчитаем вашу часть",
                        modifier = Modifier.padding(inner).padding(22.dp),
                    )
                }
            }
        }
    }
}
