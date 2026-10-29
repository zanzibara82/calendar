package com.example.mycalendar.data.remote.yandex

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Интеграционный тест [CalDavClient] против локального mock-сервера, имитирующего
 * `caldav.yandex.ru`. Проверяет реальный транспортный путь, который юнит-тест
 * [IcsMapperTest] не покрывает: цепочку discovery (PROPFIND principal → home-set →
 * список календарей), разбор multistatus (207), REPORT calendar-query, PUT/DELETE.
 *
 * Запускается на JVM (app/src/test), поэтому не требует токена Яндекса и эмулятора.
 * Mock-сервер — [yandexMockServer] на чистом java.base (ServerSocket); XML-парсер
 * (org.xmlpull.v1) берётся из kxml2 (testImplementation).
 */
class CalDavClientIntegrationTest {

    @Test
    fun `full discovery, fetch, put and delete against a mock CalDAV server`() {
        val captured = mutableListOf<MiniCalDavServer.Request>()
        yandexMockServer(captured).use { server ->
            val base = server.baseUrl
            val client = CalDavClient(OkHttpClient(), baseUrl = base)

            // 1) Discovery: должен выбрать календарь с именем «Календарь»
            val cal = client.discoverAndPickCalendar(USER, TOKEN)
            assertEquals("Календарь", cal.displayName)
            assertEquals("$base/dav/calendars/primary/", cal.url)
            assertEquals("12345", cal.ctag)

            // 2) Fetch: REPORT должен вернуть сырой VCALENDAR с VEVENT
            val events = client.fetchEvents(cal.url, USER, TOKEN)
            assertEquals(1, events.size)
            assertTrue("ожидался VCALENDAR с VEVENT", events[0].contains("VEVENT"))
            assertTrue(events[0].contains("UID:event1"))

            // 3) Write + delete: не должны бросать исключений
            val uid = "generated-uid-1"
            val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:$uid\r\nSUMMARY:Hi\r\nEND:VEVENT\r\nEND:VCALENDAR"
            client.putEvent(cal.url, USER, TOKEN, uid, ics)
            client.deleteEvent(cal.url, USER, TOKEN, uid)

            // 4) Каждый запрос шёл с корректным HTTP Basic (email:token)
            val expectedAuth = "Basic " + Base64.getEncoder().encodeToString("$USER:$TOKEN".toByteArray())
            assertTrue("все запросы должны нести Basic-авторизацию", captured.all { it.auth == expectedAuth })

            // 5) PUT и DELETE ушли ровно по <url>/<uid>.ics
            assertTrue(captured.any { it.method == "PUT" && it.path == "/dav/calendars/primary/$uid.ics" })
            assertTrue(captured.any { it.method == "DELETE" && it.path == "/dav/calendars/primary/$uid.ics" })
            assertTrue("REPORT calendar-query должен быть отправлен", captured.any { it.method == "REPORT" })
        }
    }

    /**
     * caldav.yandex.ru отдаёт href в multistatus как путь (`/calendars/...`), а не
     * полный URL. Клиент обязан разрешить его от baseUrl, иначе OkHttp падает с
     * "Expected URL scheme 'http' or 'https'" ещё до запроса.
     */
    @Test
    fun `path-absolute hrefs from Yandex are resolved to absolute URLs`() {
        val captured = mutableListOf<MiniCalDavServer.Request>()
        yandexMockServer(captured, relativeHrefs = true).use { server ->
            val base = server.baseUrl
            val client = CalDavClient(OkHttpClient(), baseUrl = base)

            val cal = client.discoverAndPickCalendar(USER, TOKEN)
            assertEquals("$base/dav/calendars/primary/", cal.url)

            val events = client.fetchEvents(cal.url, USER, TOKEN)
            assertEquals(1, events.size)

            val uid = "uid-relative"
            val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:$uid\r\nEND:VEVENT\r\nEND:VCALENDAR"
            client.putEvent(cal.url, USER, TOKEN, uid, ics)
            client.deleteEvent(cal.url, USER, TOKEN, uid)
            assertTrue(captured.any { it.method == "PUT" && it.path == "/dav/calendars/primary/$uid.ics" })
            assertTrue(captured.any { it.method == "DELETE" && it.path == "/dav/calendars/primary/$uid.ics" })
        }
    }

    @Test
    fun `REPORT with a failed propstat aborts instead of returning a partial list`() {
        yandexMockServer(mutableListOf(), report = { base -> failedEventsXml(base, "HTTP/1.1 404 Not Found") }).use { server ->
            val client = CalDavClient(OkHttpClient(), baseUrl = server.baseUrl)
            val cal = client.discoverAndPickCalendar(USER, TOKEN)

            val ex = runCatching { client.fetchEvents(cal.url, USER, TOKEN) }.exceptionOrNull()

            // Неполный multistatus нельзя выдать за полную коллекцию: отсутствующие
            // в нём события движок синхронизации счёл бы удалёнными.
            assertTrue("ожидался CalDavException при ошибочном propstat", ex is CalDavClient.CalDavException)
            assertTrue(
                "сообщение должно указывать на неполный список",
                ex!!.message.orEmpty().contains("неполный", ignoreCase = true),
            )
        }
    }

    @Test
    fun `401 from server surfaces as a clear token error`() {
        MiniCalDavServer { MiniCalDavServer.Response(401, "") }.use { server ->
            val client = CalDavClient(OkHttpClient(), baseUrl = server.baseUrl)
            val ex = try {
                client.discoverAndPickCalendar(USER, TOKEN)
                null
            } catch (e: CalDavClient.CalDavException) {
                e
            }
            assertTrue("ожидалось CalDavException при 401", ex != null)
            assertTrue(ex!!.message.orEmpty().contains("токен", ignoreCase = true))
        }
    }

    private companion object {
        const val USER = "user@example.com"
        const val TOKEN = "token123"
    }
}
