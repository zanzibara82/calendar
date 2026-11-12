package com.example.mycalendar.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.mycalendar.desktop.calendar.MonthView
import com.example.mycalendar.desktop.data.DesktopEventRepository
import com.example.mycalendar.desktop.data.DesktopPaths
import com.example.mycalendar.desktop.data.DesktopSettingsStore
import com.example.mycalendar.desktop.data.EventFileStore
import com.example.mycalendar.desktop.settings.DesktopSettings
import com.example.mycalendar.desktop.settings.SettingsScreen
import com.example.mycalendar.desktop.theme.DesktopTheme
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.launch

/** Версия из nativeDistributions.packageVersion; отдельной подписи в градле нет. */
private const val APP_VERSION = "0.1.0"

private enum class Screen { MONTH, SETTINGS }

fun main() {
    val repository = DesktopEventRepository(EventFileStore(DesktopPaths.eventsFile()))
    val settingsStore = DesktopSettingsStore(DesktopPaths.settingsFile())
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "MyCalendar",
            state = rememberWindowState(width = 1000.dp, height = 760.dp),
        ) {
            val scope = rememberCoroutineScope()
            val stored by settingsStore.settings.collectAsState()
            LaunchedEffect(settingsStore) { settingsStore.load() }
            val settings = stored ?: DesktopSettings()
            var screen by remember { mutableStateOf(Screen.MONTH) }
            var month by remember { mutableStateOf(YearMonth.now()) }
            var selectedDate by remember { mutableStateOf(LocalDate.now()) }

            DesktopTheme(settings.themeMode) {
                when (screen) {
                    Screen.MONTH -> MonthView(
                        repository = repository,
                        month = month,
                        selectedDate = selectedDate,
                        onMonthChange = { month = it },
                        onSelectDate = { selectedDate = it },
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )

                    Screen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        dataDir = DesktopPaths.dataDir().toString(),
                        version = APP_VERSION,
                        onBack = { screen = Screen.MONTH },
                        onThemeChange = { mode ->
                            scope.launch { settingsStore.update { it.copy(themeMode = mode) } }
                        },
                        onQuietChange = { quiet ->
                            scope.launch { settingsStore.update { it.copy(quietMode = quiet) } }
                        },
                        onPickAlarm = {
                            pickSoundFile()?.let { uri ->
                                scope.launch { settingsStore.update { it.copy(alarmSoundUri = uri) } }
                            }
                        },
                        onResetAlarm = {
                            scope.launch { settingsStore.update { it.copy(alarmSoundUri = null) } }
                        },
                        onPickBirthday = {
                            pickSoundFile()?.let { uri ->
                                scope.launch { settingsStore.update { it.copy(birthdaySoundUri = uri) } }
                            }
                        },
                        onResetBirthday = {
                            scope.launch { settingsStore.update { it.copy(birthdaySoundUri = null) } }
                        },
                    )
                }
            }
        }
    }
}

/** Системный диалог выбора файла: на desktop «системный сигнал» — это отсутствие файла. */
private fun pickSoundFile(): String? {
    val dialog = FileDialog(null as Frame?, "Выберите звуковой файл", FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    return Path.of(dir, name).toUri().toString()
}
