package com.example.mycalendar.data.auth

/**
 * Только чтение OAuth-токена Яндекса.
 *
 * Выделен как интерфейс, чтобы [com.example.mycalendar.data.remote.yandex.YandexCalendarDataSource]
 * можно было тестировать на JVM без Android Keystore: реальный [YandexAuthDataSource] хранит
 * токен в [androidx.security.crypto.EncryptedSharedPreferences] (требует Context + Keystore), а
 * в юнит-тесте достаточно фейковой реализации, возвращающей строку из памяти.
 */
interface AccessTokenProvider {
    /** Текущий OAuth-токен Яндекса или null, если пользователь не вошёл. */
    fun getAccessToken(): String?
}
