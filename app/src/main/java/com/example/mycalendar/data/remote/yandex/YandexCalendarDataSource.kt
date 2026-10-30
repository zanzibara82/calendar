package com.example.mycalendar.data.remote.yandex

import com.example.mycalendar.data.auth.AccessTokenProvider
import com.example.mycalendar.data.remote.RemoteCalendarDataSource
import com.example.mycalendar.data.remote.RemoteFetch
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Источник данных Яндекс.Календаря поверх CalDAV (сервер `caldav.yandex.ru`).
 *
 * У Яндекса НЕТ публичного REST API календаря — единственный официальный способ
 * синхронизации это CalDAV. Контракт совместим с [RemoteCalendarDataSource] и
 * [com.example.mycalendar.data.repository.SyncRepositoryImpl]:
 *  - [fetchUpdatedSince] -> REPORT calendar-query (все VEVENT коллекции);
 *  - [insert]/[update]    -> PUT `<календарь>/<uid>.ics`;
 *  - [delete]             -> DELETE `<календарь>/<uid>.ics`.
 *
 * У VEVENT нет надёжного «времени изменения» (LAST-MODIFIED сервер может не обновлять),
 * поэтому инкрементальный pull по updatedMin невозможен и REPORT читает всю коллекцию.
 * На решение о слиянии это не влияет: `SyncConflictResolver.decide` сравнивает
 * снимок-предок, а не updatedAt.
 *
 * Удаления CalDAV не сообщает отдельной строкой — событие просто исчезает из выборки.
 * Поэтому [fetchUpdatedSince] возвращает состав коллекции ([RemoteFetch.presentRemoteIds]),
 * и только если разобрались ВСЕ VEVENT: неразобранный файл — это пропущенный id,
 * по которому нельзя судить об удалении.
 */
@Singleton
class YandexCalendarDataSource @Inject constructor(
    private val caldav: CalDavClient,
    private val auth: AccessTokenProvider,
    private val authRepository: AuthRepository,
) : RemoteCalendarDataSource {

    class YandexCalendarUnavailableException(message: String) : Exception(message)

    /** Кэш URL выбранного календаря в рамках процесса (discovery — дорогая операция). */
    @Volatile
    private var cachedCalendarUrl: String? = null

    /** Пара (email, токен) из зашифрованного хранилища; бросает, если авторизации нет. */
    private suspend fun credentials(): Pair<String, String> {
        val token = auth.getAccessToken()
            ?: throw YandexCalendarUnavailableException("Нет токена Яндекса — войдите заново в Настройках")
        val email = authRepository.accountFor(CalendarProvider.YANDEX)?.email
            ?: throw YandexCalendarUnavailableException("Аккаунт Яндекса не найден")
        return email to token
    }

    private suspend fun calendarUrl(user: String, token: String): String {
        cachedCalendarUrl?.let { return it }
        val calendar = caldav.discoverAndPickCalendar(user, token)
        cachedCalendarUrl = calendar.url
        return calendar.url
    }

    override suspend fun fetchUpdatedSince(sinceMillis: Long): RemoteFetch =
        withContext(Dispatchers.IO) {
            val (user, token) = credentials()
            val url = calendarUrl(user, token)
            val calendars = caldav.fetchEvents(url, user, token)
            val parsed = calendars.mapNotNull { IcsMapper.fromIcs(it) }
            RemoteFetch(
                events = parsed,
                presentRemoteIds = if (parsed.size == calendars.size) {
                    parsed.mapTo(mutableSetOf()) { it.remoteId }
                } else {
                    null
                },
                collectionUrl = url,
            )
        }

    override suspend fun insert(event: Event): String = withContext(Dispatchers.IO) {
        val (user, token) = credentials()
        val url = calendarUrl(user, token)
        val uid = UUID.randomUUID().toString()
        caldav.putEvent(url, user, token, uid, IcsMapper.toIcs(event, uid, System.currentTimeMillis()))
        uid
    }

    override suspend fun update(remoteId: String, event: Event) = withContext(Dispatchers.IO) {
        val (user, token) = credentials()
        val url = calendarUrl(user, token)
        caldav.putEvent(url, user, token, remoteId, IcsMapper.toIcs(event, remoteId, System.currentTimeMillis()))
    }

    override suspend fun delete(remoteId: String) = withContext(Dispatchers.IO) {
        val (user, token) = credentials()
        val url = calendarUrl(user, token)
        caldav.deleteEvent(url, user, token, remoteId)
    }
}
