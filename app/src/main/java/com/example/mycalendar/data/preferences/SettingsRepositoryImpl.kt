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
 * Настройки пользователя (тема, динамические цвета, время последней синхронизации)
 * в DataStore Preferences.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LAST_SYNC = longPreferencesKey("last_sync_time")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("notification_permission_asked")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

        /** Время последней синхронизации провайдера: last_sync_time_google / _yandex. */
        fun lastSync(provider: CalendarProvider) =
            longPreferencesKey("last_sync_time_${provider.name.lowercase()}")

        /** Коллекция, с которой сверяемся: collection_url_google / _yandex. */
        fun collection(provider: CalendarProvider) =
            stringPreferencesKey("collection_url_${provider.name.lowercase()}")
    }

    override val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { name ->
            runCatching { ThemeMode.valueOf(name) }.getOrNull()
        } ?: ThemeMode.SYSTEM
    }

    override val dynamicColor: Flow<Boolean> = dataStore.data.map { prefs ->
        // Динамические цвета включены по умолчанию на Android 12+
        prefs[Keys.DYNAMIC_COLOR] ?: true
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

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
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

    override suspend fun setCollectionUrl(provider: CalendarProvider, url: String) {
        dataStore.edit { it[Keys.collection(provider)] = url }
    }

    override suspend fun clearCollectionUrl(provider: CalendarProvider) {
        dataStore.edit { it.remove(Keys.collection(provider)) }
    }
}
