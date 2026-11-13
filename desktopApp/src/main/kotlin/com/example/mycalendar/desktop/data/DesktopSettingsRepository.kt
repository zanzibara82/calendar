package com.example.mycalendar.desktop.data

import com.example.mycalendar.desktop.settings.DesktopSettings
import com.example.mycalendar.desktop.settings.ProviderSyncState
import com.example.mycalendar.desktop.theme.ThemeMode as DesktopThemeMode
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ThemeMode
import com.example.mycalendar.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * [SettingsRepository] поверх settings.json desktop-клиента.
 *
 * Общий движок синхронизации ждёт от настроек те же поля, что Android держит в DataStore.
 * Ключ провайдера в файле — [CalendarProvider.name]; отсутствующая запись читается как
 * значения по умолчанию (нулевое время, коллекция ещё не запоминалась).
 */
class DesktopSettingsRepository(
    private val store: DesktopSettingsStore,
) : SettingsRepository {

    /** Первое значение — гарантированно прочитанный файл, а не временные умолчания. */
    private fun settings(): Flow<DesktopSettings> = flow {
        emit(store.load())
        emitAll(store.settings.filterNotNull())
    }

    override val themeMode: Flow<ThemeMode> = settings().map { it.themeMode.toDomain() }

    override val lastSyncTime: Flow<Long> = settings().map { it.lastSyncTime }

    override val notificationsAsked: Flow<Boolean> = settings().map { it.notificationsAsked }

    override val onboardingCompleted: Flow<Boolean> = settings().map { it.onboardingCompleted }

    override val alarmSoundUri: Flow<String?> = settings().map { it.alarmSoundUri }

    override val birthdaySoundUri: Flow<String?> = settings().map { it.birthdaySoundUri }

    override val quietMode: Flow<Boolean> = settings().map { it.quietMode }

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.update { it.copy(themeMode = mode.toDesktop()) }
    }

    override suspend fun setLastSyncTime(timeMillis: Long) {
        store.update { it.copy(lastSyncTime = timeMillis) }
    }

    override suspend fun setNotificationsAsked() {
        store.update { it.copy(notificationsAsked = true) }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        store.update { it.copy(onboardingCompleted = completed) }
    }

    override fun lastSyncTime(provider: CalendarProvider): Flow<Long> =
        settings().map { it.providerState(provider).lastSyncTime }

    override suspend fun setLastSyncTime(provider: CalendarProvider, timeMillis: Long) {
        store.update {
            it.copy(
                lastSyncTime = timeMillis,
                providers = it.providers.withProvider(
                    provider,
                    it.providerState(provider).copy(lastSyncTime = timeMillis),
                ),
            )
        }
    }

    override fun collectionUrl(provider: CalendarProvider): Flow<String?> =
        settings().map { it.providerState(provider).collectionUrl }

    override fun calendarName(provider: CalendarProvider): Flow<String?> =
        settings().map { it.providerState(provider).calendarName }

    override suspend fun setCollectionUrl(provider: CalendarProvider, url: String) {
        store.update {
            it.copy(
                providers = it.providers.withProvider(
                    provider,
                    it.providerState(provider).copy(collectionUrl = url),
                ),
            )
        }
    }

    override suspend fun setAlarmSoundUri(uri: String?) {
        store.update { it.copy(alarmSoundUri = uri) }
    }

    override suspend fun setBirthdaySoundUri(uri: String?) {
        store.update { it.copy(birthdaySoundUri = uri) }
    }

    override suspend fun setQuietMode(enabled: Boolean) {
        store.update { it.copy(quietMode = enabled) }
    }

    override suspend fun setCalendarName(provider: CalendarProvider, name: String) {
        store.update {
            it.copy(
                providers = it.providers.withProvider(
                    provider,
                    it.providerState(provider).copy(calendarName = name),
                ),
            )
        }
    }

    override suspend fun clearCollectionUrl(provider: CalendarProvider) {
        store.update {
            it.copy(
                providers = it.providers.withProvider(
                    provider,
                    it.providerState(provider).copy(collectionUrl = null, calendarName = null),
                ),
            )
        }
    }
}

private fun DesktopSettings.providerState(provider: CalendarProvider): ProviderSyncState =
    providers[provider.name] ?: ProviderSyncState()

private fun Map<String, ProviderSyncState>.withProvider(
    provider: CalendarProvider,
    value: ProviderSyncState,
): Map<String, ProviderSyncState> = this + (provider.name to value)

private fun DesktopThemeMode.toDomain(): ThemeMode = when (this) {
    DesktopThemeMode.SYSTEM -> ThemeMode.SYSTEM
    DesktopThemeMode.LIGHT -> ThemeMode.LIGHT
    DesktopThemeMode.DARK -> ThemeMode.DARK
}

private fun ThemeMode.toDesktop(): DesktopThemeMode = when (this) {
    ThemeMode.SYSTEM -> DesktopThemeMode.SYSTEM
    ThemeMode.LIGHT -> DesktopThemeMode.LIGHT
    ThemeMode.DARK -> DesktopThemeMode.DARK
}
