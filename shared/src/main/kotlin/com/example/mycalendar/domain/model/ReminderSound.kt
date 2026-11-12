package com.example.mycalendar.domain.model

/** Что играть в оверлее. Вычисляется до запуска активности, активность не читает настройки. */
sealed interface ReminderSound {
    data object Silent : ReminderSound
    data class FromUri(val uri: String) : ReminderSound
    data object Builtin : ReminderSound
}