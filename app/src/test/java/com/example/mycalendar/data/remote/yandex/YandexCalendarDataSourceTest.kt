package com.example.mycalendar.data.remote.yandex

import com.example.mycalendar.data.auth.AccessTokenProvider
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.UserAccount
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.CalendarConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Юнит-тест [YandexCalendarDataSource] — уровень оркестрации поверх [CalDavClient]
 * и [IcsMapper], который не покрыт тестами ниже:
 *  - [IcsMapperTest] — сериализация/парсинг iCalendar;
 *  - [CalDavClientIntegrationTest] — транспорт CalDAV (discovery/fetch/put/delete).
 *
 * Здесь проверяем именно логику источника данных:
 *  1. [YandexCalendarDataSource.insert] генерирует UUID и шлёт PUT `<календарь>/<uid>.ics`
 *     с корректным iCalendar-телом;
 *  2. [YandexCalendarDataSource.fetchUpdatedSince] прогоняет discovery + REPORT, отдаёт
 *     распарсенные [com.example.mycalendar.data.remote.RemoteEvent] и состав коллекции
 *     ([com.example.mycalendar.data.remote.RemoteFetch.presentRemoteIds]) — по нему движок
 *     распознаёт удаления; при неразобранном VEVENT состав не отдаётся вовсе;
 *  3. [YandexCalendarDataSource.credentials] бросает [YandexCalendarDataSource.YandexCalendarUnavailableException]
 *     когда нет токена И когда у аккаунта нет email (две ветки guard-а).
 *
 * Запускается на JVM: [AccessTokenProvider] и [AuthRepository] — фейки в памяти,
 * сеть идёт в [yandexMockServer] (без токена Яндекса и эмулятора).
 */
class YandexCalendarDataSourceTest {

    private val email = "user@example.com"
    private val token = "token123"

    private fun sampleEvent() = Event(
        id = "local-1",
        title = "Встреча с командой",
        description = "Обсудить спринт",
        startTime = 1_700_000_000_000L,
        endTime = 1_700_003_600_000L,
        reminderMinutes = listOf(10),
    )

    @Test
    fun `insert generates a UUID and PUTs iCalendar to calendar slash uid ics`() {
        val captured = mutableListOf<MiniCalDavServer.Request>()
        yandexMockServer(captured).use { server ->
            val ds = YandexCalendarDataSource(
                caldav = CalDavClient(OkHttpClient(), baseUrl = server.baseUrl),
                auth = FakeTokenProvider(token),
                authRepository = FakeAuthRepository(UserAccount(CalendarProvider.YANDEX, email)),
            )

            val uid = runBlocking { ds.insert(sampleEvent()) }

            // UID — валидный UUID (UUID.randomUUID)
            assertNotNull("insert должен вернуть remoteId", uid)
            UUID.fromString(uid) // бросит IllegalArgumentException, если не UUID

            // PUT ушёл ровно по <calendarUrl>/<uid>.ics с VEVENT и SUMMARY
            val put = captured.firstOrNull { it.method == "PUT" && it.path == "/dav/calendars/primary/$uid.ics" }
            assertNotNull("ожидался PUT $uid.ics", put)
            assertTrue("тело PUT должно содержать VEVENT", put!!.body.contains("VEVENT"))
            assertTrue("тело PUT должно содержать SUMMARY события", put.body.contains("SUMMARY:Встреча с командой"))

            // Все запросы несут корректный HTTP Basic (email:token)
            val expectedAuth = "Basic " + java.util.Base64.getEncoder().encodeToString("$email:$token".toByteArray())
            assertTrue("все запросы должны нести Basic-авторизацию", captured.all { it.auth == expectedAuth })
            // Был ровно один REPORT (fetch во время discovery не нужен, но PUT требует discovery)
            assertTrue("discovery должен пройти (PROPFIND principal/home/calendars)",
                captured.count { it.method == "PROPFIND" } >= 3)
        }
    }

    @Test
    fun `fetchUpdatedSince runs discovery and returns parsed remote events`() {
        val captured = mutableListOf<MiniCalDavServer.Request>()
        yandexMockServer(captured).use { server ->
            val ds = YandexCalendarDataSource(
                caldav = CalDavClient(OkHttpClient(), baseUrl = server.baseUrl),
                auth = FakeTokenProvider(token),
                authRepository = FakeAuthRepository(UserAccount(CalendarProvider.YANDEX, email)),
            )

            val fetch = runBlocking { ds.fetchUpdatedSince(0) }

            assertEquals(1, fetch.events.size)
            assertEquals("event1", fetch.events[0].remoteId)
            assertEquals("Тестовое событие", fetch.events[0].title)
            // Полная выборка обязана назвать состав коллекции: по нему вычисляются удаления.
            assertEquals(setOf("event1"), fetch.presentRemoteIds)
            assertTrue("должен быть отправлен REPORT calendar-query", captured.any { it.method == "REPORT" })
        }
    }

    @Test
    fun `unparsable VEVENT disables absence-based deletion detection`() {
        val broken = """BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:broken
SUMMARY:Без времени начала
END:VEVENT
END:VCALENDAR"""
        yandexMockServer(mutableListOf()) { base ->
            eventsXml(base, "event1" to VALID_ICS, "broken" to broken)
        }.use { server ->
            val ds = YandexCalendarDataSource(
                caldav = CalDavClient(OkHttpClient(), baseUrl = server.baseUrl),
                auth = FakeTokenProvider(token),
                authRepository = FakeAuthRepository(UserAccount(CalendarProvider.YANDEX, email)),
            )

            val fetch = runBlocking { ds.fetchUpdatedSince(0) }

            // Разобралось одно событие из двух — состав коллекции неизвестен, иначе
            // «broken» был бы принят за удалённый и вычищен локально.
            assertEquals(1, fetch.events.size)
            assertEquals("event1", fetch.events.single().remoteId)
            assertEquals(null, fetch.presentRemoteIds)
        }
    }

    @Test
    fun `credentials throws when access token is missing`() {
        val ds = YandexCalendarDataSource(
            caldav = CalDavClient(OkHttpClient(), baseUrl = "http://localhost:1"),
            auth = FakeTokenProvider(null),
            authRepository = FakeAuthRepository(UserAccount(CalendarProvider.YANDEX, email)),
        )

        val ex = runCatching { runBlocking { ds.fetchUpdatedSince(0) } }.exceptionOrNull()
        assertTrue(
            "ожидался YandexCalendarUnavailableException",
            ex is YandexCalendarDataSource.YandexCalendarUnavailableException,
        )
        assertTrue(
            "сообщение должно указывать на токен",
            ex!!.message.orEmpty().contains("токен", ignoreCase = true),
        )
    }

    @Test
    fun `credentials throws when account email is missing`() {
        val ds = YandexCalendarDataSource(
            caldav = CalDavClient(OkHttpClient(), baseUrl = "http://localhost:1"),
            auth = FakeTokenProvider(token),
            authRepository = FakeAuthRepository(null), // account == null -> email == null
        )

        val ex = runCatching { runBlocking { ds.fetchUpdatedSince(0) } }.exceptionOrNull()
        assertTrue(
            "ожидался YandexCalendarUnavailableException",
            ex is YandexCalendarDataSource.YandexCalendarUnavailableException,
        )
        assertTrue(
            "сообщение должно указывать на аккаунт",
            ex!!.message.orEmpty().contains("Аккаунт", ignoreCase = true),
        )
    }

    private companion object {
        /** VCALENDAR со всеми обязательными для [IcsMapper.fromIcs] полями. */
        const val VALID_ICS = """BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:event1
DTSTART:20231114T221320Z
DTEND:20231114T234000Z
SUMMARY:Тестовое событие
END:VEVENT
END:VCALENDAR"""
    }

    /** Фейковый [AccessTokenProvider]: токен из памяти, без Android Keystore. */
    private class FakeTokenProvider(private val token: String?) : AccessTokenProvider {
        override fun getAccessToken(): String? = token
    }

    /** Фейковый [AuthRepository]: реализуем только [accountFor], остальное — заглушки. */
    private class FakeAuthRepository(private val account: UserAccount?) : AuthRepository {
        override val connections: Flow<List<CalendarConnection>> get() = flowOf(emptyList())
        override suspend fun accountFor(provider: CalendarProvider): UserAccount? = account
        override suspend fun connectedProviders(): List<CalendarProvider> = emptyList()
        override suspend fun connectGoogleCalendar(): Result<UserAccount> = Result.failure(NotImplementedError())
        override suspend fun signInWithYandex(): Result<UserAccount> = Result.failure(NotImplementedError())
        override suspend fun signOut(provider: CalendarProvider) = Unit
        override suspend fun signOutAll() = Unit
        override suspend fun getAccessToken(provider: CalendarProvider, forceRefresh: Boolean): String? = null
    }
}
