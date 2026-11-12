package com.example.mycalendar.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.mycalendar.desktop.calendar.EventEditorDialog
import com.example.mycalendar.desktop.calendar.MonthView
import com.example.mycalendar.desktop.data.DesktopEventRepository
import com.example.mycalendar.desktop.data.DesktopPaths
import com.example.mycalendar.desktop.data.DesktopSettingsStore
import com.example.mycalendar.desktop.data.EventFileStore
import com.example.mycalendar.desktop.settings.DesktopSettings
import com.example.mycalendar.desktop.settings.SettingsScreen
import com.example.mycalendar.desktop.theme.DesktopTheme
import com.example.mycalendar.domain.model.Event
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.launch

/** Версия из nativeDistributions.packageVersion; отдельной подписи в градле нет. */
private const val APP_VERSION = "0.1.0"

private enum class Screen { MONTH, SETTINGS }

/** Что открыто в редакторе: новый слот выбранного дня или существующее событие. */
private sealed interface EditorTarget {
    val id: String

    data class New(val date: LocalDate, override val id: String = UUID.randomUUID().toString()) : EditorTarget
    data class Existing(val event: Event) : EditorTarget {
        override val id: String get() = event.id
    }
}

fun main() {
    val repository = DesktopEventRepository(EventFileStore(DesktopPaths.eventsFile()))
    val settingsStore = DesktopSettingsStore(DesktopPaths.settingsFile())
    application {
        val stored by settingsStore.settings.collectAsState()
        LaunchedEffect(settingsStore) { settingsStore.load() }
        val settings = stored ?: DesktopSettings()
        val windowState = rememberWindowState(width = 1000.dp, height = 760.dp)

        Window(
            onCloseRequest = ::exitApplication,
            title = "MyCalendar",
            state = windowState,
        ) {
            val scope = rememberCoroutineScope()
            val zone = remember { ZoneId.systemDefault() }
            var screen by remember { mutableStateOf(Screen.MONTH) }
            var month by remember { mutableStateOf(YearMonth.now()) }
            var selectedDate by remember { mutableStateOf(LocalDate.now()) }
            var editor by remember { mutableStateOf<EditorTarget?>(null) }
            var info by remember { mutableStateOf<Event?>(null) }

            DesktopTheme(settings.themeMode) {
                when (screen) {
                    Screen.MONTH -> Box(Modifier.fillMaxSize()) {
                        MonthView(
                            repository = repository,
                            month = month,
                            selectedDate = selectedDate,
                            onMonthChange = { month = it },
                            onSelectDate = {
                                selectedDate = it
                                info = null
                            },
                            infoEvent = info,
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onAddEvent = { editor = EditorTarget.New(it) },
                            onEventClick = { info = it },
                            onCloseInfo = { info = null },
                            onEditEvent = {
                                info = it
                                editor = EditorTarget.Existing(it)
                            },
                            onDeleteEvent = { event ->
                                scope.launch { repository.markDeleted(event.id) }
                                info = null
                            },
                        )
                        when (val target = editor) {
                            is EditorTarget.New -> EventEditorDialog(
                                base = null,
                                date = target.date,
                                newId = target.id,
                                zone = zone,
                                onDismiss = { editor = null },
                                onSave = { event ->
                                    scope.launch { repository.saveFromUser(event) }
                                    editor = null
                                },
                            )

                            is EditorTarget.Existing -> EventEditorDialog(
                                base = target.event,
                                date = Instant.ofEpochMilli(target.event.startTime).atZone(zone).toLocalDate(),
                                newId = target.id,
                                zone = zone,
                                onDismiss = { editor = null },
                                onSave = { event ->
                                    scope.launch { repository.saveFromUser(event) }
                                    editor = null
                                },
                                onDelete = { event ->
                                    scope.launch { repository.markDeleted(event.id) }
                                    editor = null
                                    info = null
                                },
                            )

                            null -> Unit
                        }
                    }

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
