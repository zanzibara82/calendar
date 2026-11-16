# Google-синхронизация Windows-клиента — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Включить Google Календарь в Windows-клиенте: вход через браузер (loopback + PKCE), хранение токенов под DPAPI, двусторонняя синхронизация через Google Calendar API v3 на существующем движке.

**Architecture:** Новый `GoogleCalendarDataSource` в `:shared` реализует уже существующий `RemoteCalendarDataSource`, поэтому `SyncRepositoryImpl` не меняется. Вход вынесен в `DesktopGoogleLogin` (локальный HTTP-сервер на 127.0.0.1 + PKCE S256), токены — в `DesktopGoogleTokens` поверх `TokenVault` в отдельном файле `google-token.bin`. `DesktopAuthRepository` получает второй провайдер и второй authorize-колбэк; карточка Google в настройках — над карточкой Яндекса.

**Tech Stack:** Kotlin, Compose Desktop (CMP 1.12.2), OkHttp 5.5.1, kotlinx.serialization, JDK `com.sun.net.httpserver.HttpServer` в тестах, JUnit4, JNA Crypt32 (DPAPI уже есть в `DpapiTokenCipher`).

**Spec:** `docs/superpowers/specs/2026-11-16-google-sync-windows-design.md`

## Global Constraints

- Всё по-русски: сообщения, KDoc, тексты ошибок, коммиты.
- НИКОГДА не менять git config. Коммиты — локальные, без push. Без force-push, без переписывания истории.
- Никаких секретов и токенов в коде, тестах, документах, логах и чате. `client_id`/`client_secret` читаются только из `local.properties` (вне git). В тестах — заведомо фальшивые значения.
- Никогда не коммитить временный `ScreenshotSeedTest` и файлы из `%TEMP%`.
- TDD: сначала падающий тест, затем минимальная реализация, затем зелёный прогон.
- Два Gradle параллельно не запускать. Все команды — последовательно.
- Не трогать `:app` (Android) сверх KDoc в `:shared`; connected-тесты Android не запускать.
- `:desktopApp` собирается и тестируется JVM-тестами: `./gradlew :desktopApp:test`.
- Не добавлять системные зависимости молча.
- Сообщения об ошибках не должны содержать ответ Google целиком: только HTTP-код и короткий русский текст.
- Итоговые счётчики тестов брать только из `TEST-*.xml` (корень `<testsuite>`), не из консоли.

---

### Task 1: RruleCodec — общий кодек RRULE

**Files:**
- Create: `shared/src/main/kotlin/com/example/mycalendar/domain/util/RruleCodec.kt`
- Create: `shared/src/test/kotlin/com/example/mycalendar/domain/util/RruleCodecTest.kt`
- Modify: `shared/src/main/kotlin/com/example/mycalendar/data/remote/yandex/IcsMapper.kt:170-190`

**Interfaces:**
- Produces: `object RruleCodec { fun toRrule(rule: RepeatRule?): String?; fun fromRrule(rrule: String?): RepeatRule? }` — строки без префикса `RRULE:`, например `"FREQ=WEEKLY"`.

- [ ] **Step 1: Написать падающий тест**

`shared/src/test/kotlin/com/example/mycalendar/domain/util/RruleCodecTest.kt`:

```kotlin
package com.example.mycalendar.domain.util

import com.example.mycalendar.domain.model.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RruleCodecTest {

    @Test
    fun `rule maps to rrule`() {
        assertEquals("FREQ=DAILY", RruleCodec.toRrule(RepeatRule.DAILY))
        assertEquals("FREQ=WEEKLY", RruleCodec.toRrule(RepeatRule.WEEKLY))
        assertEquals("FREQ=MONTHLY", RruleCodec.toRrule(RepeatRule.MONTHLY))
        assertEquals("FREQ=YEARLY", RruleCodec.toRrule(RepeatRule.YEARLY))
        assertNull(RruleCodec.toRrule(null))
    }

    @Test
    fun `rrule maps to rule and ignores other parts`() {
        assertEquals(RepeatRule.DAILY, RruleCodec.fromRrule("FREQ=DAILY"))
        assertEquals(RepeatRule.WEEKLY, RruleCodec.fromRrule("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO"))
        assertEquals(RepeatRule.MONTHLY, RruleCodec.fromRrule("FREQ=MONTHLY;COUNT=5"))
        assertEquals(RepeatRule.YEARLY, RruleCodec.fromRrule("FREQ=YEARLY"))
    }

    @Test
    fun `unknown and empty rules give null`() {
        assertNull(RruleCodec.fromRrule("FREQ=HOURLY"))
        assertNull(RruleCodec.fromRrule(""))
        assertNull(RruleCodec.fromRrule(null))
    }
}
```

- [ ] **Step 2: Прогнать тест — ожидается компиляционная ошибка**

Run: `./gradlew :shared:test --tests "com.example.mycalendar.domain.util.RruleCodecTest" --console=plain`
Expected: FAIL — `Unresolved reference: RruleCodec`.

- [ ] **Step 3: Реализовать кодек**

`shared/src/main/kotlin/com/example/mycalendar/domain/util/RruleCodec.kt`:

```kotlin
package com.example.mycalendar.domain.util

import com.example.mycalendar.domain.model.RepeatRule

/**
 * Кодирование простых правил повтора в RRULE и обратно.
 *
 * Домен моделирует только частоту: INTERVAL, COUNT/UNTIL и BYDAY не поддерживаются
 * и при разборе игнорируются — так же, как в CalDAV-источнике.
 */
object RruleCodec {

    fun toRrule(rule: RepeatRule?): String? = when (rule) {
        RepeatRule.DAILY -> "FREQ=DAILY"
        RepeatRule.WEEKLY -> "FREQ=WEEKLY"
        RepeatRule.MONTHLY -> "FREQ=MONTHLY"
        RepeatRule.YEARLY -> "FREQ=YEARLY"
        null -> null
    }

    fun fromRrule(rrule: String?): RepeatRule? {
        if (rrule == null) return null
        val freq = rrule.substringAfter("FREQ=", "")
            .substringBefore(';')
            .substringBefore(':')
            .trim()
            .uppercase()
        return runCatching { RepeatRule.valueOf(freq) }.getOrNull()
    }
}
```

- [ ] **Step 4: Перевести IcsMapper на кодек**

В `IcsMapper.kt` удалить приватные `repeatToRrule` (строки 170–176) и `rruleToRepeat` (178–190).

Замены в файле:

```kotlin
repeatToRrule(event.repeatRule)?.let { lines += "RRULE:$it" }
```
→
```kotlin
RruleCodec.toRrule(event.repeatRule)?.let { lines += "RRULE:$it" }
```

```kotlin
repeatRule = rruleToRepeat(rrule),
```
→
```kotlin
repeatRule = RruleCodec.fromRrule(rrule),
```

Добавить импорт `import com.example.mycalendar.domain.util.RruleCodec` рядом с импортом `RepeatRule`. Если `RepeatRule` больше не используется в файле — удалить и его импорт.

- [ ] **Step 5: Прогнать тесты `:shared` — всё зелёное**

Run: `./gradlew :shared:test --console=plain`
Expected: PASS, включая существующий `IcsMapperTest`.

- [ ] **Step 6: Коммит**

```bash
git add shared/src/main/kotlin/com/example/mycalendar/domain/util/RruleCodec.kt \
        shared/src/test/kotlin/com/example/mycalendar/domain/util/RruleCodecTest.kt \
        shared/src/main/kotlin/com/example/mycalendar/data/remote/yandex/IcsMapper.kt
git commit -m "feat(shared): общий кодек RRULE для CalDAV и Google"
```

---

### Task 2: GoogleEventMapper — DTO и отображение

**Files:**
- Create: `shared/src/main/kotlin/com/example/mycalendar/data/remote/google/GoogleEventMapper.kt`
- Create: `shared/src/test/kotlin/com/example/mycalendar/data/remote/google/GoogleEventMapperTest.kt`

**Interfaces:**
- Consumes: `RruleCodec.toRrule/fromRrule`, `RemoteEvent`, `Event`.
- Produces:
  - `fun GoogleEventMapper.fromDto(dto: GoogleEventDto, zone: ZoneId, observedAt: Long): RemoteEvent?`
  - `fun GoogleEventMapper.toWriteDto(event: Event, zone: ZoneId): GoogleEventWriteDto`
  - DTO чтения: `GoogleEventDto(id, status, summary, description, start, end, recurrence, reminders, updated)`, `GoogleDateTimeDto(dateTime, date, timeZone)`, `GoogleRemindersDto(useDefault, overrides)`, `GoogleReminderDto(method, minutes)`, `GoogleEventsPageDto(items, nextPageToken)`.
  - DTO записи: `GoogleEventWriteDto(summary, description, start: GoogleDateTimeWriteDto, end, recurrence, reminders)`, `GoogleDateTimeWriteDto(dateTime, timeZone)` — без `id`/`status`, чтобы PATCH не отправлял чужие поля.

- [ ] **Step 1: Написать падающий тест**

`shared/src/test/kotlin/com/example/mycalendar/data/remote/google/GoogleEventMapperTest.kt`:

```kotlin
package com.example.mycalendar.data.remote.google

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleEventMapperTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private fun timedDto() = GoogleEventDto(
        id = "event-1",
        status = "confirmed",
        summary = "Созвон",
        description = "Обсудить план",
        start = GoogleDateTimeDto(dateTime = "2026-05-09T10:00:00+03:00"),
        end = GoogleDateTimeDto(dateTime = "2026-05-09T11:30:00+03:00"),
        recurrence = listOf("RRULE:FREQ=WEEKLY;INTERVAL=2"),
        reminders = GoogleRemindersDto(
            useDefault = false,
            overrides = listOf(
                GoogleReminderDto(method = "popup", minutes = 30),
                GoogleReminderDto(method = "email", minutes = 10),
                GoogleReminderDto(method = "popup", minutes = 10),
            ),
        ),
        updated = "2026-05-08T21:00:00.000Z",
    )

    @Test
    fun `timed event maps start end reminders and rule`() {
        val mapped = GoogleEventMapper.fromDto(timedDto(), zone, observedAt = 1L)!!

        assertEquals("event-1", mapped.remoteId)
        assertEquals("Созвон", mapped.title)
        assertEquals("Обсудить план", mapped.description)
        val start = Instant.ofEpochMilli(mapped.startTime).atZone(zone).toLocalDateTime()
        val end = Instant.ofEpochMilli(mapped.endTime).atZone(zone).toLocalDateTime()
        assertEquals(LocalDateTime.of(2026, 5, 9, 10, 0), start)
        assertEquals(LocalDateTime.of(2026, 5, 9, 11, 30), end)
        assertEquals(listOf(10, 30), mapped.reminderMinutes)
        assertEquals(RepeatRule.WEEKLY, mapped.repeatRule)
        assertEquals(Instant.parse("2026-05-08T21:00:00Z").toEpochMilli(), mapped.updatedAt)
        assertFalse(mapped.deleted)
    }

    @Test
    fun `all-day event covers the local day`() {
        val dto = GoogleEventDto(
            id = "event-2",
            summary = "Отпуск",
            start = GoogleDateTimeDto(date = "2026-05-09"),
            end = GoogleDateTimeDto(date = "2026-05-10"),
        )

        val mapped = GoogleEventMapper.fromDto(dto, zone, observedAt = 1L)!!
        val start = Instant.ofEpochMilli(mapped.startTime).atZone(zone)
        val end = Instant.ofEpochMilli(mapped.endTime).atZone(zone)

        assertEquals(LocalDate.of(2026, 5, 9), start.toLocalDate())
        assertEquals(0, start.hour)
        assertEquals(LocalDate.of(2026, 5, 9), end.toLocalDate())
        assertEquals(23, end.hour)
        assertEquals(59, end.minute)
        assertEquals(59, end.second)
        assertEquals(999_000_000, end.nano)
    }

    @Test
    fun `cancelled event is a tombstone`() {
        val dto = GoogleEventDto(
            id = "event-3",
            status = "cancelled",
            start = GoogleDateTimeDto(dateTime = "2026-05-09T10:00:00+03:00"),
        )

        assertTrue(GoogleEventMapper.fromDto(dto, zone, observedAt = 1L)!!.deleted)
    }

    @Test
    fun `event without id is skipped`() {
        assertNull(GoogleEventMapper.fromDto(GoogleEventDto(summary = "Без id"), zone, 1L))
    }

    @Test
    fun `write dto carries time, rule and popup reminders`() {
        val event = Event(
            id = "local-1",
            title = "Планёрка",
            description = null,
            startTime = OffsetDateTime.parse("2026-05-09T10:00:00+03:00").toInstant().toEpochMilli(),
            endTime = OffsetDateTime.parse("2026-05-09T11:00:00+03:00").toInstant().toEpochMilli(),
            calendarProvider = CalendarProvider.LOCAL,
            reminderMinutes = listOf(15, 15, 60),
            repeatRule = RepeatRule.WEEKLY,
        )

        val dto = GoogleEventMapper.toWriteDto(event, zone)

        assertEquals("Планёрка", dto.summary)
        assertNull(dto.description)
        assertEquals(
            Instant.ofEpochMilli(event.startTime),
            OffsetDateTime.parse(dto.start.dateTime).toInstant(),
        )
        assertEquals(listOf("RRULE:FREQ=WEEKLY"), dto.recurrence)
        assertEquals(false, dto.reminders.useDefault)
        assertEquals(listOf(15, 60), dto.reminders.overrides!!.map { it.minutes })
        assertTrue(dto.reminders.overrides!!.all { it.method == "popup" })
    }
}
```

- [ ] **Step 2: Прогнать — ожидается ошибка компиляции**

Run: `./gradlew :shared:test --tests "com.example.mycalendar.data.remote.google.GoogleEventMapperTest" --console=plain`
Expected: FAIL — unresolved references на DTO и `GoogleEventMapper`.

- [ ] **Step 3: Реализовать DTO и маппер**

`shared/src/main/kotlin/com/example/mycalendar/data/remote/google/GoogleEventMapper.kt`:

```kotlin
package com.example.mycalendar.data.remote.google

import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.util.RruleCodec
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
data class GoogleEventDto(
    val id: String? = null,
    val status: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val start: GoogleDateTimeDto? = null,
    val end: GoogleDateTimeDto? = null,
    val recurrence: List<String>? = null,
    val reminders: GoogleRemindersDto? = null,
    val updated: String? = null,
)

@Serializable
data class GoogleDateTimeDto(
    val dateTime: String? = null,
    val date: String? = null,
    val timeZone: String? = null,
)

@Serializable
data class GoogleRemindersDto(
    val useDefault: Boolean? = null,
    val overrides: List<GoogleReminderDto>? = null,
)

@Serializable
data class GoogleReminderDto(
    val method: String? = null,
    val minutes: Int? = null,
)

@Serializable
data class GoogleEventsPageDto(
    val items: List<GoogleEventDto> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class GoogleDateTimeWriteDto(
    val dateTime: String,
    val timeZone: String,
)

/**
 * Тело записи: только редактируемые поля. `id`, `status` и `updated` Google
 * выставляет сам; в PATCH они не отправляются. `description = null` очищает
 * описание, `recurrence = null` снимает повтор — это осознанная перезапись.
 */
@Serializable
data class GoogleEventWriteDto(
    val summary: String,
    val description: String? = null,
    val start: GoogleDateTimeWriteDto,
    val end: GoogleDateTimeWriteDto,
    val recurrence: List<String>? = null,
    val reminders: GoogleRemindersDto,
)

/**
 * Отображение событий Google Calendar API v3 в домен и обратно.
 *
 * All-day события Google приходят датами: `start.date` включительно, `end.date`
 * исключительно. В домен они попадают как местные сутки: 00:00 старт и
 * 23:59:59.999 конец. Напоминания — только `popup` (email домен не моделирует).
 */
object GoogleEventMapper {

    const val NO_TITLE = "(без названия)"
    private const val DEFAULT_DURATION_MILLIS = 3_600_000L

    fun fromDto(dto: GoogleEventDto, zone: ZoneId, observedAt: Long): RemoteEvent? {
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        val start = startMillis(dto.start, zone) ?: 0L
        val end = endMillis(dto.end, zone) ?: (start + DEFAULT_DURATION_MILLIS)
        return RemoteEvent(
            remoteId = id,
            title = dto.summary?.takeIf { it.isNotBlank() } ?: NO_TITLE,
            description = dto.description,
            startTime = start,
            endTime = end,
            reminderMinutes = popupMinutes(dto),
            repeatRule = RruleCodec.fromRrule(
                dto.recurrence?.firstOrNull { it.startsWith("RRULE", ignoreCase = true) }
            ),
            updatedAt = dto.updated
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: observedAt,
            deleted = dto.status == "cancelled",
        )
    }

    fun toWriteDto(event: Event, zone: ZoneId): GoogleEventWriteDto = GoogleEventWriteDto(
        summary = event.title,
        description = event.description,
        start = GoogleDateTimeWriteDto(dateTime = rfc3339(event.startTime, zone), timeZone = zone.id),
        end = GoogleDateTimeWriteDto(dateTime = rfc3339(event.endTime, zone), timeZone = zone.id),
        recurrence = RruleCodec.toRrule(event.repeatRule)?.let { listOf("RRULE:$it") },
        reminders = GoogleRemindersDto(
            useDefault = false,
            overrides = event.reminderMinutes
                .distinct()
                .filter { it >= 0 }
                .sorted()
                .map { GoogleReminderDto(method = "popup", minutes = it) },
        ),
    )

    private fun popupMinutes(dto: GoogleEventDto): List<Int> =
        dto.reminders?.overrides.orEmpty()
            .filter { it.method == "popup" }
            .mapNotNull { it.minutes?.takeIf { minutes -> minutes >= 0 } }
            .distinct()
            .sorted()

    private fun startMillis(value: GoogleDateTimeDto?, zone: ZoneId): Long? {
        if (value == null) return null
        value.dateTime?.let(::dateTimeMillis)?.let { return it }
        return value.date?.let { date ->
            runCatching {
                LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }

    private fun endMillis(value: GoogleDateTimeDto?, zone: ZoneId): Long? {
        if (value == null) return null
        value.dateTime?.let(::dateTimeMillis)?.let { return it }
        return value.date?.let { date ->
            runCatching {
                LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            }.getOrNull()
        }
    }

    private fun dateTimeMillis(raw: String): Long? =
        runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()

    private fun rfc3339(epochMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toOffsetDateTime().toString()
}
```

- [ ] **Step 4: Прогнать — зелёный**

Run: `./gradlew :shared:test --tests "com.example.mycalendar.data.remote.google.GoogleEventMapperTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add shared/src/main/kotlin/com/example/mycalendar/data/remote/google/GoogleEventMapper.kt \
        shared/src/test/kotlin/com/example/mycalendar/data/remote/google/GoogleEventMapperTest.kt
git commit -m "feat(shared): отображение событий Google Calendar API"
```

---

### Task 3: GoogleCalendarDataSource — pull и push

**Files:**
- Create: `shared/src/main/kotlin/com/example/mycalendar/data/remote/google/GoogleCalendarDataSource.kt`
- Create: `shared/src/test/kotlin/com/example/mycalendar/data/remote/google/GoogleCalendarDataSourceTest.kt`

**Interfaces:**
- Consumes: `GoogleEventMapper`, `AuthRepository.getAccessToken(GOOGLE, forceRefresh)`, `RemoteCalendarDataSource`.
- Produces: `class GoogleCalendarDataSource(http: OkHttpClient, authRepository: AuthRepository, baseUrl: String = "https://www.googleapis.com/calendar/v3", zone: () -> ZoneId = { ZoneId.systemDefault() }, now: () -> Long = { System.currentTimeMillis() }) : RemoteCalendarDataSource` и `class GoogleCalendarUnavailableException(message: String) : Exception(message)`.

- [ ] **Step 1: Написать падающий тест**

`shared/src/test/kotlin/com/example/mycalendar/data/remote/google/GoogleCalendarDataSourceTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Прогнать — ошибка компиляции**

Run: `./gradlew :shared:test --tests "com.example.mycalendar.data.remote.google.GoogleCalendarDataSourceTest" --console=plain`
Expected: FAIL — нет класса `GoogleCalendarDataSource`.

- [ ] **Step 3: Реализовать источник**

`shared/src/main/kotlin/com/example/mycalendar/data/remote/google/GoogleCalendarDataSource.kt`:

```kotlin
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
            writeRequest("POST", EVENTS_PATH)
                .post(json.encodeToString(GoogleEventMapper.toWriteDto(event, zone())).toRequestBody(JSON)),
            forceRefresh = false,
        )
        json.decodeFromString<GoogleEventDto>(text).id
            ?.takeIf { it.isNotBlank() }
            ?: throw GoogleCalendarUnavailableException("Google не вернул id созданного события")
    }

    override suspend fun update(remoteId: String, event: Event) = withContext(Dispatchers.IO) {
        executeAuthorized(
            writeRequest("PATCH", "$EVENTS_PATH/$remoteId")
                .patch(json.encodeToString(GoogleEventMapper.toWriteDto(event, zone())).toRequestBody(JSON)),
            forceRefresh = false,
        )
        Unit
    }

    override suspend fun delete(remoteId: String) = withContext(Dispatchers.IO) {
        executeAuthorized(writeRequest("DELETE", "$EVENTS_PATH/$remoteId"), forceRefresh = false)
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

    private fun writeRequest(method: String, path: String): Request.Builder =
        Request.Builder().url("$baseUrl$path?sendUpdates=none".toHttpUrl()).method(method, null)

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
```

Замечания к реализации:
- `writeRequest(...).method("DELETE", null)` допустим в OkHttp.
- Для `POST`/`PATCH` тело передаётся отдельно через `.post(...)`/`.patch(...)`.
- `EVENTS_PATH` начинается со слэша, `baseUrl` — без него: склейка даёт правильный URL.

- [ ] **Step 4: Прогнать — зелёный**

Run: `./gradlew :shared:test --tests "com.example.mycalendar.data.remote.google.GoogleCalendarDataSourceTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Прогнать весь `:shared` и коммит**

Run: `./gradlew :shared:test --console=plain`
Expected: PASS.

```bash
git add shared/src/main/kotlin/com/example/mycalendar/data/remote/google/GoogleCalendarDataSource.kt \
        shared/src/test/kotlin/com/example/mycalendar/data/remote/google/GoogleCalendarDataSourceTest.kt
git commit -m "feat(shared): источник Google Calendar API с pull и push"
```

---

### Task 4: Хранилище токенов Google и конфигурация OAuth

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/example/mycalendar/desktop/data/DesktopPaths.kt:28`
- Create: `desktopApp/src/main/kotlin/com/example/mycalendar/desktop/data/DesktopGoogleTokens.kt`
- Create: `desktopApp/src/main/kotlin/com/example/mycalendar/desktop/auth/DesktopGoogleOAuthConfig.kt`
- Create: `desktopApp/src/test/kotlin/com/example/mycalendar/desktop/data/DesktopGoogleTokensTest.kt`

**Interfaces:**
- Produces:
  - `DesktopPaths.googleTokenFile(): Path` → `dataDir/google-token.bin`.
  - `data class GoogleTokens(access: String, refresh: String, expiresAt: Long)`.
  - `data class GoogleSession(tokens: GoogleTokens, account: UserAccount)`.
  - `class GoogleAuthExpiredException(message: String) : Exception(message)`.
  - `class DesktopGoogleTokens(vault: TokenVault, clientId: String, clientSecret: String, tokenUrl: String = DesktopGoogleOAuthConfig.TOKEN_URL, http: OkHttpClient = OkHttpClient(), now: () -> Long = { System.currentTimeMillis() })` с методами `restore()`, `save(tokens)`, `clear()`, `accessToken(forceRefresh: Boolean = false): String?`.
  - `object DesktopGoogleOAuthConfig { val clientId; val clientSecret; val configured; const val SCOPES; const val AUTH_URL; const val TOKEN_URL; const val USER_INFO_URL; const val CALENDAR_API_BASE }`.

- [ ] **Step 1: Написать падающий тест**

`desktopApp/src/test/kotlin/com/example/mycalendar/desktop/data/DesktopGoogleTokensTest.kt`:

```kotlin
package com.example.mycalendar.desktop.data

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopGoogleTokensTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class Files(val token: Path)

    private fun files(): Files = Files(folder.newFolder().toPath().resolve("google-token.bin"))

    private fun tokens(files: Files, tokenUrl: String, now: () -> Long = { 1_000L }) =
        DesktopGoogleTokens(
            vault = DesktopTokenVault(files.token, ReversingCipher()),
            clientId = "client-id",
            clientSecret = "client-secret",
            tokenUrl = tokenUrl,
            now = now,
        )

    @Test
    fun `saved token survives reload without refresh`() = runBlocking {
        val files = files()
        tokens(files, "http://127.0.0.1:9/token").apply {
            save(GoogleTokens("access-1", "refresh-1", expiresAt = 10_000L))
        }

        val reloaded = tokens(files, "http://127.0.0.1:9/token")

        assertEquals("access-1", reloaded.accessToken())
    }

    @Test
    fun `expired token is refreshed and the new one is stored`() = runBlocking {
        TokenServer(
            """{"access_token":"access-2","expires_in":3600}"""
        ).use { server ->
            val files = files()
            tokens(files, server.url).save(GoogleTokens("access-1", "refresh-1", expiresAt = 1_000L))

            val fresh = tokens(files, server.url).accessToken()

            assertEquals("access-2", fresh)
            assertEquals(
                "grant_type=refresh_token&client_id=client-id&client_secret=client-secret&refresh_token=refresh-1",
                server.lastRequest,
            )
            val again = tokens(files, "http://127.0.0.1:9/token").accessToken()
            assertEquals("access-2", again)
        }
    }

    @Test
    fun `invalid grant clears the session and asks to sign in`() = runBlocking {
        TokenServer("""{"error":"invalid_grant"}""", code = 400).use { server ->
            val files = files()
            tokens(files, server.url).save(GoogleTokens("access-1", "refresh-1", expiresAt = 1_000L))

            val error = runCatching { tokens(files, server.url).accessToken() }.exceptionOrNull()

            assertTrue(error is GoogleAuthExpiredException)
            assertTrue(error!!.message.orEmpty().contains("истекла", ignoreCase = true))
            assertNull(tokens(files, server.url).accessToken())
        }
    }

    @Test
    fun `unreadable file is not fatal`() = runBlocking {
        val files = files()
        java.nio.file.Files.writeString(files.token, "не json")

        assertNull(tokens(files, "http://127.0.0.1:9/token").accessToken())
    }

    @Test
    fun `service error keeps the session`() = runBlocking {
        TokenServer("""{"error":"backend"}""", code = 500).use { server ->
            val files = files()
            tokens(files, server.url).save(GoogleTokens("access-1", "refresh-1", expiresAt = 1_000L))

            val error = runCatching { tokens(files, server.url).accessToken() }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertTrue(error!!.message.orEmpty().contains("HTTP 500"))
        }
    }

    /** Крошечный сервер токена: один POST, ответ задан заранее. */
    private class TokenServer(body: String, private val code: Int = 200) : java.io.Closeable {

        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var lastRequest: String? = null
            private set

        val url: String get() = "http://127.0.0.1:${server.address.port}/token"

        init {
            server.createContext("/token") { exchange ->
                lastRequest = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
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
}

private class ReversingCipher : TokenCipher {
    override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
    override fun decrypt(blob: ByteArray): ByteArray = blob.reversedArray()
}
```

- [ ] **Step 2: Прогнать — ошибка компиляции**

Run: `./gradlew :desktopApp:test --tests "com.example.mycalendar.desktop.data.DesktopGoogleTokensTest" --console=plain`
Expected: FAIL — нет `DesktopGoogleTokens`.

- [ ] **Step 3: Добавить путь**

В `DesktopPaths.kt` после `tokenFile()`:

```kotlin
    fun googleTokenFile(): Path = dataDir().resolve("google-token.bin")
```

- [ ] **Step 4: Реализовать хранилище**

`desktopApp/src/main/kotlin/com/example/mycalendar/desktop/data/DesktopGoogleTokens.kt`:

```kotlin
package com.example.mycalendar.desktop.data

import com.example.mycalendar.desktop.auth.DesktopGoogleOAuthConfig
import com.example.mycalendar.domain.model.UserAccount
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** Токены Google в том виде, в каком они лежат в файле под DPAPI. */
@Serializable
data class GoogleTokens(
    val access: String,
    val refresh: String,
    val expiresAt: Long,
)

/** Успешный вход Google: токены и учётная запись, под которой они выданы. */
data class GoogleSession(
    val tokens: GoogleTokens,
    val account: UserAccount,
)

/** Google больше не принимает refresh-токен: нужен новый вход. */
class GoogleAuthExpiredException(message: String) : Exception(message)

/**
 * Токены Google между запусками: отдельный файл `google-token.bin` под тем же
 * DPAPI-шифром, что и токен Яндекса. Провайдеры независимы: выход из Яндекса
 * не трогает Google и наоборот.
 *
 * Refresh выполняется под мьютексом: параллельные pull/push не должны дважды
 * обменивать один refresh-токен. Если Google не прислал новый refresh,
 * сохраняется прежний — иначе следующее обновление потеряет сессию.
 */
class DesktopGoogleTokens(
    private val vault: TokenVault,
    private val clientId: String,
    private val clientSecret: String,
    private val tokenUrl: String = DesktopGoogleOAuthConfig.TOKEN_URL,
    private val http: OkHttpClient = OkHttpClient(),
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile
    private var cached: GoogleTokens? = null

    suspend fun restore() {
        mutex.withLock { restoreLocked() }
    }

    suspend fun save(tokens: GoogleTokens) {
        mutex.withLock {
            cached = tokens
            vault.save(json.encodeToString(tokens))
        }
    }

    suspend fun clear() {
        mutex.withLock { clearLocked() }
    }

    /** Access-токен; при истечении обновляется, `forceRefresh` — после 401. */
    suspend fun accessToken(forceRefresh: Boolean = false): String? = mutex.withLock {
        val tokens = cached ?: restoreLocked() ?: return@withLock null
        val fresh = !forceRefresh && tokens.expiresAt - now() > REFRESH_MARGIN_MILLIS
        if (fresh) tokens.access else refreshLocked(tokens).access
    }

    private suspend fun restoreLocked(): GoogleTokens? {
        cached?.let { return it }
        val raw = vault.load() ?: return null
        val tokens = runCatching { json.decodeFromString<GoogleTokens>(raw) }.getOrNull()
        if (tokens == null || tokens.access.isBlank() || tokens.refresh.isBlank()) {
            // Чужой или повреждённый файл: вход повторится, но не упадёт.
            vault.clear()
            return null
        }
        cached = tokens
        return tokens
    }

    private suspend fun refreshLocked(tokens: GoogleTokens): GoogleTokens {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", tokens.refresh)
            .build()
        val request = Request.Builder().url(tokenUrl).post(body).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching {
                    json.decodeFromString<TokenErrorResponse>(text).error
                }.getOrNull()
                if (error == "invalid_grant") {
                    clearLocked()
                    throw GoogleAuthExpiredException(
                        "Сессия Google истекла — войдите заново в Настройках"
                    )
                }
                throw IllegalStateException("Не удалось обновить токен Google: HTTP ${response.code}")
            }
            val parsed = runCatching {
                json.decodeFromString<TokenRefreshResponse>(text)
            }.getOrNull() ?: throw IllegalStateException("Google вернул неожиданный ответ при обновлении токена")
            if (parsed.access.isBlank()) {
                throw IllegalStateException("Google вернул пустой access-токен")
            }
            val next = GoogleTokens(
                access = parsed.access,
                refresh = parsed.refresh?.takeIf { it.isNotBlank() } ?: tokens.refresh,
                expiresAt = now() + TimeUnit.SECONDS.toMillis(parsed.expiresIn),
            )
            cached = next
            vault.save(json.encodeToString(next))
            return next
        }
    }

    private suspend fun clearLocked() {
        cached = null
        vault.clear()
    }

    @Serializable
    private data class TokenRefreshResponse(
        val access_token: String = "",
        val refresh_token: String? = null,
        val expires_in: Long = 3600,
    )

    @Serializable
    private data class TokenErrorResponse(val error: String? = null)

    private companion object {
        const val REFRESH_MARGIN_MILLIS = 60_000L
    }
}
```

- [ ] **Step 5: Реализовать конфигурацию**

`desktopApp/src/main/kotlin/com/example/mycalendar/desktop/auth/DesktopGoogleOAuthConfig.kt`:

```kotlin
package com.example.mycalendar.desktop.auth

/**
 * OAuth-клиент Google типа «Desktop app».
 *
 * Значения приходят из `local.properties` через JVM-свойства сборки и никогда
 * не попадают в репозиторий. Пустые значения — штатное состояние: сборка
 * проходит, вход в Google просто выключен в настройках.
 */
object DesktopGoogleOAuthConfig {

    private const val CLIENT_ID_PROPERTY = "mycalendar.google.clientId"
    private const val CLIENT_SECRET_PROPERTY = "mycalendar.google.clientSecret"

    val clientId: String
        get() = System.getProperty(CLIENT_ID_PROPERTY).orEmpty().trim()

    val clientSecret: String
        get() = System.getProperty(CLIENT_SECRET_PROPERTY).orEmpty().trim()

    val configured: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    const val SCOPES = "openid email https://www.googleapis.com/auth/calendar.events"
    const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val USER_INFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"
    const val CALENDAR_API_BASE = "https://www.googleapis.com/calendar/v3"
}
```

- [ ] **Step 6: Прогнать — зелёный**

Run: `./gradlew :desktopApp:test --tests "com.example.mycalendar.desktop.data.DesktopGoogleTokensTest" --console=plain`
Expected: PASS.

- [ ] **Step 7: Коммит**

```bash
git add desktopApp/src/main/kotlin/com/example/mycalendar/desktop/data/DesktopPaths.kt \
        desktopApp/src/main/kotlin/com/example/mycalendar/desktop/data/DesktopGoogleTokens.kt \
        desktopApp/src/main/kotlin/com/example/mycalendar/desktop/auth/DesktopGoogleOAuthConfig.kt \
        desktopApp/src/test/kotlin/com/example/mycalendar/desktop/data/DesktopGoogleTokensTest.kt
git commit -m "feat(desktop): токены Google под DPAPI и конфигурация OAuth"
```

---

### Task 5: DesktopGoogleLogin — вход через браузер

**Files:**
- Create: `desktopApp/src/main/kotlin/com/example/mycalendar/desktop/auth/DesktopGoogleLogin.kt`
- Create: `desktopApp/src/test/kotlin/com/example/mycalendar/desktop/auth/DesktopGoogleLoginTest.kt`

**Interfaces:**
- Consumes: `DesktopGoogleOAuthConfig`, `GoogleSession`, `GoogleTokens`.
- Produces: `class DesktopGoogleLogin(clientId: String = DesktopGoogleOAuthConfig.clientId, clientSecret: String = DesktopGoogleOAuthConfig.clientSecret, scope: String = DesktopGoogleOAuthConfig.SCOPES, authUrl: String = ..., tokenUrl: String = ..., userInfoUrl: String = ..., http: OkHttpClient = OkHttpClient(), timeoutMillis: Long = 10 * 60_000, now: () -> Long = ...)` с `suspend fun signIn(openBrowser: (String) -> Boolean): GoogleSession`; `internal fun googleCodeChallenge(verifier: String): String`.

- [ ] **Step 1: Написать падающий тест**

`desktopApp/src/test/kotlin/com/example/mycalendar/desktop/auth/DesktopGoogleLoginTest.kt`:

```kotlin
package com.example.mycalendar.desktop.auth

import com.example.mycalendar.domain.model.CalendarProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopGoogleLoginTest {

    @Test
    fun `pkce challenge matches the rfc 7636 vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            googleCodeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `sign in returns tokens and the account`() = runBlocking {
        FakeGoogleAuthServer().use { server ->
            var capturedAuthUrl: String? = null
            val session = login(server).signIn { url ->
                capturedAuthUrl = url
                visitCallback(url, state = null, code = "code-1")
            }

            val auth = capturedAuthUrl!!.toHttpUrl()
            assertEquals("code", auth.queryParameter("response_type"))
            assertEquals("offline", auth.queryParameter("access_type"))
            assertEquals("consent", auth.queryParameter("prompt"))
            assertEquals("S256", auth.queryParameter("code_challenge_method"))
            assertEquals(
                googleCodeChallenge(server.lastVerifier!!),
                auth.queryParameter("code_challenge"),
            )

            assertEquals("access-1", session.tokens.access)
            assertEquals("refresh-1", session.tokens.refresh)
            assertEquals(1_000L + 3_600_000L, session.tokens.expiresAt)
            assertEquals(CalendarProvider.GOOGLE, session.account.provider)
            assertEquals("ivan@example.com", session.account.email)
            assertEquals("Иван", session.account.displayName)
            assertTrue(server.sawUserInfo)
        }
    }

    @Test
    fun `state mismatch is rejected before the token request`() = runBlocking {
        FakeGoogleAuthServer().use { server ->
            val error = runCatching {
                login(server).signIn { url -> visitCallback(url, state = "чужой", code = "code-1") }
            }.exceptionOrNull()

            assertTrue(error!!.message.orEmpty().contains("Неверный ответ входа"))
            assertEquals(null, server.lastVerifier)
        }
    }

    @Test
    fun `browser failure stops the login`() = runBlocking {
        FakeGoogleAuthServer().use { server ->
            val error = runCatching { login(server).signIn { false } }.exceptionOrNull()

            assertTrue(error!!.message.orEmpty().contains("браузер"))
        }
    }

    @Test
    fun `unanswered callback times out`() = runBlocking {
        FakeGoogleAuthServer().use { server ->
            val error = runCatching {
                login(server, timeoutMillis = 300).signIn { true }
            }.exceptionOrNull()

            assertTrue(error!!.message.orEmpty().contains("не завершён"))
        }
    }

    private fun login(server: FakeGoogleAuthServer, timeoutMillis: Long = 10_000L) =
        DesktopGoogleLogin(
            clientId = "client-id",
            clientSecret = "client-secret",
            authUrl = server.url + "/authorize",
            tokenUrl = server.url + "/token",
            userInfoUrl = server.url + "/userinfo",
            http = OkHttpClient(),
            timeoutMillis = timeoutMillis,
            now = { 1_000L },
        )

    /** Имитация браузера: приходит на redirect_uri с кодом и state из auth-URL. */
    private fun visitCallback(authUrl: String, state: String?, code: String): Boolean {
        val url = authUrl.toHttpUrl()
        val redirect = url.queryParameter("redirect_uri")!!
        val realState = state ?: url.queryParameter("state")!!
        val target = "$redirect?code=${URLEncoder.encode(code, "UTF-8")}" +
            "&state=${URLEncoder.encode(realState, "UTF-8")}"
        val connection = URI(target).toURL().openConnection() as HttpURLConnection
        connection.responseCode
        connection.inputStream.use { it.readBytes() }
        return true
    }

    /** Сервер Google: авторизация не нужна, обменивает код и отдаёт профиль. */
    private class FakeGoogleAuthServer : java.io.Closeable {

        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var lastVerifier: String? = null
            private set
        var sawUserInfo: Boolean = false
            private set

        val url: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/token") { exchange ->
                val form = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                lastVerifier = form.substringAfter("code_verifier=", "").substringBefore('&')
                val body = """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600}"""
                respond(exchange, 200, body)
            }
            server.createContext("/userinfo") { exchange ->
                sawUserInfo = exchange.requestHeaders.getFirst("Authorization") == "Bearer access-1"
                respond(exchange, 200, """{"email":"ivan@example.com","name":"Иван"}""")
            }
            server.start()
        }

        private fun respond(exchange: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        override fun close() {
            server.stop(0)
        }
    }
}
```

`URLDecoder` в тесте не нужен — удалить импорт, если компилятор предупредит.

- [ ] **Step 2: Прогнать — ошибка компиляции**

Run: `./gradlew :desktopApp:test --tests "com.example.mycalendar.desktop.auth.DesktopGoogleLoginTest" --console=plain`
Expected: FAIL — нет `DesktopGoogleLogin`.

- [ ] **Step 3: Реализовать вход**

`desktopApp/src/main/kotlin/com/example/mycalendar/desktop/auth/DesktopGoogleLogin.kt`:

```kotlin
package com.example.mycalendar.desktop.auth

import com.example.mycalendar.desktop.data.GoogleSession
import com.example.mycalendar.desktop.data.GoogleTokens
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.UserAccount
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** S256-преобразование code_verifier в code_challenge (RFC 7636). */
internal fun googleCodeChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

/**
 * Вход Google для desktop: loopback-redirect + PKCE.
 *
 * Установленное приложение не может использовать device flow для календарных
 * scope, зато клиент типа «Desktop app» принимает redirect на любой порт
 * 127.0.0.1. Порт выбирает ОС, `state` защищает от чужого колбэка, браузер
 * получает обычную страницу «вернитесь в программу».
 */
class DesktopGoogleLogin(
    private val clientId: String = DesktopGoogleOAuthConfig.clientId,
    private val clientSecret: String = DesktopGoogleOAuthConfig.clientSecret,
    private val scope: String = DesktopGoogleOAuthConfig.SCOPES,
    private val authUrl: String = DesktopGoogleOAuthConfig.AUTH_URL,
    private val tokenUrl: String = DesktopGoogleOAuthConfig.TOKEN_URL,
    private val userInfoUrl: String = DesktopGoogleOAuthConfig.USER_INFO_URL,
    private val http: OkHttpClient = OkHttpClient(),
    private val timeoutMillis: Long = TimeUnit.MINUTES.toMillis(10),
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun signIn(openBrowser: (String) -> Boolean): GoogleSession {
        require(clientId.isNotBlank() && clientSecret.isNotBlank()) {
            "Вход в Google не настроен: нет client_id в local.properties"
        }
        val verifier = randomVerifier()
        val state = UUID.randomUUID().toString()
        val callback = CompletableDeferred<Callback>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/oauth2callback") { exchange ->
            finishCallback(exchange, state, callback)
        }
        server.start()
        try {
            val redirect = "http://127.0.0.1:${server.address.port}/oauth2callback"
            if (!openBrowser(authorizationUrl(redirect, googleCodeChallenge(verifier), state))) {
                throw IllegalStateException("Не удалось открыть браузер для входа в Google")
            }
            val result = try {
                withTimeout(timeoutMillis) { callback.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw IllegalStateException("Вход через Google не завершён — попробуйте ещё раз")
            }
            when (result) {
                is Callback.Failed -> throw IllegalStateException(result.message)
                is Callback.Code -> return withContext(Dispatchers.IO) {
                    exchange(result.code, redirect, verifier)
                }
            }
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun finishCallback(
        exchange: HttpExchange,
        expectedState: String,
        callback: CompletableDeferred<Callback>,
    ) {
        val query = exchange.requestURI.rawQuery.orEmpty()
        val code = queryValue(query, "code")
        val error = queryValue(query, "error")
        val state = queryValue(query, "state")
        when {
            error != null -> {
                respond(exchange, 400, "Вход отменён. Вернитесь в программу.")
                callback.complete(Callback.Failed("Вход через Google отменён"))
            }
            state != expectedState -> {
                respond(exchange, 400, "Неверный ответ входа. Попробуйте ещё раз.")
                callback.complete(Callback.Failed("Неверный ответ входа"))
            }
            code == null -> {
                respond(exchange, 400, "Google не прислал код входа.")
                callback.complete(Callback.Failed("Google не прислал код входа"))
            }
            else -> {
                respond(exchange, 200, "Вход завершён. Вернитесь в программу и закройте вкладку.")
                callback.complete(Callback.Code(code))
            }
        }
    }

    private fun authorizationUrl(redirect: String, challenge: String, state: String): String =
        authUrl.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirect)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", scope)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .addQueryParameter("access_type", "offline")
            .addQueryParameter("prompt", "consent")
            .build()
            .toString()

    private fun exchange(code: String, redirect: String, verifier: String): GoogleSession {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirect)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("code_verifier", verifier)
            .build()
        val tokenText = post(tokenUrl, body)
        val token = json.decodeFromString<TokenResponse>(tokenText)
        val refresh = token.refresh?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Google не выдал долговременный токен — попробуйте войти ещё раз")
        if (token.access.isBlank()) {
            throw IllegalStateException("Google вернул пустой access-токен")
        }
        val account = fetchAccount(token.access)
        return GoogleSession(
            tokens = GoogleTokens(
                access = token.access,
                refresh = refresh,
                expiresAt = now() + TimeUnit.SECONDS.toMillis(token.expiresIn),
            ),
            account = account,
        )
    }

    private fun fetchAccount(accessToken: String): UserAccount {
        val request = Request.Builder()
            .url(userInfoUrl)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Не удалось получить профиль Google: HTTP ${response.code}")
            }
            val info = json.decodeFromString<UserInfo>(response.body?.string().orEmpty())
            val email = info.email?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Google не вернул e-mail")
            return UserAccount(
                provider = CalendarProvider.GOOGLE,
                email = email,
                displayName = info.name?.takeIf { it.isNotBlank() } ?: email,
            )
        }
    }

    private fun post(url: String, body: FormBody): String {
        val request = Request.Builder().url(url).post(body).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Google отклонил вход: HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun randomVerifier(): String {
        val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun queryValue(query: String, name: String): String? =
        query.split('&')
            .firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { java.net.URLDecoder.decode(it, StandardCharsets.UTF_8) }

    private sealed interface Callback {
        data class Code(val code: String) : Callback
        data class Failed(val message: String) : Callback
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val access: String = "",
        @SerialName("refresh_token") val refresh: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
    )

    @Serializable
    private data class UserInfo(val email: String? = null, val name: String? = null)
}
```

- [ ] **Step 4: Прогнать — зелёный**

Run: `./gradlew :desktopApp:test --tests "com.example.mycalendar.desktop.auth.DesktopGoogleLoginTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add desktopApp/src/main/kotlin/com/example/mycalendar/desktop/auth/DesktopGoogleLogin.kt \
        desktopApp/src/test/kotlin/com/example/mycalendar/desktop/auth/DesktopGoogleLoginTest.kt
git commit -m "feat(desktop): вход Google через браузер с loopback и PKCE"
```

---

### Task 6: Второй провайдер в репозитории и источнике

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/example/mycalendar/desktop/data/DesktopAuthRepository.kt` (весь класс)
- Modify: `desktopApp/src/main/kotlin/com/example/mycalendar/desktop/data/DesktopRemoteDataSourceProvider.kt`
- Modify: `desktopApp/src/main/kotlin/com/example/mycalendar/desktop/Main.kt:166-224`
- Modify: `desktopApp/src/test/kotlin/com/example/mycalendar/desktop/data/DesktopAuthRepositoryTest.kt`
- Modify: `desktopApp/src/test/kotlin/com/example/mycalendar/desktop/data/DesktopRemoteDataSourceProviderTest.kt`
- Modify: `desktopApp/src/test/kotlin/com/example/mycalendar/desktop/data/DesktopSyncEngineTest.kt:40-54`
- Modify: `shared/src/main/kotlin/com/example/mycalendar/domain/repository/AuthRepository.kt` (KDoc)
- Modify: `shared/src/main/kotlin/com/example/mycalendar/data/remote/RemoteCalendarDataSource.kt` (KDoc)

**Interfaces:**
- Consumes: `DesktopGoogleTokens`, `GoogleSession`, `DesktopGoogleLogin`, `GoogleCalendarDataSource`.
- Produces: `DesktopAuthRepository(store, settingsRepository, tokenVault, googleTokens, authorizeYandex, authorizeGoogle)`; `suspend fun restoreTokens()`; `DesktopRemoteDataSourceProvider(google: RemoteCalendarDataSource, yandex: RemoteCalendarDataSource)`.

- [ ] **Step 1: Обновить тест репозитория (сначала падающий)**

В `DesktopAuthRepositoryTest.kt`:

1. Заменить `TempPaths` и `newFiles()` на версию с google-файлом:

```kotlin
    private class TempPaths(
        val account: Path,
        val settings: Path,
        val token: Path,
        val googleToken: Path,
    )

    private fun newFiles(): TempPaths {
        val dir = folder.newFolder().toPath()
        return TempPaths(
            account = dir.resolve("account.json"),
            settings = dir.resolve("settings.json"),
            token = dir.resolve("token.bin"),
            googleToken = dir.resolve("google-token.bin"),
        )
    }

    private fun googleTokens(files: TempPaths) = DesktopGoogleTokens(
        vault = DesktopTokenVault(files.googleToken, ReversingCipher()),
        clientId = "client-id",
        clientSecret = "client-secret",
        tokenUrl = "http://127.0.0.1:9/token",
    )
```

2. Заменить фабрику:

```kotlin
    private fun repository(
        files: TempPaths,
        authorizeYandex: suspend () -> YandexSession,
        authorizeGoogle: suspend () -> GoogleSession = { error("вход Google не нужен") },
    ): Pair<DesktopAuthRepository, DesktopSettingsRepository> {
        val settingsRepository = DesktopSettingsRepository(DesktopSettingsStore(files.settings))
        val repository = DesktopAuthRepository(
            store = DesktopAuthStore(files.account),
            settingsRepository = settingsRepository,
            tokenVault = vault(files),
            googleTokens = googleTokens(files),
            authorizeYandex = authorizeYandex,
            authorizeGoogle = authorizeGoogle,
        )
        return repository to settingsRepository
    }
```

3. Во всех вызовах `repository(files) { session }` заменить лямбду на именованный аргумент `authorizeYandex = { session }`; `restoreToken()` → `restoreTokens()`.

4. Тест `google is unsupported and has no token` заменить тремя:

```kotlin
    @Test
    fun `google sign in stores the account and the tokens`() = runBlocking {
        val files = newFiles()
        val googleAccount = UserAccount(
            provider = CalendarProvider.GOOGLE,
            email = "user@gmail.com",
            displayName = "Пользователь Google",
        )
        val googleSession = GoogleSession(
            tokens = GoogleTokens("access-g", "refresh-g", expiresAt = Long.MAX_VALUE),
            account = googleAccount,
        )
        val (repository, _) = repository(
            files,
            authorizeYandex = { error("Яндекс не нужен") },
            authorizeGoogle = { googleSession },
        )

        val result = repository.connectGoogleCalendar()

        assertEquals(googleAccount, result.getOrNull())
        assertEquals(googleAccount, repository.accountFor(CalendarProvider.GOOGLE))
        assertEquals(listOf(CalendarProvider.GOOGLE), repository.connectedProviders())
        assertEquals("access-g", repository.getAccessToken(CalendarProvider.GOOGLE))
        assertEquals("access-g", googleTokens(files).accessToken())
    }

    @Test
    fun `google and yandex are independent`() = runBlocking {
        val files = newFiles()
        val googleAccount = UserAccount(CalendarProvider.GOOGLE, "user@gmail.com")
        val googleSession = GoogleSession(
            tokens = GoogleTokens("access-g", "refresh-g", expiresAt = Long.MAX_VALUE),
            account = googleAccount,
        )
        val (repository, _) = repository(
            files,
            authorizeYandex = { session },
            authorizeGoogle = { googleSession },
        )
        repository.signInWithYandex()
        repository.connectGoogleCalendar()

        repository.signOut(CalendarProvider.GOOGLE)

        assertEquals(account, repository.accountFor(CalendarProvider.YANDEX))
        assertEquals("token-1", repository.getAccessToken(CalendarProvider.YANDEX))
        assertNull(repository.accountFor(CalendarProvider.GOOGLE))
        assertNull(repository.getAccessToken(CalendarProvider.GOOGLE))
        assertNull(googleTokens(files).accessToken())
    }

    @Test
    fun `google tokens without an account are dropped on restore`() = runBlocking {
        val files = newFiles()
        googleTokens(files).save(GoogleTokens("access", "refresh", expiresAt = Long.MAX_VALUE))
        val (repository, _) = repository(files, authorizeYandex = { session })

        repository.restoreTokens()

        assertNull(repository.getAccessToken(CalendarProvider.GOOGLE))
        assertNull(googleTokens(files).accessToken())
    }
```

Тест `account and token survive reload` дополнить проверкой Google не нужно — он про Яндекс.

- [ ] **Step 2: Прогнать — ошибка компиляции**

Run: `./gradlew :desktopApp:test --tests "com.example.mycalendar.desktop.data.DesktopAuthRepositoryTest" --console=plain`
Expected: FAIL — конструктор не принимает `googleTokens`.

- [ ] **Step 3: Переписать DesktopAuthRepository**

`DesktopAuthRepository.kt` целиком:

```kotlin
package com.example.mycalendar.desktop.data

import com.example.mycalendar.data.auth.AccessTokenProvider
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.UserAccount
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.CalendarConnection
import com.example.mycalendar.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Результат входа в Яндекс: токен и учётная запись, под которой он выдан. */
data class YandexSession(
    val token: String,
    val account: UserAccount,
)

/**
 * [AuthRepository] desktop-клиента: Яндекс и Google независимы.
 *
 * Яндекс входит по коду устройства, Google — через браузер (loopback + PKCE);
 * оба входа вынесены в колбэки, чтобы репозиторий не знал про UI и HTTP.
 * Аккаунты лежат в `account.json`, токены — в двух отдельных файлах под DPAPI:
 * выход из одного календаря не трогает другой.
 *
 * Токен без учётной записи бессмыслен: после сбоя выхода он остался бы в файле,
 * поэтому при восстановлении сначала сверяется `account.json`, а осиротевший
 * токен стирается.
 */
class DesktopAuthRepository(
    private val store: DesktopAuthStore,
    private val settingsRepository: SettingsRepository,
    private val tokenVault: TokenVault,
    private val googleTokens: DesktopGoogleTokens,
    private val authorizeYandex: suspend () -> YandexSession,
    private val authorizeGoogle: suspend () -> GoogleSession,
) : AuthRepository, AccessTokenProvider {

    private val managedProviders = listOf(CalendarProvider.GOOGLE, CalendarProvider.YANDEX)

    @Volatile
    private var yandexToken: String? = null

    override val connections: Flow<List<CalendarConnection>> = flow {
        emit(store.snapshot())
        emitAll(store.accounts.filterNotNull())
    }.map { accounts ->
        managedProviders.map { provider ->
            CalendarConnection(provider = provider, account = accounts.readAccount(provider))
        }
    }

    override suspend fun accountFor(provider: CalendarProvider): UserAccount? =
        store.snapshot().readAccount(provider)

    override suspend fun connectedProviders(): List<CalendarProvider> =
        managedProviders.filter { store.snapshot().readAccount(it) != null }

    override suspend fun connectGoogleCalendar(): Result<UserAccount> = try {
        val session = authorizeGoogle()
        saveAccount(session.account)
        // Вход состоялся и без сохранения токена: сбой диска не должен его отменять.
        runCatching { googleTokens.save(session.tokens) }
            .onFailure { System.err.println("google token was not saved: ${it.message}") }
        Result.success(session.account)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(
            IllegalStateException(
                error.message ?: "Не удалось войти через Google: ${error.javaClass.simpleName}"
            )
        )
    }

    override suspend fun signInWithYandex(): Result<UserAccount> = try {
        val session = authorizeYandex()
        yandexToken = session.token
        saveAccount(session.account)
        runCatching { tokenVault.save(session.token) }
            .onFailure { System.err.println("token was not saved: ${it.message}") }
        Result.success(session.account)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(
            IllegalStateException(
                error.message ?: "Не удалось войти через Яндекс: ${error.javaClass.simpleName}"
            )
        )
    }

    /**
     * Возвращает сохранённые токены после перезапуска — каждый только при
     * наличии своей учётной записи. Осиротевший токен стирается.
     */
    suspend fun restoreTokens() {
        if (store.snapshot().containsKey(CalendarProvider.YANDEX.name)) {
            yandexToken = tokenVault.load()
        } else {
            yandexToken = null
            tokenVault.clear()
        }
        if (store.snapshot().containsKey(CalendarProvider.GOOGLE.name)) {
            googleTokens.restore()
        } else {
            googleTokens.clear()
        }
    }

    override suspend fun signOut(provider: CalendarProvider) {
        store.update { accounts -> accounts - provider.name }
        when (provider) {
            CalendarProvider.YANDEX -> {
                yandexToken = null
                tokenVault.clear()
            }
            CalendarProvider.GOOGLE -> googleTokens.clear()
            CalendarProvider.LOCAL -> Unit
        }
    }

    override suspend fun signOutAll() {
        managedProviders.forEach { signOut(it) }
    }

    override suspend fun getAccessToken(provider: CalendarProvider, forceRefresh: Boolean): String? =
        when (provider) {
            CalendarProvider.GOOGLE -> googleTokens.accessToken(forceRefresh)
            CalendarProvider.YANDEX -> yandexToken
            CalendarProvider.LOCAL -> null
        }

    /** [AccessTokenProvider] глазами CalDAV-источника: там бывает только Яндекс. */
    override fun getAccessToken(): String? = yandexToken

    private suspend fun saveAccount(account: UserAccount) {
        store.update { accounts ->
            accounts + (account.provider.name to StoredAccount(account.email, account.displayName))
        }
        settingsRepository.clearCollectionUrl(account.provider)
    }
}

private fun Map<String, StoredAccount>.readAccount(provider: CalendarProvider): UserAccount? =
    this[provider.name]?.let { stored ->
        UserAccount(provider = provider, email = stored.email, displayName = stored.displayName)
    }
```

- [ ] **Step 4: Провайдер источников**

`DesktopRemoteDataSourceProvider.kt` целиком:

```kotlin
package com.example.mycalendar.desktop.data

import com.example.mycalendar.data.remote.RemoteCalendarDataSource
import com.example.mycalendar.data.remote.RemoteDataSourceProvider
import com.example.mycalendar.domain.model.CalendarProvider

/** Подключённые к окну источники: Google и Яндекс независимы, LOCAL — только локально. */
class DesktopRemoteDataSourceProvider(
    private val google: RemoteCalendarDataSource,
    private val yandex: RemoteCalendarDataSource,
) : RemoteDataSourceProvider {

    override fun forProvider(provider: CalendarProvider): RemoteCalendarDataSource? =
        when (provider) {
            CalendarProvider.GOOGLE -> google
            CalendarProvider.YANDEX -> yandex
            CalendarProvider.LOCAL -> null
        }
}
```

- [ ] **Step 5: Обновить тест провайдера**

`DesktopRemoteDataSourceProviderTest.kt` — новая фабрика и три теста:

```kotlin
    private class Fixture(
        val provider: DesktopRemoteDataSourceProvider,
        val google: RemoteCalendarDataSource,
        val yandex: RemoteCalendarDataSource,
    )

    private fun newProvider(): Fixture = runBlocking {
        val dir = folder.newFolder().toPath()
        val settings = DesktopSettingsRepository(DesktopSettingsStore(dir.resolve("settings.json")))
        val googleTokens = DesktopGoogleTokens(
            vault = MemoryTokenVault(),
            clientId = "client-id",
            clientSecret = "client-secret",
        )
        val auth = DesktopAuthRepository(
            store = DesktopAuthStore(dir.resolve("account.json")),
            settingsRepository = settings,
            tokenVault = MemoryTokenVault(),
            googleTokens = googleTokens,
            authorizeYandex = {
                YandexSession("token", UserAccount(CalendarProvider.YANDEX, "user@yandex.ru"))
            },
            authorizeGoogle = { error("вход Google не нужен") },
        )
        val yandex = YandexCalendarDataSource(CalDavClient(OkHttpClient()), auth, auth)
        val google = GoogleCalendarDataSource(OkHttpClient(), auth)
        Fixture(DesktopRemoteDataSourceProvider(google, yandex), google, yandex)
    }
```

Тесты:

```kotlin
    @Test
    fun `google and yandex use their sources`() {
        val fixture = newProvider()

        assertSame(fixture.google, fixture.provider.forProvider(CalendarProvider.GOOGLE))
        assertSame(fixture.yandex, fixture.provider.forProvider(CalendarProvider.YANDEX))
    }

    @Test
    fun `local has no remote source`() {
        assertNull(newProvider().provider.forProvider(CalendarProvider.LOCAL))
    }
```

Добавить импорт `com.example.mycalendar.data.remote.google.GoogleCalendarDataSource`.

- [ ] **Step 6: Обновить DesktopSyncEngineTest**

В блоке создания `auth` (строки 46–53) добавить `googleTokens` и оба колбэка:

```kotlin
        val auth = DesktopAuthRepository(
            store = DesktopAuthStore(dir.resolve("account.json")),
            settingsRepository = settings,
            tokenVault = MemoryTokenVault(),
            googleTokens = DesktopGoogleTokens(
                vault = MemoryTokenVault(),
                clientId = "client-id",
                clientSecret = "client-secret",
            ),
            authorizeYandex = {
                YandexSession("token-1", UserAccount(CalendarProvider.YANDEX, "user@yandex.ru"))
            },
            authorizeGoogle = { error("вход Google не нужен") },
        )
```

- [ ] **Step 7: Обновить Main (минимально, чтобы собрать)**

В `Main.kt`:

```kotlin
            val login = remember { DesktopYandexLogin() }
            val googleLogin = remember { DesktopGoogleLogin() }
            var deviceCode by remember { mutableStateOf<DeviceCode?>(null) }
            var loginError by remember { mutableStateOf<String?>(null) }
            var loginJob by remember { mutableStateOf<Job?>(null) }
            val tokenVault = remember { DesktopTokenVault(DesktopPaths.tokenFile(), DpapiTokenCipher()) }
            val googleTokens = remember {
                DesktopGoogleTokens(
                    vault = DesktopTokenVault(DesktopPaths.googleTokenFile(), DpapiTokenCipher()),
                    clientId = DesktopGoogleOAuthConfig.clientId,
                    clientSecret = DesktopGoogleOAuthConfig.clientSecret,
                )
            }
            val authRepository = remember {
                DesktopAuthRepository(
                    store = authStore,
                    settingsRepository = settingsRepository,
                    tokenVault = tokenVault,
                    googleTokens = googleTokens,
                    authorizeYandex = { login.signIn { code -> deviceCode = code } },
                    authorizeGoogle = { googleLogin.signIn(::openInBrowser) },
                )
            }
            LaunchedEffect(authRepository) { authRepository.restoreTokens() }
```

И в `DesktopRemoteDataSourceProvider`:

```kotlin
                    dataSourceProvider = DesktopRemoteDataSourceProvider(
                        google = GoogleCalendarDataSource(
                            http = OkHttpClient(),
                            authRepository = authRepository,
                        ),
                        yandex = YandexCalendarDataSource(
                            caldav = CalDavClient(OkHttpClient()),
                            auth = authRepository,
                            authRepository = authRepository,
                        ),
                    ),
```

Добавить импорты: `com.example.mycalendar.desktop.auth.DesktopGoogleLogin`,
`com.example.mycalendar.desktop.auth.DesktopGoogleOAuthConfig`,
`com.example.mycalendar.data.remote.google.GoogleCalendarDataSource`,
`com.example.mycalendar.desktop.data.DesktopGoogleTokens`.

- [ ] **Step 8: Обновить KDoc в `:shared`**

`AuthRepository.kt`, метод `connectGoogleCalendar` — KDoc:

```kotlin
    /**
     * Подключение Google Календаря.
     *
     * Android использует системный календарь: OAuth не нужен, достаточно
     * READ/WRITE_CALENDAR, а аккаунт владельца берётся из CalendarContract.
     *
     * Windows входит через браузер (loopback + PKCE) и работает с Google
     * Calendar API напрямую.
     *
     * @return аккаунт-владелец выбранного календаря (обычно e-mail Google).
     */
```

`getAccessToken` — KDoc:

```kotlin
    /**
     * OAuth access token для API выбранного провайдера.
     *
     * На Android Google здесь всегда null: синхронизация идёт через системный
     * календарь и токен не нужен. На Windows Google возвращает access-токен
     * Calendar API, а Яндекс — токен CalDAV.
     *
     * @param forceRefresh — сбросить закешированный токен (после 401).
     */
```

`RemoteCalendarDataSource.kt` — в списке реализаций добавить `GoogleCalendarDataSource` (Google Calendar API v3).

- [ ] **Step 9: Прогнать тесты desktop целиком**

Run: `./gradlew :desktopApp:test --console=plain`
Expected: PASS.

- [ ] **Step 10: Коммит**

```bash
git add desktopApp/src/main/kotlin/com/example/mycalendar/desktop/data/DesktopAuthRepository.kt \
        desktopApp/src/main/kotlin/com/example/mycalendar/desktop/data/DesktopRemoteDataSourceProvider.kt \
        desktopApp/src/main/kotlin/com/example/mycalendar/desktop/Main.kt \
        desktopApp/src/test/kotlin/com/example/mycalendar/desktop/data/DesktopAuthRepositoryTest.kt \
        desktopApp/src/test/kotlin/com/example/mycalendar/desktop/data/DesktopRemoteDataSourceProviderTest.kt \
        desktopApp/src/test/kotlin/com/example/mycalendar/desktop/data/DesktopSyncEngineTest.kt \
        shared/src/main/kotlin/com/example/mycalendar/domain/repository/AuthRepository.kt \
        shared/src/main/kotlin/com/example/mycalendar/data/remote/RemoteCalendarDataSource.kt
git commit -m "feat(desktop): Google как второй независимый провайдер синхронизации"
```

---

### Task 7: Карточка Google в настройках

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/example/mycalendar/desktop/settings/SettingsScreen.kt:58-232`
- Modify: `desktopApp/src/test/kotlin/com/example/mycalendar/desktop/settings/SettingsScreenUiTest.kt`

**Interfaces:**
- Produces: новые параметры `SettingsScreen`: `googleAccount: UserAccount? = null`, `googleLoginError: String? = null`, `googleSignInEnabled: Boolean = true`, `googleSigningIn: Boolean = false`, `onGoogleSignIn: () -> Unit = {}`, `onGoogleSignOut: () -> Unit = {}` (до `scrollState`). Теги: `settings-google-card`, `settings-google-account`, `settings-google-sign-in`, `settings-google-sign-out`, `settings-google-error`.

- [ ] **Step 1: Написать падающие UI-тесты**

Добавить в `SettingsScreenUiTest.kt` четыре теста:

```kotlin
    @Test
    fun `google card invites to sign in`() = runComposeUiTest {
        var signedIn = false
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                    googleAccount = null,
                    onGoogleSignIn = { signedIn = true },
                )
            }
        }

        onNodeWithTag("settings-google-account").assertExists()
        onNodeWithTag("settings-google-sign-in").performClick()

        assertTrue(signedIn)
    }

    @Test
    fun `google card is disabled without credentials`() = runComposeUiTest {
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                    googleSignInEnabled = false,
                )
            }
        }

        onNodeWithTag("settings-google-sign-in").assertIsNotEnabled()
        onNodeWithText("Вход не настроен", substring = true).assertExists()
    }

    @Test
    fun `connected google account signs out`() = runComposeUiTest {
        var signedOut = false
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                    googleAccount = UserAccount(CalendarProvider.GOOGLE, "ivan@gmail.com", "Иван"),
                    onGoogleSignOut = { signedOut = true },
                )
            }
        }

        onNodeWithText("Подключено: Иван").assertExists()
        onNodeWithTag("settings-google-sign-out").performClick()

        assertTrue(signedOut)
    }

    @Test
    fun `google signing in waits for the browser`() = runComposeUiTest {
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                    googleSigningIn = true,
                )
            }
        }

        onNodeWithTag("settings-google-sign-in").assertIsNotEnabled()
        onNodeWithText("Ожидаем подтверждения", substring = true).assertExists()
    }
```

- [ ] **Step 2: Прогнать — ошибка компиляции**

Run: `./gradlew :desktopApp:test --tests "com.example.mycalendar.desktop.settings.SettingsScreenUiTest" --console=plain`
Expected: FAIL — нет параметров `googleAccount` и др.

- [ ] **Step 3: Добавить параметры и карточку**

В сигнатуру `SettingsScreen` перед `scrollState`:

```kotlin
    googleAccount: UserAccount? = null,
    googleLoginError: String? = null,
    googleSignInEnabled: Boolean = true,
    googleSigningIn: Boolean = false,
    onGoogleSignIn: () -> Unit = {},
    onGoogleSignOut: () -> Unit = {},
```

В блоке «Календари» перед карточкой Яндекса вставить отдельный `Panel`:

```kotlin
            Panel {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings-google-card"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Google", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = googleStatusText(
                                account = googleAccount,
                                signingIn = googleSigningIn,
                                enabled = googleSignInEnabled,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.testTag("settings-google-account"),
                        )
                    }
                    if (googleAccount == null) {
                        TextButton(
                            onClick = onGoogleSignIn,
                            enabled = googleSignInEnabled && !googleSigningIn,
                            modifier = Modifier.testTag("settings-google-sign-in"),
                        ) {
                            Text("Войти")
                        }
                    } else {
                        TextButton(
                            onClick = onGoogleSignOut,
                            modifier = Modifier.testTag("settings-google-sign-out"),
                        ) {
                            Text("Выйти")
                        }
                    }
                }
                googleLoginError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("settings-google-error"),
                    )
                }
            }
```

Карточку Яндекса оставить как есть, только заголовок `Text("Яндекс", ...)` уже есть.

Добавить приватную функцию в конец файла:

```kotlin
/** Статус карточки Google: подключение важнее подсказки о входе. */
internal fun googleStatusText(
    account: UserAccount?,
    signingIn: Boolean,
    enabled: Boolean,
): String = when {
    account != null -> "Подключено: ${account.displayName ?: account.email}"
    signingIn -> "Ожидаем подтверждения в браузере…"
    !enabled -> "Вход не настроен: нет client_id в local.properties"
    else -> "Не подключён — вход через браузер"
}
```

- [ ] **Step 4: Прогнать — зелёный**

Run: `./gradlew :desktopApp:test --tests "com.example.mycalendar.desktop.settings.SettingsScreenUiTest" --console=plain`
Expected: PASS, старые тесты настроек тоже зелёные.

- [ ] **Step 5: Коммит**

```bash
git add desktopApp/src/main/kotlin/com/example/mycalendar/desktop/settings/SettingsScreen.kt \
        desktopApp/src/test/kotlin/com/example/mycalendar/desktop/settings/SettingsScreenUiTest.kt
git commit -m "feat(desktop): карточка Google в настройках"
```

---

### Task 8: Wiring окна, креды сборки, документы

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/example/mycalendar/desktop/Main.kt` (состояние и колбэки Google)
- Modify: `desktopApp/build.gradle.kts:39-46`
- Modify: `README.md`
- Modify: `docs/how-to-verify.md`

**Interfaces:**
- Consumes: всё из задач 1–7.
- Produces: рабочее окно с карточкой Google; JVM-свойства `mycalendar.google.clientId`/`mycalendar.google.clientSecret` в запуске и пакете.

- [ ] **Step 1: Состояние и колбэки в Main**

После `val yandexAccount = ...`:

```kotlin
            val googleAccount = connections
                .firstOrNull { it.provider == CalendarProvider.GOOGLE }
                ?.account
            val googleConfigured = remember { DesktopGoogleOAuthConfig.configured }
            var googleSigningIn by remember { mutableStateOf(false) }
            var googleLoginError by remember { mutableStateOf<String?>(null) }
```

В вызов `SettingsScreen` после `onSignOut = { ... }` (карточка Яндекса) добавить:

```kotlin
                            googleAccount = googleAccount,
                            googleLoginError = googleLoginError,
                            googleSignInEnabled = googleConfigured,
                            googleSigningIn = googleSigningIn,
                            onGoogleSignIn = {
                                if (!googleSigningIn) {
                                    googleLoginError = null
                                    googleSigningIn = true
                                    scope.launch {
                                        try {
                                            authRepository.connectGoogleCalendar()
                                                .onFailure { error ->
                                                    googleLoginError = error.message
                                                        ?: "Не удалось войти через Google"
                                                }
                                        } finally {
                                            googleSigningIn = false
                                        }
                                    }
                                }
                            },
                            onGoogleSignOut = {
                                scope.launch { authRepository.signOut(CalendarProvider.GOOGLE) }
                            },
```

- [ ] **Step 2: Креды из local.properties**

В начало `desktopApp/build.gradle.kts` (после `val appVersion`):

```kotlin
// OAuth-клиент Google живёт только в local.properties (файл вне git).
// Пустые значения — допустимая сборка: вход в Google просто выключен.
val localProperties = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}
val googleClientId = localProperties.getProperty("google.oauth.clientId").orEmpty().trim()
val googleClientSecret = localProperties.getProperty("google.oauth.clientSecret").orEmpty().trim()
```

В `compose.desktop.application` заменить блок `jvmArgs`:

```kotlin
        jvmArgs += listOf(
            "-Dmycalendar.version=$appVersion",
            "-Dmycalendar.google.clientId=$googleClientId",
            "-Dmycalendar.google.clientSecret=$googleClientSecret",
        )
```

- [ ] **Step 3: Прогнать сборку и тесты — убедиться, что JVM-свойства доходят**

Run: `./gradlew :desktopApp:test --console=plain`
Expected: PASS.

Run: `./gradlew :desktopApp:compileKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Дополнить README**

В раздел про Windows-клиент добавить подраздел:

```markdown
### Google Календарь (Windows)

Google подключается в «Настройках» через браузер. Для сборки нужен собственный
OAuth-клиент типа «Desktop app» в Google Cloud Console; его значения кладутся
в `local.properties` (файл не коммитится):

```properties
google.oauth.clientId=...
google.oauth.clientSecret=...
```

Без этих строк сборка проходит, но кнопка «Войти» у Google выключена.
```

Обновить счётчики тестов в README по факту прогона (шаг 5).

- [ ] **Step 5: Полный прогон и счётчики**

Run: `./gradlew :shared:test :desktopApp:test :app:testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL, без падений.

Посчитать из XML (только корень `<testsuite>`, иначе счёт удвоится):

```bash
python - <<'EOF'
import glob, xml.etree.ElementTree as ET
for name, path in [
    ("shared", "shared/build/test-results/test"),
    ("desktop", "desktopApp/build/test-results/test"),
    ("android", "app/build/test-results/testDebugUnitTest"),
]:
    total = failed = errors = skipped = 0
    for f in glob.glob(path + "/TEST-*.xml"):
        r = ET.parse(f).getroot()
        total += int(r.get("tests", 0))
        failed += int(r.get("failures", 0))
        errors += int(r.get("errors", 0))
        skipped += int(r.get("skipped", 0))
    print(name, "tests", total, "failures", failed, "errors", errors, "skipped", skipped)
EOF
```

Перенести числа в README (секция про тесты) и `docs/how-to-verify.md` (строка со счётчиками), сохранив формулировки файла.

- [ ] **Step 6: Убрать за собой и проверить дерево**

```bash
git status --short
```

В выводе не должно быть `ScreenshotSeedTest`, файлов из `%TEMP%` и сгенерированных отчётов.

- [ ] **Step 7: Коммит**

```bash
git add desktopApp/src/main/kotlin/com/example/mycalendar/desktop/Main.kt \
        desktopApp/build.gradle.kts README.md docs/how-to-verify.md
git commit -m "feat(desktop): включить Google-синхронизацию в окне и документах"
```

---

## Самопроверка плана

- Спека §2 (prerequisite): `local.properties` → JVM-свойства, `configured` гасит кнопку — Task 4, 8.
- §3 (loopback+PKCE, state, timeout, userinfo): Task 5.
- §4 (google-token.bin, restore, независимая очистка): Task 4, 6.
- §5 (источник, маппинг all-day/popup/RRULE/cancelled, collectionUrl, 401, 403/429): Task 2, 3.
- §6 (карточка над Яндексом, параметры, wiring, провайдер, движок не меняется): Task 6, 7, 8.
- §7 (тесты по файлам): Task 1–7.
- §8 (безопасность): bind на loopback, state, DPAPI, креды вне git, фейковые значения в тестах — Task 4, 5, 8.
- §9 (ограничения): ничего в коде не обещает больше; отражено в документах README.
- §11 (совместимость): новая схема не меняется, добавляется только `google-token.bin`.

Типы сквозные: `GoogleTokens`, `GoogleSession`, `DesktopGoogleTokens.accessToken(Boolean)`, `restoreTokens()`, `DesktopRemoteDataSourceProvider(google, yandex)`, `GoogleCalendarDataSource(http, authRepository)` — совпадают во всех задачах.

Исполнение: субагенты в этой сессии недоступны, поэтому — inline по `superpowers:executing-plans`, задача за задачей, с прогонами и локальными коммитами.
