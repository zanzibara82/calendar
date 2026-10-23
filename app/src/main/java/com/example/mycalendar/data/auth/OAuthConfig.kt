package com.example.mycalendar.data.auth

/**
 * Единое место для OAuth-настроек приложения.
 *
 * ПОЧЕМУ ЗДЕСЬ: client_id хранился прямо в DataSource в виде плейсхолдера
 * "REPLACE_WITH_..." — из-за этого Яндекс отвечал HTTP 400 (invalid_client),
 * а Google — UNREGISTERED_ON_API_CONSOLE.
 *
 * ЧТОБЫ АВТОРИЗАЦИЯ ЯНДЕКСА ЗАРАБОТАЛА, сюда вписано реальное значение:
 *
 *  Яндекс ID — https://oauth.yandex.ru (Создать приложение):
 *      - Платформы → Веб-сервисы → Callback URI: mycalendar://oauth
 *      - Доступы → «Яндекс.Паспорт»: доступ к email / логину
 *      - Доступы → «Яндекс.Календарь»: чтобы токен был пригоден для
 *        будущей синхронизации (на экране согласия пользователь увидит
 *        12 прав на Календарь — это нормально).
 *    Скопируйте «ID приложения» в YANDEX_CLIENT_ID.
 *
 * Client secret в приложении НЕ хранится и не нужен: используется implicit
 * flow (response_type=token), токен приходит во фрагменте redirect_uri.
 * Секрет — только для серверного (authorization code) обмена, попадание его
 * в APK компрометирует приложение.
 *
 * GOOGLE ЗДЕСЬ НЕ НУЖЕН: Google Календарь подключается через системный
 * календарь Android (CalendarContract) — аккаунт уже есть на телефоне,
 * поэтому ни OAuth-клиента, ни client_id, ни регистрации в Google Cloud
 * Console не требуется. Нужны только runtime-разрешения
 * READ_CALENDAR / WRITE_CALENDAR (см. SystemCalendarDataSource).
 *
 * Пока client_id не задан, приложение НЕ открывает браузер, а сразу
 * показывает понятное сообщение (см. [isYandexConfigured]).
 */
object OAuthConfig {

    /** ID приложения из https://oauth.yandex.ru. */
    const val YANDEX_CLIENT_ID = "a802cf017ad6400f8f3d2cd5d3f334f2"

    /** Redirect URI — должен СОВПАДАТЬ с указанным в настройках Яндекс-приложения. */
    const val YANDEX_REDIRECT_URI = "mycalendar://oauth"

    /** Признак «Яндекс настроен» — пустой/незаполненный client_id означает ошибку 400. */
    val isYandexConfigured: Boolean
        get() = YANDEX_CLIENT_ID.isNotBlank() &&
            !YANDEX_CLIENT_ID.startsWith("REPLACE")
}
