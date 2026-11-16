package com.example.mycalendar.desktop.auth

/**
 * OAuth-клиент Google типа «Desktop app».
 *
 * Значения приходят из `local.properties` через JVM-свойства сборки и никогда
 * не попадают в репозиторий. Пустые значения — штатное состояние: сборка
 * проходит, вход в Google просто выключен в настройках.
 */
object DesktopGoogleOAuthConfig {

    private const val CLIENT_ID_PROPERTY = "mycalendar.google.clientId"
    private const val CLIENT_SECRET_PROPERTY = "mycalendar.google.clientSecret"

    val clientId: String
        get() = System.getProperty(CLIENT_ID_PROPERTY).orEmpty().trim()

    val clientSecret: String
        get() = System.getProperty(CLIENT_SECRET_PROPERTY).orEmpty().trim()

    val configured: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    const val SCOPES = "openid email https://www.googleapis.com/auth/calendar.events"
    const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val USER_INFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"
    const val CALENDAR_API_BASE = "https://www.googleapis.com/calendar/v3"
}
