package com.example.mycalendar.desktop.settings

import com.example.mycalendar.desktop.theme.ThemeMode
import kotlinx.serialization.Serializable

/**
 * Настройки desktop-клиента. Сейчас это только то, что уже работает в окне:
 * тема оформления, напоминания и поведение окна. Календарь-провайдеры появятся
 * вместе с синхронизацией.
 */
@Serializable
data class DesktopSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val quietMode: Boolean = false,
    val alarmSoundUri: String? = null,
    val birthdaySoundUri: String? = null,
    /** Крестик окна прячет программу в трей, а не завершает её. */
    val minimizeToTray: Boolean = false,
)
