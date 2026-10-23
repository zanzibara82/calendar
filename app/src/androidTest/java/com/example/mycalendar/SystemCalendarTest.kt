package com.example.mycalendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentResolver
import android.content.ContentValues
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.data.remote.toEvent
import com.example.mycalendar.data.system.SystemCalendarDataSource
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone
import javax.inject.Inject

/**
 * Сквозной тест [SystemCalendarDataSource]: insert -> fetch -> update -> delete
 * на реальном CalendarProvider эмулятора.
 *
 * Покрывает пуш-путь двусторонней синхронизации через системный календарь
 * (pull-путь проверяет сценарий подключения в AppUiTest).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SystemCalendarTest {

    /**
     * Разрешения на календарь выдаём через [GrantPermissionRule], а не `pm grant`
     * через `uiAutomation.executeShellCommand`: shell-команда асинхронна и не
     * успевала примениться к старту первого теста класса — он падал с
     * «Нет доступа к календарю телефона», хотя второй тест в том же процессе
     * проходил. Правило грантит синхронно (UiAutomation.grantRuntimePermission).
     */
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    @get:Rule(order = 1)
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var dataSource: SystemCalendarDataSource

    private val resolver: ContentResolver
        get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    private var createdRemoteId: String? = null
    private var createdCalendarId: Long? = null
    private var hasWritableCalendar = false

    @Before
    fun setUp() {
        // HiltAndroidRule НЕ инжектит поля теста автоматически — нужен явный inject().
        hiltRule.inject()

        // Чистый AVD без аккаунта Google (например, Android 11) вообще не содержит
        // календарей, и selectPrimaryCalendar() падает с «На телефоне нет
        // календарей, доступных для записи». Создаём локальный календарь сами,
        // чтобы тест проверял наш код, а не наполнение образа эмулятора.
        hasWritableCalendar = runBlocking { dataSource.writableCalendars().isNotEmpty() }
        if (!hasWritableCalendar) {
            val id = createLocalCalendar()
            createdCalendarId = id
            hasWritableCalendar = id != null &&
                runBlocking { dataSource.writableCalendars().isNotEmpty() }
        }
    }

    @After
    fun cleanup() {
        // Подчищаем за собой, чтобы не мусорить в календаре эмулятора
        val id = createdRemoteId?.toLongOrNull() ?: return
        runCatching {
            resolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                null, null,
            )
        }
    }

    @After
    fun removeProvisionedCalendar() {
        val id = createdCalendarId ?: return
        createdCalendarId = null
        runCatching {
            resolver.delete(
                ContentUris.withAppendedId(
                    CalendarContract.Calendars.CONTENT_URI
                        .buildUpon()
                        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, TEST_ACCOUNT)
                        .appendQueryParameter(
                            CalendarContract.Calendars.ACCOUNT_TYPE,
                            CalendarContract.ACCOUNT_TYPE_LOCAL,
                        )
                        .build(),
                    id,
                ),
                null, null,
            )
        }
    }

    /**
     * Создаёт локальный календарь (account type LOCAL — не требует аккаунта
     * Google) с уровнем доступа OWNER, чтобы он прошёл фильтр
     * `CAL_ACCESS_CONTRIBUTOR` в [SystemCalendarDataSource].
     */
    private fun createLocalCalendar(): Long? = runCatching {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, TEST_ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, TEST_CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, TEST_CALENDAR_NAME)
            put(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER,
            )
            put(CalendarContract.Calendars.OWNER_ACCOUNT, TEST_ACCOUNT)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.CAN_ORGANIZER_RESPOND, 0)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI
            .buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, TEST_ACCOUNT)
            .appendQueryParameter(
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL,
            )
            .build()
        resolver.insert(uri, values)?.let { ContentUris.parseId(it) }
    }.getOrNull()

    @Test
    fun insert_update_delete_roundTrip() = runBlocking {
        Assume.assumeTrue(
            "На устройстве нет календаря, доступного для записи — тест пропущен",
            hasWritableCalendar,
        )
        val calendar = dataSource.selectPrimaryCalendar()
        assertTrue("Календарь ${calendar.displayName} не доступен для записи",
            calendar.id > 0)

        val start = System.currentTimeMillis() + 60 * 60 * 1000
        val end = start + 60 * 60 * 1000
        val event = Event(
            id = "test-${System.nanoTime()}",
            title = "Round-trip test ${System.currentTimeMillis() % 100_000}",
            description = "Test description",
            startTime = start,
            endTime = end,
            reminderMinutes = listOf(5, 15),
            repeatRule = RepeatRule.WEEKLY,
            calendarProvider = CalendarProvider.GOOGLE,
        )

        // ---------- INSERT ----------
        val newId = dataSource.insert(event)
        createdRemoteId = newId
        assertNotNull("insert() вернул пустой id", newId)
        assertTrue("Событие не появилось в системном календаре", eventExists(newId.toLong()))

        val afterInsert = readEvent(newId.toLong())
        assertEquals(event.title, afterInsert?.title)
        assertEquals(event.startTime, afterInsert?.startMillis)
        assertEquals(event.endTime, afterInsert?.endMillis)
        assertEquals("FREQ=WEEKLY", afterInsert?.rrule)
        assertEquals(
            "Напоминания должны попасть в CalendarContract.Reminders",
            setOf(5, 15), remindersFor(newId.toLong()),
        )

        // ---------- FETCH ----------
        val pulled = dataSource.fetchUpdatedSince(sinceMillis = 0L)
        val matched = pulled.firstOrNull { it.remoteId == newId }
        assertNotNull("fetchUpdatedSince не вернул вставленное событие", matched)
        assertEquals(event.title, matched!!.title)
        assertEquals(event.startTime, matched.startTime)
        assertEquals(event.endTime, matched.endTime)
        assertEquals(listOf(5, 15).sorted(), matched.reminderMinutes.sorted())
        assertEquals(RepeatRule.WEEKLY, matched.repeatRule)

        // ---------- UPDATE ----------
        val updated = event.copy(title = "Updated ${event.title}", reminderMinutes = listOf(30))
        dataSource.update(newId, updated)
        val afterUpdate = readEvent(newId.toLong())
        assertEquals(updated.title, afterUpdate?.title)
        assertEquals(setOf(30), remindersFor(newId.toLong()))

        // ---------- DELETE ----------
        dataSource.delete(newId)
        createdRemoteId = null
        assertEquals(false, eventExists(newId.toLong()))
    }

    /**
     * Сценарий из ТЗ: одно событие одновременно с ГОЛОСОВОЙ и ТЕКСТОВОЙ заметкой.
     *
     * Проверяем, что push-путь [SystemCalendarDataSource] не падает, когда у
     * события заполнены ОБА поля заметок, и что заметки по дизайну остаются
     * ЛОКАЛЬНЫМИ: в CalendarContract они не пишутся, поэтому при обратном pull
     * (fetchUpdatedSince -> RemoteEvent.toEvent) поля textNote/voiceNotePath
     * приходят пустыми. Сами события (название/время/напоминания/повтор)
     * корректно проходят insert -> fetch -> update -> delete.
     */
    @Test
    fun event_withVoiceAndTextNote_roundTrip() = runBlocking {
        Assume.assumeTrue(
            "На устройстве нет календаря, доступного для записи — тест пропущен",
            hasWritableCalendar,
        )
        val calendar = dataSource.selectPrimaryCalendar()
        assertTrue("Календарь ${calendar.displayName} не доступен для записи",
            calendar.id > 0)

        val start = System.currentTimeMillis() + 2 * 60 * 60 * 1000
        val end = start + 90 * 60 * 1000
        val event = Event(
            id = "test-note-${System.nanoTime()}",
            title = "Событие с заметками ${System.currentTimeMillis() % 100_000}",
            description = "Описание события с двумя типами заметок",
            startTime = start,
            endTime = end,
            reminderMinutes = listOf(10),
            repeatRule = RepeatRule.MONTHLY,
            // --- оба типа заметок одновременно ---
            textNote = "Текстовая заметка: купить молоко и позвонить маме",
            voiceNotePath = "/sdcard/Android/data/com.example.mycalendar/files/voice_${System.nanoTime()}.m4a",
            calendarProvider = CalendarProvider.GOOGLE,
        )
        assertNotNull("textNote не должен быть пустым в исходном событии", event.textNote)
        assertNotNull("voiceNotePath не должен быть пустым в исходном событии", event.voiceNotePath)

        // ---------- INSERT (с обеими заметками) ----------
        val newId = dataSource.insert(event)
        createdRemoteId = newId
        assertNotNull("insert() вернул пустой id при наличии обеих заметок", newId)
        assertTrue(
            "Событие с голосовой+текстовой заметкой не появилось в системном календаре",
            eventExists(newId.toLong()),
        )

        val afterInsert = readEvent(newId.toLong())
        assertEquals(event.title, afterInsert?.title)
        assertEquals(event.startTime, afterInsert?.startMillis)
        assertEquals(event.endTime, afterInsert?.endMillis)
        assertEquals("FREQ=MONTHLY", afterInsert?.rrule)
        assertEquals(
            "Напоминание должно попасть в CalendarContract.Reminders даже при наличии заметок",
            setOf(10), remindersFor(newId.toLong()),
        )

        // ---------- FETCH: заметки остаются локальными ----------
        val pulled: List<RemoteEvent> = dataSource.fetchUpdatedSince(sinceMillis = 0L)
        val matched = pulled.firstOrNull { it.remoteId == newId }
        assertNotNull("fetchUpdatedSince не вернул вставленное событие", matched)
        assertEquals(event.title, matched!!.title)
        assertEquals(event.startTime, matched.startTime)
        assertEquals(event.endTime, matched.endTime)
        assertEquals(listOf(10), matched.reminderMinutes.sorted())
        assertEquals(RepeatRule.MONTHLY, matched.repeatRule)

        // RemoteEvent не переносит заметки: при маппинге в локальное событие
        // они должны быть null (это и есть гарантия «заметки не уходят в облако»).
        val pulledAsEvent = matched.toEvent(CalendarProvider.GOOGLE)
        assertEquals(
            "Текстовая заметка не должна синхронизироваться в облако через системный календарь",
            null, pulledAsEvent.textNote,
        )
        assertEquals(
            "Голосовая заметка не должна синхронизироваться в облако через системный календарь",
            null, pulledAsEvent.voiceNotePath,
        )

        // ---------- UPDATE (меняем название и напоминания) ----------
        val updated = event.copy(
            title = "Обновлено: ${event.title}",
            reminderMinutes = listOf(60),
        )
        dataSource.update(newId, updated)
        val afterUpdate = readEvent(newId.toLong())
        assertEquals(updated.title, afterUpdate?.title)
        assertEquals(setOf(60), remindersFor(newId.toLong()))

        // ---------- DELETE ----------
        dataSource.delete(newId)
        createdRemoteId = null
        assertEquals(false, eventExists(newId.toLong()))
    }

    private fun eventExists(id: Long): Boolean = readEvent(id) != null

    private data class EventSnapshot(
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val rrule: String?,
    )

    private fun readEvent(id: Long): EventSnapshot? {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.RRULE,
        )
        return runCatching {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                EventSnapshot(
                    title = c.getString(0).orEmpty(),
                    startMillis = c.getLong(1),
                    endMillis = c.getLong(2),
                    rrule = c.getString(3),
                )
            }
        }.getOrNull()
    }

    private fun remindersFor(eventId: Long): Set<Int> {
        val out = mutableSetOf<Int>()
        runCatching {
            resolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) out.add(c.getInt(0))
            }
        }
        return out
    }

    private companion object {
        /** Локальный аккаунт для календаря, который создаёт сам тест. */
        const val TEST_ACCOUNT = "test@mycalendar.local"
        const val TEST_CALENDAR_NAME = "MyCalendar Test"
    }
}
