package com.example.mycalendar.desktop.settings

import com.example.mycalendar.desktop.theme.ThemeMode
import kotlinx.serialization.Serializable

/**
 * Служебное состояние синхронизации одного провайдера. Ключ в [DesktopSettings.providers] —
 * имя [com.example.mycalendar.domain.model.CalendarProvider]. Поля повторяют то, что Android
 * держит в DataStore: время последней синхронизации, URL коллекции и её имя.
 */
@Serializable
data class ProviderSyncState(
    val lastSyncTime: Long = 0L,
    val collectionUrl: String? = null,
    val calendarName: String? = null,
)

/**
 * Настройки desktop-клиента: тема оформления, напоминания, поведение окна
 * и служебные метки синхронизации, которые движку нужны наравне с настройками.
 */
@Serializable
data class DesktopSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val quietMode: Boolean = false,
    val alarmSoundUri: String? = null,
    val birthdaySoundUri: String? = null,
    /** Крестик окна прячет программу в трей, а не завершает её. */
    val minimizeToTray: Boolean = false,
    /** Общая метка последней синхронизации (обновляется вместе с провайдерской). */
    val lastSyncTime: Long = 0L,
    /** Флаги, которые на desktop пока не используются, но есть в общем контракте настроек. */
    val notificationsAsked: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val providers: Map<String, ProviderSyncState> = emptyMap(),
)
