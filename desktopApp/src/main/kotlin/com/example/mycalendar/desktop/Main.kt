package com.example.mycalendar.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import com.example.mycalendar.data.remote.yandex.CalDavClient
import com.example.mycalendar.data.remote.yandex.YandexCalendarDataSource
import com.example.mycalendar.data.repository.SyncRepositoryImpl
import com.example.mycalendar.desktop.auth.DeviceCode
import com.example.mycalendar.desktop.auth.DesktopYandexLogin
import com.example.mycalendar.desktop.calendar.EventEditorDialog
import com.example.mycalendar.desktop.calendar.MonthView
import com.example.mycalendar.desktop.conflict.ConflictsController
import com.example.mycalendar.desktop.conflict.ConflictsScreen
import com.example.mycalendar.desktop.data.DesktopAuthRepository
import com.example.mycalendar.desktop.data.DesktopAuthStore
import com.example.mycalendar.desktop.data.DesktopConflictRepository
import com.example.mycalendar.desktop.data.DesktopConflictStore
import com.example.mycalendar.desktop.data.DesktopEventRepository
import com.example.mycalendar.desktop.data.DesktopPaths
import com.example.mycalendar.desktop.data.DesktopRemoteDataSourceProvider
import com.example.mycalendar.desktop.data.DesktopSettingsRepository
import com.example.mycalendar.desktop.data.DesktopSettingsStore
import com.example.mycalendar.desktop.data.EventFileStore
import com.example.mycalendar.desktop.reminder.DesktopReminderScheduler
import com.example.mycalendar.desktop.reminder.DesktopReminderSoundPlayer
import com.example.mycalendar.desktop.reminder.DueReminder
import com.example.mycalendar.desktop.reminder.ReminderBanner
import com.example.mycalendar.desktop.reminder.reminderLabel
import com.example.mycalendar.desktop.settings.DesktopSettings
import com.example.mycalendar.desktop.settings.SettingsScreen
import com.example.mycalendar.desktop.settings.YandexLoginDialog
import com.example.mycalendar.desktop.settings.syncStatusText
import com.example.mycalendar.desktop.theme.DesktopTheme
import com.example.mycalendar.desktop.theme.EventCardGradient
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.reminder.ReminderSoundPolicy
import com.example.mycalendar.domain.repository.SyncResult
import com.example.mycalendar.domain.sync.SyncConflictResolver
import com.example.mycalendar.domain.usecase.ResolveConflictUseCase
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Версия из nativeDistributions.packageVersion; отдельной подписи в градле нет. */
private const val APP_VERSION = "0.1.0"

private enum class Screen { MONTH, SETTINGS, CONFLICTS }

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
    val settingsRepository = DesktopSettingsRepository(settingsStore)
    val authStore = DesktopAuthStore(DesktopPaths.accountFile())
    val conflictRepository =
        DesktopConflictRepository(DesktopConflictStore(DesktopPaths.conflictsFile()))
    application {
        val stored by settingsStore.settings.collectAsState()
        LaunchedEffect(settingsStore) { settingsStore.load() }
        val settings = stored ?: DesktopSettings()
        val windowState = rememberWindowState(width = 1000.dp, height = 760.dp)
        var windowVisible by remember { mutableStateOf(true) }
        val trayAvailable = isTraySupported
        val showWindow = {
            windowVisible = true
            windowState.isMinimized = false
        }

        Window(
            onCloseRequest = {
                if (settings.minimizeToTray && trayAvailable) windowVisible = false else exitApplication()
            },
            visible = windowVisible,
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

            val login = remember { DesktopYandexLogin() }
            var deviceCode by remember { mutableStateOf<DeviceCode?>(null) }
            var loginError by remember { mutableStateOf<String?>(null) }
            var loginJob by remember { mutableStateOf<Job?>(null) }
            val authRepository = remember {
                DesktopAuthRepository(authStore, settingsRepository) {
                    login.signIn { code -> deviceCode = code }
                }
            }
            val connections by authRepository.connections.collectAsState(initial = emptyList())
            val yandexAccount = connections
                .firstOrNull { it.provider == CalendarProvider.YANDEX }
                ?.account

            var reminder by remember { mutableStateOf<DueReminder?>(null) }
            val soundPolicy = remember { ReminderSoundPolicy(settingsRepository) }
            val soundPlayer = remember { DesktopReminderSoundPlayer() }
            val reminderScheduler = remember {
                DesktopReminderScheduler(
                    onReminder = { due ->
                        scope.launch {
                            reminder = due
                            showWindow()
                            val calendarName = settingsStore.settings.value
                                ?.providers
                                ?.get(due.event.calendarProvider.name)
                                ?.calendarName
                            soundPlayer.play(soundPolicy.resolve(due.event, calendarName))
                        }
                    },
                )
            }
            LaunchedEffect(reminderScheduler) {
                reminderScheduler.rescheduleAll(
                    repository.getUpcomingEvents(System.currentTimeMillis()),
                )
            }
            DisposableEffect(reminderScheduler) {
                onDispose { reminderScheduler.shutdown() }
            }
            val syncEngine = remember {
                SyncRepositoryImpl(
                    eventRepository = repository,
                    authRepository = authRepository,
                    settingsRepository = settingsRepository,
                    conflictResolver = SyncConflictResolver(),
                    conflictRepository = conflictRepository,
                    dataSourceProvider = DesktopRemoteDataSourceProvider(
                        YandexCalendarDataSource(
                            caldav = CalDavClient(OkHttpClient()),
                            auth = authRepository,
                            authRepository = authRepository,
                        ),
                    ),
                    reminderScheduler = reminderScheduler,
                )
            }
            val conflictsController = remember {
                ConflictsController(
                    conflictRepository = conflictRepository,
                    eventRepository = repository,
                    resolveConflict = ResolveConflictUseCase(
                        conflictRepository = conflictRepository,
                        eventRepository = repository,
                        reminderScheduler = reminderScheduler,
                    ),
                )
            }
            val conflicts by remember { conflictRepository.observeConflicts() }
                .collectAsState(initial = emptyList())
            val conflictState by conflictsController.state.collectAsState()
            var syncing by remember { mutableStateOf(false) }
            var syncResult by remember { mutableStateOf<SyncResult?>(null) }
            val syncStatus = syncStatusText(syncResult, settings.lastSyncTime, syncing)
            val startSync = {
                if (!syncing) {
                    syncing = true
                    scope.launch {
                        try {
                            syncResult = syncEngine.syncNow()
                        } finally {
                            syncing = false
                        }
                    }
                }
            }

            DesktopTheme(settings.themeMode) {
                Box(Modifier.fillMaxSize()) {
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
                                    scope.launch {
                                        repository.markDeleted(event.id)
                                        reminderScheduler.cancel(event)
                                    }
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
                                        scope.launch {
                                            repository.saveFromUser(event)
                                            reminderScheduler.schedule(event)
                                        }
                                        editor = null
                                    },
                                )

                                is EditorTarget.Existing -> EventEditorDialog(
                                    base = target.event,
                                    date = Instant.ofEpochMilli(target.event.startTime)
                                        .atZone(zone)
                                        .toLocalDate(),
                                    newId = target.id,
                                    zone = zone,
                                    onDismiss = { editor = null },
                                    onSave = { event ->
                                        scope.launch {
                                            repository.saveFromUser(event)
                                            reminderScheduler.schedule(event)
                                        }
                                        editor = null
                                    },
                                    onDelete = { event ->
                                        scope.launch {
                                            repository.markDeleted(event.id)
                                            reminderScheduler.cancel(event)
                                        }
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
                            onMinimizeToTrayChange = { tray ->
                                scope.launch { settingsStore.update { it.copy(minimizeToTray = tray) } }
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
                            account = yandexAccount,
                            loginError = loginError,
                            onSignIn = {
                                loginError = null
                                deviceCode = null
                                loginJob = scope.launch {
                                    authRepository.signInWithYandex().onFailure { error ->
                                        loginError = error.message ?: "Не удалось войти через Яндекс"
                                    }
                                    deviceCode = null
                                    loginJob = null
                                }
                            },
                            onSignOut = {
                                loginError = null
                                scope.launch { authRepository.signOut(CalendarProvider.YANDEX) }
                            },
                            syncStatus = syncStatus,
                            syncing = syncing,
                            onSync = startSync,
                            conflictCount = conflicts.size,
                            onOpenConflicts = { screen = Screen.CONFLICTS },
                        )

                        Screen.CONFLICTS -> ConflictsScreen(
                            conflicts = conflicts,
                            state = conflictState,
                            zone = zone,
                            onBack = { screen = Screen.SETTINGS },
                            onOpen = { id -> scope.launch { conflictsController.open(id) } },
                            onChoose = conflictsController::choose,
                            onApply = { scope.launch { conflictsController.apply() } },
                            onResolveDeletion = { choice ->
                                scope.launch { conflictsController.resolveDeletion(choice) }
                            },
                            onDismissNotice = conflictsController::dismissNotice,
                        )
                    }

                    deviceCode?.let { code ->
                        YandexLoginDialog(
                            code = code,
                            onCopy = { copyToClipboard(code.userCode) },
                            onOpenPage = {
                                if (!openInBrowser(code.verificationUrl)) {
                                    loginError = "Не удалось открыть браузер. " +
                                        "Откройте ${code.verificationUrl} вручную."
                                }
                            },
                            onCancel = {
                                loginJob?.cancel()
                                loginJob = null
                                deviceCode = null
                            },
                            error = loginError,
                        )
                    }

                    reminder?.let { due ->
                        ReminderBanner(
                            title = due.event.title,
                            label = reminderLabel(due.occurrenceStart, due.minutes, zone),
                            onOpen = {
                                val date = Instant.ofEpochMilli(due.event.startTime)
                                    .atZone(zone)
                                    .toLocalDate()
                                month = YearMonth.from(date)
                                selectedDate = date
                                info = due.event
                                screen = Screen.MONTH
                                reminder = null
                            },
                            onDismiss = { reminder = null },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(16.dp),
                        )
                    }
                }
            }
        }

        if (trayAvailable) {
            Tray(
                icon = remember { BitmapPainter(trayIconBitmap()) },
                tooltip = "MyCalendar",
                onAction = showWindow,
            ) {
                Item("Открыть", onClick = showWindow)
                Separator()
                Item("Выход", onClick = ::exitApplication)
            }
        }
    }
}

/**
 * Иконка трея рисуется кодом тем же фирменным градиентом, что и шапка карточки:
 * отдельного ресурса у desktop-модуля нет, а PNG-файл ради 16 пикселей не нужен.
 */
private fun trayIconBitmap(): ImageBitmap {
    val size = 64f
    val bitmap = ImageBitmap(size.toInt(), size.toInt())
    val canvas = Canvas(bitmap)
    val tile = Paint().apply {
        Brush.linearGradient(EventCardGradient).applyTo(Size(size, size), this, 1f)
    }
    canvas.drawRoundRect(0f, 0f, size, size, 14f, 14f, tile)

    val page = Paint().apply { color = Color.White }
    canvas.drawRoundRect(12f, 18f, 52f, 52f, 6f, 6f, page)
    val header = Paint().apply { color = Color(0xFF09235E) }
    canvas.drawRoundRect(12f, 18f, 52f, 29f, 6f, 6f, header)
    canvas.drawRoundRect(21f, 10f, 25f, 22f, 2f, 2f, page)
    canvas.drawRoundRect(39f, 10f, 43f, 22f, 2f, 2f, page)
    val day = Paint().apply { color = Color(0xFF35618F) }
    listOf(22f, 32f, 42f).forEach { x ->
        listOf(36f, 45f).forEach { y -> canvas.drawCircle(Offset(x, y), 2.5f, day) }
    }
    return bitmap
}

/** Системный диалог выбора файла: на desktop «системный сигнал» — это отсутствие файла. */
private fun pickSoundFile(): String? {
    val dialog = FileDialog(null as Frame?, "Выберите звуковой файл", FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    return Path.of(dir, name).toUri().toString()
}

/** Страница входа открывается в системном браузере; false — браузера нет или он не открылся. */
private fun openInBrowser(url: String): Boolean = runCatching {
    if (!Desktop.isDesktopSupported()) return false
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
    desktop.browse(URI(url))
    true
}.getOrDefault(false)

private fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
