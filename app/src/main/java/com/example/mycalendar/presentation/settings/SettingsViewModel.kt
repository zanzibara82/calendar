package com.example.mycalendar.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mycalendar.data.auth.OAuthConfig
import com.example.mycalendar.data.remote.yandex.YandexCalendarDataSource
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ThemeMode
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.CalendarConnection
import com.example.mycalendar.domain.repository.ConflictRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import com.example.mycalendar.domain.repository.SyncResult
import com.example.mycalendar.domain.usecase.SyncEventsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Состояние одной карточки календаря (Google / Яндекс) в настройках. */
data class CalendarConnectionState(
    val provider: CalendarProvider,
    val connection: CalendarConnection,
    /** В процессе подключения (запрос токена / ожидание OAuth-редиректа). */
    val isConnecting: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncTime: Long = 0L,
    val result: SyncResult? = null,
) {
    val account = connection.account
    val isConnected: Boolean get() = account != null
    val isBusy: Boolean get() = isConnecting || isSyncing

    /**
     * Доступна ли реальная синхронизация для этого провайдера.
     *
     * Google работает через системный календарь Android (CalendarContract) — он
     * доступен, пока есть runtime-разрешение.
     *
     * Яндекс работает поверх CalDAV (`caldav.yandex.ru`, Basic-авторизация
     * OAuth-токеном) — [YandexCalendarDataSource] реализует pull/push через
     * PROPFIND/REPORT/PUT/DELETE. Кнопка «Синхронизировать» активна при
     * подключённом аккаунте; при ошибке (например, истёк токен) результат
     * показывается в Snackbar'е.
     */
    val syncAvailable: Boolean
        get() = when (provider) {
            CalendarProvider.GOOGLE -> true
            CalendarProvider.YANDEX -> true
            CalendarProvider.LOCAL -> true
        }
}

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Всегда две карточки — подключённые и нет. */
    val calendars: List<CalendarConnectionState> = emptyList(),
    /** Открытые конфликты синхронизации; 0 — строку не показываем. */
    val conflictCount: Int = 0,
)

/**
 * ViewModel настроек: тема + подключение календарей Google/Яндекс.
 *
 * Ключевое отличие от прошлой версии: провайдеры полностью независимы —
 * у каждого свои флаги загрузки, свой результат синхронизации и своё время
 * последней синхронизации. Подключение одного не отключает другой.
 *
 * Google подключается через СИСТЕМНЫЙ календарь Android: OAuth-клиент не нужен,
 * достаточно runtime-разрешения на чтение/запись календаря (его запрашивает
 * экран настроек, см. [onCalendarPermissionResult]).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val syncEvents: SyncEventsUseCase,
    private val conflictRepository: ConflictRepository,
) : ViewModel() {

    /**
     * Показываемые провайдеры: Яндекс появляется только после того, как
     * в OAuthConfig задан YANDEX_CLIENT_ID — иначе его OAuth вернёт 400.
     */
    private val MANAGED: List<CalendarProvider> = buildList {
        add(CalendarProvider.GOOGLE)
        if (OAuthConfig.isYandexConfigured) add(CalendarProvider.YANDEX)
    }

    /** Провайдеры, для которых идёт подключение. */
    private val connecting = MutableStateFlow<Set<CalendarProvider>>(emptySet())

    /** Провайдеры, для которых идёт синхронизация. */
    private val syncing = MutableStateFlow<Set<CalendarProvider>>(emptySet())

    /** Результат последней операции по провайдеру. */
    private val results = MutableStateFlow<Map<CalendarProvider, SyncResult>>(emptyMap())

    /** Открытые конфликты — из таблицы sync_conflicts. */
    private val conflictCount: Flow<Int> = conflictRepository.observeOpenCount()

    /** Флаги загрузки, результаты по провайдерам и очередь конфликтов одним снимком. */
    private data class SyncFlags(
        val connecting: Set<CalendarProvider>,
        val syncing: Set<CalendarProvider>,
        val results: Map<CalendarProvider, SyncResult>,
        val conflicts: Int,
    )

    /** Показан ли диалог с пошаговой настройкой OAuth. */
    private val _showHelp = MutableStateFlow(false)
    val showHelp: StateFlow<Boolean> = _showHelp.asStateFlow()

    /** Время последней синхронизации по каждому провайдеру. */
    private val lastSyncTimes: Flow<Map<CalendarProvider, Long>> = combine(
        MANAGED.map { provider -> settingsRepository.lastSyncTime(provider).map { provider to it } }
    ) { pairs -> pairs.toMap() }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.themeMode,
        settingsRepository.dynamicColor,
        authRepository.connections,
        lastSyncTimes,
        combine(connecting, syncing, results, conflictCount, ::SyncFlags),
    ) { themeMode, dynamicColor, connections, syncTimes, flags ->
        SettingsUiState(
            themeMode = themeMode,
            dynamicColor = dynamicColor,
            calendars = connections.map { connection ->
                CalendarConnectionState(
                    provider = connection.provider,
                    connection = connection,
                    isConnecting = connection.provider in flags.connecting,
                    isSyncing = connection.provider in flags.syncing,
                    lastSyncTime = syncTimes[connection.provider] ?: 0L,
                    result = flags.results[connection.provider],
                )
            },
            conflictCount = flags.conflicts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    // ---------- Тема ----------

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    }

    /** Открыть/закрыть пошаговую инструкцию регистрации OAuth-клиентов. */
    fun showSetupHelp() { _showHelp.value = true }

    fun dismissSetupHelp() { _showHelp.value = false }

    // ---------- Подключение календарей ----------

    /**
     * Подключение Google Календаря через системный календарь Android.
     * Вызывается только после того, как разрешение на календарь получено.
     */
    fun connectGoogle() {
        setBusy(CalendarProvider.GOOGLE, connecting = true)
        viewModelScope.launch {
            val result = authRepository.connectGoogleCalendar()
            if (result.isSuccess) {
                finish(CalendarProvider.GOOGLE, null)
                // Сразу тянем события: иначе непонятно, подключились ли на самом деле
                sync(CalendarProvider.GOOGLE)
            } else {
                finish(
                    CalendarProvider.GOOGLE,
                    SyncResult.failure(
                        CalendarProvider.GOOGLE,
                        result.exceptionOrNull()?.message ?: "Не удалось подключить Google Календарь",
                    ),
                )
            }
        }
    }

    /** Ответ системы на запрос разрешения READ/WRITE_CALENDAR. */
    fun onCalendarPermissionResult(granted: Boolean) {
        if (granted) {
            connectGoogle()
        } else {
            finish(
                CalendarProvider.GOOGLE,
                SyncResult.failure(
                    CalendarProvider.GOOGLE,
                    "Нужен доступ к календарю телефона: разрешите чтение и запись " +
                        "календаря в настройках приложения.",
                ),
            )
        }
    }

    /** Подключение Яндекс ID (браузер + deep link mycalendar://oauth). */
    fun connectYandex() {
        // Проверяем конфигурацию заранее: иначе Яндекс вернёт непонятную «Ошибка 400»
        if (!OAuthConfig.isYandexConfigured) {
            results.update {
                it + (CalendarProvider.YANDEX to SyncResult.failure(
                    CalendarProvider.YANDEX,
                    "Вход через Яндекс не настроен: не задан YANDEX_CLIENT_ID " +
                        "в data/auth/OAuthConfig.kt (Callback URI: mycalendar://oauth).",
                ))
            }
            return
        }
        setBusy(CalendarProvider.YANDEX, connecting = true)
        viewModelScope.launch {
            val result = authRepository.signInWithYandex()
            if (result.isSuccess) {
                finish(CalendarProvider.YANDEX, null)
                sync(CalendarProvider.YANDEX)
            } else {
                finish(
                    CalendarProvider.YANDEX,
                    SyncResult.failure(
                        CalendarProvider.YANDEX,
                        result.exceptionOrNull()?.message ?: "Не удалось подключить Яндекс",
                    ),
                )
            }
        }
    }

    /** Отключить календарь; второй остаётся подключённым. */
    fun disconnect(provider: CalendarProvider) {
        viewModelScope.launch {
            authRepository.signOut(provider)
            results.update { it - provider }
        }
    }

    // ---------- Синхронизация ----------

    /** Синхронизация конкретного календаря. */
    fun sync(provider: CalendarProvider) {
        setBusy(provider, syncing = true)
        results.update { it - provider }
        viewModelScope.launch {
            val result = syncEvents.forProvider(provider)
            finish(provider, result)
        }
    }

    /** Синхронизация всех подключённых календарей. */
    fun syncAll() {
        val providers = uiState.value.calendars.filter { it.isConnected }.map { it.provider }
        if (providers.isEmpty()) return
        providers.forEach { sync(it) }
    }

    // ---------- Вспомогательное ----------

    private fun setBusy(provider: CalendarProvider, connecting: Boolean = false, syncing: Boolean = false) {
        if (connecting) this.connecting.update { it + provider }
        if (syncing) this.syncing.update { it + provider }
    }

    private fun clearBusy(provider: CalendarProvider, connecting: Boolean = false, syncing: Boolean = false) {
        if (connecting) this.connecting.update { it - provider }
        if (syncing) this.syncing.update { it - provider }
    }

    /** Снять все флаги загрузки и (опционально) показать результат. */
    private fun finish(provider: CalendarProvider, result: SyncResult?) {
        clearBusy(provider, connecting = true, syncing = true)
        result?.let { results.update { it + (provider to result) } }
    }
}
