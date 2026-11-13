package com.example.mycalendar.desktop.auth

/**
 * Настройки OAuth Яндекса для desktop-клиента.
 *
 * Это тот же публичный client_id, что и у Android ([com.example.mycalendar.data.auth.OAuthConfig]
 * в модуле :app): вход по коду устройства проверен живым запросом к oauth.yandex.com,
 * отдельная регистрация для Windows не нужна — redirect_uri в этом потоке не участвует.
 *
 * Scope повторяет Android: профиль (login:info, login:email) и полный доступ к Календарю,
 * чтобы полученный токен сразу годился для CalDAV-синхронизации.
 */
object DesktopOAuthConfig {
    const val CLIENT_ID = "a802cf017ad6400f8f3d2cd5d3f334f2"
    const val SCOPES = "login:info login:email calendar:all"

    const val DEVICE_CODE_URL = "https://oauth.yandex.com/device/code"
    const val TOKEN_URL = "https://oauth.yandex.com/token"
    const val USER_INFO_URL = "https://login.yandex.ru/info"
}
