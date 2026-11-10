package com.example.mycalendar.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ThemeMode
import com.example.mycalendar.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Настройки пользователя (тема и время последней синхронизации)
 * в DataStore Preferences.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LAST_SYNC = longPreferencesKey("last_sync_time")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("notification_permission_asked")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val ALARM_SOUND_URI = stringPreferencesKey("alarm_sound_uri")
        val BIRTHDAY_SOUND_URI = stringPreferencesKey("birthday_sound_uri")
        val QUIET_MODE = booleanPreferencesKey("quiet_mode")

        /** Время последней синхронизации провайдера: last_sync_time_google / _yandex. */
        fun lastSync(provider: CalendarProvider) =
            longPreferencesKey("last_sync_time_${provider.name.lowercase()}")

        /** Коллекция, с которой сверяемся: collection_url_google / _yandex. */
        fun collection(provider: CalendarProvider) =
            stringPreferencesKey("collection_url_${provider.name.lowercase()}")

        /** Имя коллекции: calendar_name_google / _yandex. */
        fun calendarName(provider: CalendarProvider) =
            stringPreferencesKey("calendar_name_${provider.name.lowercase()}")
    }

    override val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { name ->
            runCatching { ThemeMode.valueOf(name) }.getOrNull()
        } ?: ThemeMode.SYSTEM
    }

    override val lastSyncTime: Flow<Long> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_SYNC] ?: 0L
    }

    override val notificationsAsked: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.NOTIFICATIONS_ASKED] ?: false
    }

    override val onboardingCompleted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_COMPLETED] ?: false
    }

    override val alarmSoundUri: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.ALARM_SOUND_URI]
    }

    override val birthdaySoundUri: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.BIRTHDAY_SOUND_URI]
    }

    override val quietMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.QUIET_MODE] ?: false
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    override suspend fun setLastSyncTime(timeMillis: Long) {
        dataStore.edit { it[Keys.LAST_SYNC] = timeMillis }
    }

    override suspend fun setNotificationsAsked() {
        dataStore.edit { it[Keys.NOTIFICATIONS_ASKED] = true }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    override fun lastSyncTime(provider: CalendarProvider): Flow<Long> = dataStore.data.map { prefs ->
        prefs[Keys.lastSync(provider)] ?: 0L
    }

    override suspend fun setLastSyncTime(provider: CalendarProvider, timeMillis: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.lastSync(provider)] = timeMillis
            // Агрегат обновляем тоже — по нему SyncWorker и общий статус
            prefs[Keys.LAST_SYNC] = timeMillis
        }
    }

    override fun collectionUrl(provider: CalendarProvider): Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.collection(provider)]
    }

    override fun calendarName(provider: CalendarProvider): Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.calendarName(provider)]
    }

    override suspend fun setCollectionUrl(provider: CalendarProvider, url: String) {
        dataStore.edit { it[Keys.collection(provider)] = url }
    }

    override suspend fun setAlarmSoundUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.ALARM_SOUND_URI) else prefs[Keys.ALARM_SOUND_URI] = uri
        }
    }

    override suspend fun setBirthdaySoundUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.BIRTHDAY_SOUND_URI) else prefs[Keys.BIRTHDAY_SOUND_URI] = uri
        }
    }

    override suspend fun setQuietMode(enabled: Boolean) {
        dataStore.edit { it[Keys.QUIET_MODE] = enabled }
    }

    override suspend fun setCalendarName(provider: CalendarProvider, name: String) {
        dataStore.edit { it[Keys.calendarName(provider)] = name }
    }

    override suspend fun clearCollectionUrl(provider: CalendarProvider) {
        dataStore.edit {
            it.remove(Keys.collection(provider))
            it.remove(Keys.calendarName(provider))
        }
    }
}
