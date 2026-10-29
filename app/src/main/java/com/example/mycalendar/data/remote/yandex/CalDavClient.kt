package com.example.mycalendar.data.remote.yandex

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Низкоуровневый клиент CalDAV для Яндекс.Календаря (RFC 4791).
 *
 * Сервер: [baseUrl] = `https://caldav.yandex.ru`.
 * Авторизация — HTTP Basic, где **логин = email аккаунта Яндекса**, а **пароль =
 * OAuth-токен** (scope `calendar:all`). У Яндекса нет публичного REST API для
 * Календаря — синхронизация идёт только поверх CalDAV.
 *
 * Discovery (цепочка PROPFIND):
 *   principal            -> calendar-home-set -> список календарей.
 * Чтение: REPORT calendar-query (все VEVENT коллекции).
 * Запись/удаление: PUT/DELETE `<коллекция>/<uid>.ics`.
 */
class CalDavClient(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://caldav.yandex.ru",
) {

    /** Ошибка уровня CalDAV (сеть/сервер/токен). Ловится вызывающим и превращается в SyncResult. */
    class CalDavException(message: String) : Exception(message)

    data class Calendar(val url: String, val displayName: String, val ctag: String?)

    private val xmlType = "application/xml; charset=utf-8".toMediaType()
    private val icsType = "text/calendar; charset=utf-8".toMediaType()

    private val principalBody = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><d:current-user-principal/></d:prop></d:propfind>"""

    private val homeSetBody = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-home-set/></d:prop></d:propfind>"""

    private val calendarsBody = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><d:resourcetype/><d:displayname/><c:getctag/></d:prop></d:propfind>"""

    private val eventsBody = """<?xml version="1.0" encoding="utf-8"?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><d:getetag/><c:calendar-data/></d:prop></c:calendar-query>"""

    private fun authHeader(user: String, token: String): String {
        val cred = "$user:$token".toByteArray(StandardCharsets.UTF_8)
        return "Basic " + Base64.getEncoder().encodeToString(cred)
    }

    private fun resolve(relative: String): String = URL(baseUrl).let { URL(it, relative) }.toString()

    /** Discovery: principal -> calendar-home-set -> первый подходящий календарь. */
    fun discoverAndPickCalendar(user: String, token: String): Calendar {
        val principal = parseMultiStatus(
            propfind(baseUrl, user, token, depth = "0", body = principalBody)
        ).firstOrNull()?.href
            ?: throw CalDavException("Не удалось найти principal (токен Яндекса недействителен?)")

        val home = parseMultiStatus(
            propfind(resolve(principal), user, token, depth = "0", body = homeSetBody)
        ).firstOrNull()?.href
            ?: throw CalDavException("Не удалось найти calendar-home-set")

        val calendars = parseMultiStatus(
            propfind(resolve(home), user, token, depth = "1", body = calendarsBody)
        ).filter { it.isCalendar }
            // href в multistatus — путь (`/calendars/...`), а не URL: без resolve
            // окно получило бы «Expected URL scheme» вместо запроса (проверено на
            // настоящем caldav.yandex.ru).
            .map {
                Calendar(
                    url = resolve(it.href ?: throw CalDavException("Календарь без URL")),
                    displayName = it.displayName,
                    ctag = it.ctag,
                )
            }

        if (calendars.isEmpty()) {
            throw CalDavException("У аккаунта Яндекса не найдено ни одного календаря")
        }
        // Предпочитаем календарь с именем «Календарь»/default, иначе первый попавшийся.
        return calendars.firstOrNull { it.displayName.contains("Календарь", ignoreCase = true) }
            ?: calendars.first()
    }

    /**
     * Получить все VEVENT коллекции календаря в виде сырых текстов VCALENDAR.
     *
     * Ошибка в любом `<response>` обрывает чтение: движок синхронизации считает события,
     * отсутствующие в полученной выборке, удалёнными в облаке. Выдать частичный ответ
     * за полный — значит молча удалить у пользователя то, что просто не прочиталось.
     */
    fun fetchEvents(calendarUrl: String, user: String, token: String): List<String> {
        val responses = parseMultiStatus(report(calendarUrl, user, token, depth = "1", body = eventsBody))
        responses.firstOrNull { it.failedStatus != null }?.let {
            throw CalDavException("CalDAV вернул неполный список событий: ${it.failedStatus}")
        }
        return responses.mapNotNull { it.calendarData }.filter { it.contains("VEVENT") }
    }

    fun putEvent(calendarUrl: String, user: String, token: String, uid: String, ics: String) {
        val url = URL(calendarUrl).let { URL(it, "$uid.ics") }.toString()
        call("PUT", url, user, token, ics, icsType)
    }

    fun deleteEvent(calendarUrl: String, user: String, token: String, uid: String) {
        val url = URL(calendarUrl).let { URL(it, "$uid.ics") }.toString()
        call("DELETE", url, user, token, null, null)
    }

    // ---------- HTTP ----------

    private fun propfind(url: String, user: String, token: String, depth: String, body: String): String {
        val req = Request.Builder().url(url)
            .addHeader("Authorization", authHeader(user, token))
            .addHeader("Depth", depth)
            .method("PROPFIND", body.toRequestBody(xmlType))
            .build()
        return execute(req)
    }

    /** REPORT calendar-query — единственный по RFC 4791 способ отфильтровать VEVENT. */
    private fun report(url: String, user: String, token: String, depth: String, body: String): String {
        val req = Request.Builder().url(url)
            .addHeader("Authorization", authHeader(user, token))
            .addHeader("Depth", depth)
            .method("REPORT", body.toRequestBody(xmlType))
            .build()
        return execute(req)
    }

    private fun call(
        method: String,
        url: String,
        user: String,
        token: String,
        body: String?,
        type: okhttp3.MediaType?,
    ) {
        val req = Request.Builder().url(url)
            .addHeader("Authorization", authHeader(user, token))
            .method(method, if (body != null && type != null) body.toRequestBody(type) else null)
            .build()
        execute(req)
    }

    private fun execute(req: Request): String {
        http.newCall(req).execute().use { resp ->
            val code = resp.code
            val bodyText = resp.body?.string().orEmpty()
            return when {
                code in 200..299 || code == 207 -> bodyText
                code == 401 -> throw CalDavException("Токен Яндекса недействителен или истёк — войдите заново")
                else -> throw CalDavException("CalDAV-ошибка ${req.method} ${req.url} : HTTP $code")
            }
        }
    }

    // ---------- XML-парсинг ответов (207 Multi-Status) ----------

    /**
     * Разбор `<multistatus>` в список ответов. Используется для ВСЕХ ответов:
     *  - principal/home — берём `.href` (последний href в ответе = искомый);
     *  - список календарей — фильтруем по [Response.isCalendar];
     *  - события — берём `.calendarData`.
     *
     * Статус запоминается, чтобы [fetchEvents] мог отличить полную выборку от частичной:
     * ошибкой считается не-2xx статус самого `<response>` либо `<propstat>`, в котором
     * пришли данные. Не-2xx propstat без данных (запрошенное свойство отсутствует) —
     * штатный ответ и ошибкой не считается.
     */
    private fun parseMultiStatus(xml: String): List<Response> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val responses = mutableListOf<Response>()
        var cur: Response? = null
        var inResourcetype = false
        var inPropstat = false
        var propstatStatus: String? = null
        var propstatHasData = false
        var pendingTextTag: String? = null
        var pendingText: String? = null

        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (val ln = localName(parser.name)) {
                        "response" -> {
                            cur = Response()
                            pendingTextTag = null
                            pendingText = null
                        }
                        "propstat" -> {
                            inPropstat = true
                            propstatStatus = null
                            propstatHasData = false
                        }
                        "resourcetype" -> inResourcetype = true
                        "calendar" -> if (inResourcetype) cur?.isCalendar = true
                        "href" -> pendingTextTag = "href"
                        "displayname" -> pendingTextTag = "displayname"
                        "getctag" -> pendingTextTag = "getctag"
                        "calendar-data" -> pendingTextTag = "calendar-data"
                        "status" -> pendingTextTag = "status"
                    }
                }
                XmlPullParser.TEXT -> {
                    pendingText = parser.text
                }
                XmlPullParser.END_TAG -> {
                    when (val ln = localName(parser.name)) {
                        "resourcetype" -> inResourcetype = false
                        "href" -> if (pendingTextTag == "href") {
                            cur?.href = pendingText?.trim(); pendingTextTag = null; pendingText = null
                        }
                        "displayname" -> if (pendingTextTag == "displayname") {
                            cur?.displayName = pendingText?.trim().orEmpty(); pendingTextTag = null; pendingText = null
                        }
                        "getctag" -> if (pendingTextTag == "getctag") {
                            cur?.ctag = pendingText?.trim(); pendingTextTag = null; pendingText = null
                        }
                        "calendar-data" -> if (pendingTextTag == "calendar-data") {
                            cur?.calendarData = pendingText
                            if (inPropstat) propstatHasData = true
                            pendingTextTag = null; pendingText = null
                        }
                        "status" -> if (pendingTextTag == "status") {
                            val status = pendingText?.trim()
                            // Внутри propstat статус относится к его свойствам, на уровне
                            // response — ко всему ответу (например, 404 на исчезнувший ресурс).
                            if (inPropstat) {
                                propstatStatus = status
                            } else if (status != null && !statusCodeIsOk(status)) {
                                cur?.failedStatus = status
                            }
                            pendingTextTag = null; pendingText = null
                        }
                        "propstat" -> {
                            val status = propstatStatus
                            if (propstatHasData && status != null && !statusCodeIsOk(status)) {
                                cur?.failedStatus = status
                            }
                            inPropstat = false
                            propstatStatus = null
                            propstatHasData = false
                        }
                        "response" -> {
                            cur?.let { responses += it }
                            cur = null
                        }
                    }
                }
            }
            event = parser.next()
        }
        return responses
    }

    private fun localName(name: String?): String = name?.substringAfter(':') ?: ""

    /** "HTTP/1.1 200 OK" -> true; неразборчивый статус считаем ошибкой. */
    private fun statusCodeIsOk(status: String): Boolean {
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: return false
        return code in 200..299
    }

    /** Один <response> из multistatus. */
    private data class Response(
        var href: String? = null,
        var isCalendar: Boolean = false,
        var displayName: String = "",
        var ctag: String? = null,
        var calendarData: String? = null,
        /** Не-2xx статус ответа или его данных: выборка событий неполна. */
        var failedStatus: String? = null,
    )
}
