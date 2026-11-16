package com.example.mycalendar.data.remote.google

import com.example.mycalendar.data.remote.RemoteCalendarDataSource
import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.data.remote.RemoteFetch
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.repository.AuthRepository
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Google не подключён, токен истёк или API отказал — текст уже готов для интерфейса. */
class GoogleCalendarUnavailableException(message: String) : Exception(message)

/**
 * Google Calendar API v3: полный pull по `primary` и запись событий.
 *
 * У провайдера есть инкрементальный `syncToken`, но движку синхронизации хватает
 * полной выборки: слияние и так сравнивает снимок-предок, а не время изменения.
 * Удалённые события приходят как `status=cancelled` (tombstone), поэтому
 * `presentRemoteIds` содержит их id: они ещё в коллекции и не «исчезли».
 *
 * Токен берётся у [AuthRepository]; на 401 делается ровно один принудительный
 * рефреш и повтор запроса. Ответ Google не попадает в текст ошибки — только код.
 */
class GoogleCalendarDataSource(
    private val http: OkHttpClient,
    private val authRepository: AuthRepository,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Long = { System.currentTimeMillis() },
) : RemoteCalendarDataSource {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchUpdatedSince(sinceMillis: Long): RemoteFetch =
        withContext(Dispatchers.IO) {
            val events = mutableListOf<RemoteEvent>()
            val ids = mutableSetOf<String>()
            var pageToken: String? = null
            do {
                val page = json.decodeFromString<GoogleEventsPageDto>(
                    executeAuthorized(eventsRequest(pageToken), forceRefresh = false)
                )
                for (dto in page.items) {
                    val mapped = GoogleEventMapper.fromDto(dto, zone(), now()) ?: continue
                    events += mapped
                    ids += mapped.remoteId
                }
                pageToken = page.nextPageToken
            } while (!pageToken.isNullOrBlank())

            RemoteFetch(
                events = events,
                presentRemoteIds = ids,
                collectionUrl = COLLECTION_URL,
                calendarName = null,
            )
        }

    override suspend fun insert(event: Event): String = withContext(Dispatchers.IO) {
        val text = executeAuthorized(
            Request.Builder()
                .url(writeUrl(EVENTS_PATH))
                .post(json.encodeToString(GoogleEventMapper.toWriteDto(event, zone())).toRequestBody(JSON))
                .build(),
            forceRefresh = false,
        )
        json.decodeFromString<GoogleEventDto>(text).id
            ?.takeIf { it.isNotBlank() }
            ?: throw GoogleCalendarUnavailableException("Google не вернул id созданного события")
    }

    override suspend fun update(remoteId: String, event: Event) = withContext(Dispatchers.IO) {
        executeAuthorized(
            Request.Builder()
                .url(writeUrl("$EVENTS_PATH/$remoteId"))
                .patch(json.encodeToString(GoogleEventMapper.toWriteDto(event, zone())).toRequestBody(JSON))
                .build(),
            forceRefresh = false,
        )
        Unit
    }

    override suspend fun delete(remoteId: String) = withContext(Dispatchers.IO) {
        executeAuthorized(
            Request.Builder().url(writeUrl("$EVENTS_PATH/$remoteId")).delete().build(),
            forceRefresh = false,
        )
        Unit
    }

    private fun eventsRequest(pageToken: String?): Request {
        val url = "$baseUrl/calendars/primary/events".toHttpUrl().newBuilder()
            .addQueryParameter("showDeleted", "true")
            .addQueryParameter("singleEvents", "false")
            .addQueryParameter("maxResults", "2500")
            .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
            .build()
        return Request.Builder().url(url).get().build()
    }

    private fun writeUrl(path: String) = "$baseUrl$path?sendUpdates=none".toHttpUrl()

    private suspend fun executeAuthorized(request: Request, forceRefresh: Boolean): String {
        val token = authRepository.getAccessToken(CalendarProvider.GOOGLE, forceRefresh)
            ?: throw GoogleCalendarUnavailableException(
                "Google Календарь не подключён — войдите в Настройках"
            )
        val authorized = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(authorized).execute().use { response ->
            if (response.code == 401 && !forceRefresh) {
                return executeAuthorized(request, forceRefresh = true)
            }
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GoogleCalendarUnavailableException(messageFor(response.code))
            }
            return text
        }
    }

    private fun messageFor(code: Int): String = when (code) {
        401 -> "Сессия Google истекла — войдите заново в Настройках"
        403 -> "Google отклонил запрос (403): проверьте доступ к календарю"
        429 -> "Google временно ограничил запросы, повторите позже"
        else -> "Google вернул HTTP $code"
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://www.googleapis.com/calendar/v3"
        const val COLLECTION_URL = "google:primary"
        const val EVENTS_PATH = "/calendars/primary/events"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
