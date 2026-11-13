package com.example.mycalendar.data.auth

/**
 * Только чтение OAuth-токена Яндекса.
 *
 * Интерфейс общий для обоих клиентов: [com.example.mycalendar.data.remote.yandex.YandexCalendarDataSource]
 * не знает, где лежит токен. Android хранит его в зашифрованных настройках, у Windows
 * хранилище появится вместе с OAuth-входом; тестам достаточно фейка в памяти.
 */
interface AccessTokenProvider {
    /** Текущий OAuth-токен Яндекса или null, если пользователь не вошёл. */
    fun getAccessToken(): String?
}
