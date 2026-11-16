package com.example.mycalendar.data.remote.google

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.UserAccount
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.CalendarConnection
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCalendarDataSourceTest {

    private val zone = ZoneId.of("Europe/Moscow")

    // ---------- pull ----------

    @Test
    fun `pull maps pages and reports the collection`() = runBlocking {
        FakeGoogleServer { request ->
            val pageToken = request.query?.substringAfter("pageToken=", "")
                ?.substringBefore('&')
                ?.takeIf { it.isNotBlank() }
            if (pageToken == null) {
                200 to """{"items":[
                    {"id":"a","summary":"Первое","start":{"dateTime":"2026-05-09T10:00:00+03:00"},"end":{"dateTime":"2026-05-09T11:00:00+03:00"}},
                    {"id":"b","status":"cancelled","summary":"Удалённое","start":{"date":"2026-05-09"},"end":{"date":"2026-05-10"}}
                ],"nextPageToken":"page-2"}"""
            } else {
                200 to """{"items":[
                    {"id":"c","summary":"Второе","start":{"dateTime":"2026-05-10T09:00:00+03:00"},"end":{"dateTime":"2026-05-10T10:00:00+03:00"}}
                ]}"""
            }
        }.use { server ->
            val fetch = source(server).fetchUpdatedSince(0L)

            assertEquals(setOf("a", "b", "c"), fetch.events.map { it.remoteId }.toSet())
            assertEquals(setOf("a", "b", "c"), fetch.presentRemoteIds)
            assertEquals("google:primary", fetch.collectionUrl)
            assertEquals(null, fetch.calendarName)
            assertTrue(fetch.events.first { it.remoteId == "b" }.deleted)
            val first = server.requests.first()
            assertEquals("GET", first.method)
            assertTrue(first.query.orEmpty().contains("showDeleted=true"))
            assertTrue(first.query.orEmpty().contains("singleEvents=false"))
            assertEquals("Bearer token-1", first.authorization)
        }
    }

    @Test
    fun `missing token asks to sign in`() = runBlocking {
        FakeGoogleServer { 200 to """{"items":[]}""" }.use { server ->
            val error = runCatching { source(server, token = null).fetchUpdatedSince(0L) }
                .exceptionOrNull()

            assertTrue(error is GoogleCalendarUnavailableException)
            assertTrue(error!!.message.orEmpty().contains("войдите", ignoreCase = true))
        }
    }

    @Test
    fun `401 forces one token refresh and retries`() = runBlocking {
        FakeGoogleServer { request ->
            if (request.authorization == "Bearer token-1") {
                401 to """{"error":{"code":401}}"""
            } else {
                200 to """{"items":[]}"""
            }
        }.use { server ->
            val auth = FakeAuthRepository("token-1", refreshed = "token-2")
            val fetch = GoogleCalendarDataSource(
                http = OkHttpClient(),
                authRepository = auth,
                baseUrl = server.baseUrl,
                zone = { zone },
                now = { 1L },
            ).fetchUpdatedSince(0L)

            assertTrue(fetch.events.isEmpty())
            assertEquals(listOf(false, true), auth.refreshRequests)
            assertEquals(listOf("Bearer token-1", "Bearer token-2"), server.requests.map { it.authorization })
        }
    }

    // ---------- push ----------

    @Test
    fun `insert posts the event and returns its id`() = runBlocking {
        FakeGoogleServer { 200 to """{"id":"new-1"}""" }.use { server ->
            val event = com.example.mycalendar.domain.model.Event(
                id = "local-1",
                title = "Событие",
                startTime = 1_000L,
                endTime = 2_000L,
                calendarProvider = CalendarProvider.LOCAL,
            )

            val remoteId = source(server).insert(event)

            assertEquals("new-1", remoteId)
            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertTrue(request.query.orEmpty().contains("sendUpdates=none"))
            assertTrue(request.body.contains("\"summary\":\"Событие\""))
        }
    }

    @Test
    fun `update patches and delete removes`() = runBlocking {
        FakeGoogleServer { 200 to "{}" }.use { server ->
            val event = com.example.mycalendar.domain.model.Event(
                id = "local-1",
                title = "Новое имя",
                startTime = 1_000L,
                endTime = 2_000L,
                calendarProvider = CalendarProvider.LOCAL,
            )
            val source = source(server)

            source.update("remote-1", event)
            source.delete("remote-1")

            assertEquals(listOf("PATCH", "DELETE"), server.requests.map { it.method })
            assertTrue(server.requests[0].path.endsWith("/events/remote-1"))
            assertTrue(server.requests[1].path.endsWith("/events/remote-1"))
        }
    }

    private fun source(server: FakeGoogleServer, token: String? = "token-1") =
        GoogleCalendarDataSource(
            http = OkHttpClient(),
            authRepository = FakeAuthRepository(token, refreshed = token),
            baseUrl = server.baseUrl,
            zone = { zone },
            now = { 1L },
        )

    /** Локальный HTTP-сервер: запоминает запросы и отвечает заданной парой (код, тело). */
    private class FakeGoogleServer(private val handler: (Recorded) -> Pair<Int, String>) : java.io.Closeable {

        data class Recorded(
            val method: String,
            val path: String,
            val query: String?,
            val authorization: String?,
            val body: String,
        )

        val requests = java.util.Collections.synchronizedList(mutableListOf<Recorded>())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/calendar/v3"

        init {
            server.createContext("/") { exchange ->
                val recorded = Recorded(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    query = exchange.requestURI.rawQuery,
                    authorization = exchange.requestHeaders.getFirst("Authorization"),
                    body = exchange.requestBody.readBytes().toString(Charsets.UTF_8),
                )
                requests += recorded
                val (code, body) = handler(recorded)
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
        }
    }

    /** Фейковый [AuthRepository]: важен только токен Google. */
    private class FakeAuthRepository(
        token: String?,
        private val refreshed: String?,
    ) : AuthRepository {

        private var token: String? = token
        val refreshRequests = mutableListOf<Boolean>()

        override val connections: Flow<List<CalendarConnection>> get() = flowOf(emptyList())
        override suspend fun accountFor(provider: CalendarProvider): UserAccount? = null
        override suspend fun connectedProviders(): List<CalendarProvider> = emptyList()
        override suspend fun connectGoogleCalendar(): Result<UserAccount> =
            Result.failure(NotImplementedError())
        override suspend fun signInWithYandex(): Result<UserAccount> =
            Result.failure(NotImplementedError())
        override suspend fun signOut(provider: CalendarProvider) = Unit
        override suspend fun signOutAll() = Unit

        override suspend fun getAccessToken(
            provider: CalendarProvider,
            forceRefresh: Boolean,
        ): String? {
            refreshRequests += forceRefresh
            if (forceRefresh) token = refreshed
            return token
        }
    }
}
