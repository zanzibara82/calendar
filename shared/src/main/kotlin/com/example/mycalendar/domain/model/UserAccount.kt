package com.example.mycalendar.domain.model

import com.example.mycalendar.domain.model.CalendarProvider

/**
 * Аккаунт, под которым выполнен вход.
 * Гостевой режим = отсутствие аккаунта (currentUser == null).
 */
data class UserAccount(
    val provider: CalendarProvider,
    val email: String,
    val displayName: String? = null,
)
