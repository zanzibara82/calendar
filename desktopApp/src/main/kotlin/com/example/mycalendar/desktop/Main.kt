package com.example.mycalendar.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

// Значения — из Android-палитры (тёмная схема эталонная). При переносе темы
// в общий UI-модуль эти константы уйдут: дублировать палитру постоянно нельзя.
private val Background = Color(0xFF0B1220)
private val SurfaceColor = Color(0xFF101B2E)
private val CardColor = Color(0xFF1B2A44)
private val OnSurface = Color(0xFFE8EEF9)
private val OnSurfaceVariant = Color(0xFF9FB0CC)
private val Primary = Color(0xFF4790FF)
private val OnPrimary = Color(0xFF04122B)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "MyCalendar",
        state = rememberWindowState(width = 1000.dp, height = 700.dp),
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                background = Background,
                surface = SurfaceColor,
                onSurface = OnSurface,
                onSurfaceVariant = OnSurfaceVariant,
                primary = Primary,
                onPrimary = OnPrimary,
            ),
        ) {
            SpikeScreen()
        }
    }
}

@Composable
private fun SpikeScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("SyncPlanner для Windows", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Спайк: окно на Compose Multiplatform, домен и слияние берутся из общего модуля.",
                color = OnSurfaceVariant,
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardColor),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Проверка", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Кириллица, тема и Material 3 отрисовываются без Android-зависимостей.",
                        color = OnSurfaceVariant,
                    )
                }
            }
        }
    }
}
