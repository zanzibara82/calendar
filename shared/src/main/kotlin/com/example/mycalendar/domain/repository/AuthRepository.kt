package com.example.mycalendar.domain.repository

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.UserAccount
import kotlinx.coroutines.flow.Flow

/**
 * Состояние одного подключаемого календаря (Google / Яндекс).
 *
 * Приложение НЕ требует входа: гостевой режим — это нормальный режим работы,
 * а Google/Яндекс подключаются отдельно и только ради синхронизации событий.
 */
data class CalendarConnection(
    val provider: CalendarProvider,
    /** null — календарь не подключён. */
    val account: UserAccount?,
)

/**
 * Авторизация в календарях Google / Яндекс.
 *
 * КЛЮЧЕВОЕ ОТЛИЧИЕ ОТ ПРЕЖНЕЙ МОДЕЛИ: аккаунтов может быть НЕСКОЛЬКО —
 * по одному на провайдера. Раньше хранился единственный `currentUser`, из-за чего
 * подключение Яндекса выбивало Google и наоборот. Теперь каждый провайдер
 * подключается и синхронизируется независимо.
 */
interface AuthRepository {

    /**
     * Подключённые календари в фиксированном порядке [CalendarProvider.GOOGLE],
     * [CalendarProvider.YANDEX]. Неподключённые присутствуют со значением account = null,
     * чтобы UI мог отрисовать обе карточки сразу.
     */
    val connections: Flow<List<CalendarConnection>>

    /** Аккаунт конкретного провайдера или null, если он не подключён. */
    suspend fun accountFor(provider: CalendarProvider): UserAccount?

    /** Провайдеры, к которым есть рабочий аккаунт. */
    suspend fun connectedProviders(): List<CalendarProvider>

    /**
     * Подключение Google Календаря через СИСТЕМНЫЙ календарь Android.
     *
     * Аккаунт Google уже добавлен на телефон, а его календарь уже синхронизируется
     * системой в [android.provider.CalendarContract] — поэтому OAuth-клиент,
     * client_id и регистрация в Google Cloud Console НЕ нужны вовсе.
     * Требуется только runtime-разрешение READ/WRITE_CALENDAR.
     *
     * @return аккаунт-владелец выбранного календаря (обычно e-mail Google).
     */
    suspend fun connectGoogleCalendar(): Result<UserAccount>

    /**
     * Подключение Яндекс ID: открывает браузер с OAuth-страницей,
     * токен возвращается через deep link mycalendar://oauth (см. MainActivity).
     */
    suspend fun signInWithYandex(): Result<UserAccount>

    /** Отключить только указанный календарь; второй остаётся подключённым. */
    suspend fun signOut(provider: CalendarProvider)

    /** Отключить все календари. */
    suspend fun signOutAll()

    /**
     * OAuth access token для API выбранного провайдера.
     *
     * Google здесь всегда null: его синхронизация идёт через системный календарь
     * ([android.provider.CalendarContract]) и токен не нужен.
     *
     * @param forceRefresh — сбросить закешированный токен (после 401).
     */
    suspend fun getAccessToken(provider: CalendarProvider, forceRefresh: Boolean = false): String?
}
