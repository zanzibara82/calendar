package com.example.mycalendar.data.remote.yandex

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Самодостаточный mock CalDAV-сервер на чистом java.base (ServerSocket),
 * пригодный для JVM-тестов (без com.sun.net.httpserver, которого нет в classpath
 * тестов AGP). Один HTTP-запрос на соединение, ответ с `Connection: close`,
 * поэтому OkHttp не переиспользует соединение.
 *
 * Используется в [CalDavClientIntegrationTest] и [YandexCalendarDataSourceTest],
 * чтобы проверять реальный сетевой путь (discovery/fetch/put/delete) без токена
 * Яндекса и без эмулятора. XML-парсер (org.xmlpull.v1) берётся из kxml2
 * (testImplementation).
 */
class MiniCalDavServer(private val onRequest: (Request) -> Response) : AutoCloseable {

    data class Request(val method: String, val path: String, val auth: String?, val body: String)
    data class Response(val status: Int, val body: String)

    private val ss = ServerSocket(0)
    val port: Int get() = ss.localPort
    val baseUrl: String get() = "http://localhost:$port"

    private val thread = Thread {
        while (!ss.isClosed) {
            val sock = runCatching { ss.accept() }.getOrNull() ?: break
            runCatching { serve(sock) }
            runCatching { sock.close() }
        }
    }.also { it.isDaemon = true; it.start() }

    private fun serve(sock: Socket) {
        val raw = sock.getInputStream()
        val buf = ByteArray(8192)
        val headerBaos = ByteArrayOutputStream()
        val crlfcrlf = byteArrayOf(13, 10, 13, 10)
        while (true) {
            val n = raw.read(buf)
            if (n < 0) return
            headerBaos.write(buf, 0, n)
            val arr = headerBaos.toByteArray()
            val idx = arr.indexOfSequence(crlfcrlf)
            if (idx < 0) continue
            val headerText = String(arr, 0, idx, StandardCharsets.UTF_8)
            val len = headerText.lines().firstNotNullOfOrNull { l ->
                if (l.startsWith("Content-Length", ignoreCase = true)) l.substringAfter(':').trim().toIntOrNull() else null
            } ?: 0
            val bodyBaos = ByteArrayOutputStream()
            val bodyStart = idx + 4
            if (arr.size > bodyStart) bodyBaos.write(arr, bodyStart, arr.size - bodyStart)
            while (bodyBaos.size() < len) {
                val m = raw.read(buf)
                if (m < 0) break
                bodyBaos.write(buf, 0, m)
            }
            val bodyBytes = if (len > 0) {
                bodyBaos.toByteArray().copyOfRange(0, len.coerceAtMost(bodyBaos.size()))
            } else ByteArray(0)
            val body = String(bodyBytes, StandardCharsets.UTF_8)

            val first = headerText.lines().firstOrNull()?.split(" ") ?: listOf("", "")
            val method = first.getOrNull(0) ?: ""
            val path = first.getOrNull(1) ?: ""
            val auth = headerText.lines().firstNotNullOfOrNull { l ->
                if (l.startsWith("Authorization", ignoreCase = true)) l.substringAfter(':').trim() else null
            }
            writeResp(sock, onRequest(Request(method, path, auth, body)))
            return
        }
    }

    private fun writeResp(sock: Socket, resp: Response) {
        val out = sock.getOutputStream()
        val bodyBytes = resp.body.toByteArray(StandardCharsets.UTF_8)
        out.write("HTTP/1.1 ${resp.status} OK\r\n".toByteArray())
        out.write("Content-Length: ${bodyBytes.size}\r\n".toByteArray())
        out.write("Connection: close\r\n".toByteArray())
        out.write("\r\n".toByteArray())
        out.write(bodyBytes)
        out.flush()
    }

    override fun close() = ss.close()
}

/** Поиск подпоследовательности байт (аналог String.indexOf) для разбора заголовков. */
private fun ByteArray.indexOfSequence(seq: ByteArray, from: Int = 0): Int {
    if (seq.isEmpty() || this.size < seq.size) return -1
    outer@ for (i in from..(this.size - seq.size)) {
        for (j in seq.indices) {
            if (this[i + j] != seq[j]) continue@outer
        }
        return i
    }
    return -1
}

// ---------- Mock-ответы CalDAV ----------
// Делятся между CalDavClientIntegrationTest и YandexCalendarDataSourceTest.
// По RFC 4918 href в multistatus — это путь (path-absolute), а не полный URL,
// и настоящий caldav.yandex.ru отдаёт именно `/calendars/...`. Параметр
// [relativeHrefs] переключает ответы на этот вид, чтобы тесты не маскировали
// отсутствие resolve в клиенте.

/** Префикс href: "" (path-absolute) или "<base>" (абсолютный URL). */
private fun hrefPrefix(base: String, relativeHrefs: Boolean): String =
    if (relativeHrefs) "" else base

internal fun principalXml(base: String, relativeHrefs: Boolean = false): String {
    val p = hrefPrefix(base, relativeHrefs)
    return """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:response>
    <d:href>/</d:href>
    <d:propstat>
      <d:prop>
        <d:current-user-principal><d:href>$p/dav/principals/me/</d:href></d:current-user-principal>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"""
}

internal fun homeSetXml(base: String, relativeHrefs: Boolean = false): String {
    val p = hrefPrefix(base, relativeHrefs)
    return """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:response>
    <d:href>$p/dav/principals/me/</d:href>
    <d:propstat>
      <d:prop>
        <c:calendar-home-set><d:href>$p/dav/calendars/</d:href></c:calendar-home-set>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"""
}

internal fun calendarsXml(base: String, relativeHrefs: Boolean = false): String {
    val p = hrefPrefix(base, relativeHrefs)
    return """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:response>
    <d:href>$p/dav/calendars/primary/</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype><c:calendar/><d:collection/></d:resourcetype>
        <d:displayname>Календарь</d:displayname>
        <c:getctag>12345</c:getctag>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>$p/dav/calendars/work/</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype><c:calendar/><d:collection/></d:resourcetype>
        <d:displayname>Work</d:displayname>
        <c:getctag>6789</c:getctag>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"""
}

private const val EVENT1_ICS = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//MyCalendar//EN
BEGIN:VEVENT
UID:event1
DTSTART:20231114T221320Z
DTEND:20231114T234000Z
SUMMARY:Тестовое событие
END:VEVENT
END:VCALENDAR"""

/** REPORT-ответ из произвольных пар uid -> VCALENDAR (по умолчанию — одно [EVENT1_ICS]). */
internal fun eventsXml(
    base: String,
    vararg entries: Pair<String, String>,
    relativeHrefs: Boolean = false,
): String = buildString {
    val p = hrefPrefix(base, relativeHrefs)
    val items = if (entries.isEmpty()) listOf("event1" to EVENT1_ICS) else entries.toList()
    append("""<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
""")
    for ((uid, ics) in items) {
        append("""  <d:response>
    <d:href>$p/dav/calendars/primary/$uid.ics</d:href>
    <d:propstat>
      <d:prop>
        <d:getetag>"$uid"</d:getetag>
        <c:calendar-data>$ics</c:calendar-data>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
""")
    }
    append("</d:multistatus>")
}

/** REPORT-ответ, где данные события пришли с ошибочным статусом propstat. */
internal fun failedEventsXml(base: String, status: String, relativeHrefs: Boolean = false): String {
    val p = hrefPrefix(base, relativeHrefs)
    return """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:response>
    <d:href>$p/dav/calendars/primary/event1.ics</d:href>
    <d:propstat>
      <d:prop>
        <c:calendar-data>BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:event1
DTSTART:20231114T221320Z
DTEND:20231114T234000Z
SUMMARY:Тестовое событие
END:VEVENT
END:VCALENDAR</c:calendar-data>
      </d:prop>
      <d:status>$status</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"""
}

/**
 * Готовый mock Яндекс.Календаря: цепочка discovery (PROPFIND principal → home-set →
 * список календарей), REPORT calendar-query (отдаёт [eventsXml]), PUT/DELETE по
 * `<calendar>/<uid>.ics`. Все пришедшие запросы добавляются в [captured].
 *
 * [relativeHrefs] = true повторяет настоящее поведение caldav.yandex.ru: href в
 * multistatus — это путь (`/dav/...`), а не полный URL.
 */
internal fun yandexMockServer(
    captured: MutableList<MiniCalDavServer.Request>,
    relativeHrefs: Boolean = false,
    report: (String) -> String = { base -> eventsXml(base, relativeHrefs = relativeHrefs) },
): MiniCalDavServer {
    lateinit var base: String
    val server = MiniCalDavServer { req ->
        captured += req
        when {
            req.path == "/dav/principals/me/" && req.method == "PROPFIND" -> MiniCalDavServer.Response(207, homeSetXml(base, relativeHrefs))
            req.path == "/dav/calendars/" && req.method == "PROPFIND" -> MiniCalDavServer.Response(207, calendarsXml(base, relativeHrefs))
            req.path == "/dav/calendars/primary/" && req.method == "REPORT" -> MiniCalDavServer.Response(207, report(base))
            req.path.startsWith("/dav/calendars/primary/") && req.path.endsWith(".ics") && req.method == "PUT" -> MiniCalDavServer.Response(201, "")
            req.path.startsWith("/dav/calendars/primary/") && req.path.endsWith(".ics") && req.method == "DELETE" -> MiniCalDavServer.Response(204, "")
            req.path == "/" && req.method == "PROPFIND" -> MiniCalDavServer.Response(207, principalXml(base, relativeHrefs))
            else -> MiniCalDavServer.Response(404, "")
        }
    }
    base = server.baseUrl
    return server
}
