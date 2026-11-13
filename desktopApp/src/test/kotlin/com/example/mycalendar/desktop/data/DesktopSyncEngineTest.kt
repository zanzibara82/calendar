package com.example.mycalendar.desktop.data

import com.example.mycalendar.data.remote.RemoteCalendarDataSource
import com.example.mycalendar.data.remote.RemoteDataSourceProvider
import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.data.remote.RemoteFetch
import com.example.mycalendar.data.repository.SyncRepositoryImpl
import com.example.mycalendar.desktop.reminder.DesktopReminderScheduler
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.UserAccount
import com.example.mycalendar.domain.sync.SyncConflictResolver
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Общий движок синхронизации на хранилищах desktop-клиента: события, настройки и очередь
 * конфликтов — настоящие файловые реализации, CalDAV заменён фейком источника.
 */
class DesktopSyncEngineTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var events: DesktopEventRepository
    private lateinit var settings: DesktopSettingsRepository
    private lateinit var conflicts: DesktopConflictRepository
    private lateinit var remote: FakeRemote
    private lateinit var engine: SyncRepositoryImpl

    @Before
    fun setUp() = runBlocking {
        val dir = folder.newFolder().toPath()
        events = DesktopEventRepository(EventFileStore(dir.resolve("events.json")))
        settings = DesktopSettingsRepository(DesktopSettingsStore(dir.resolve("settings.json")))
        conflicts = DesktopConflictRepository(DesktopConflictStore(dir.resolve("conflicts.json")))
        val auth = DesktopAuthRepository(
            store = DesktopAuthStore(dir.resolve("account.json")),
            settingsRepository = settings,
            authorize = {
                YandexSession("token-1", UserAccount(CalendarProvider.YANDEX, "user@yandex.ru"))
            },
        )
        auth.signInWithYandex()
        remote = FakeRemote()
        engine = SyncRepositoryImpl(
            eventRepository = events,
            authRepository = auth,
            settingsRepository = settings,
            conflictResolver = SyncConflictResolver(),
            conflictRepository = conflicts,
            dataSourceProvider = RemoteDataSourceProvider { remote },
            reminderScheduler = DesktopReminderScheduler(onReminder = {}),
        )
    }

    @Test
    fun `push sends a local event and pins the collection`() = runBlocking {
        val event = event(title = "Локальное")
        events.saveFromUser(event)

        val result = engine.syncProvider(CalendarProvider.YANDEX)

        assertTrue(result.message.orEmpty(), result.success)
        assertEquals(1, result.pushed)
        val pushed = remote.stored.getValue("remote-1")
        assertEquals("Локальное", pushed.title)
        val saved = events.getEvent(event.id)!!
        assertEquals("remote-1", saved.remoteId)
        assertEquals(CalendarProvider.YANDEX, saved.calendarProvider)
        assertEquals(COLLECTION, settings.collectionUrl(CalendarProvider.YANDEX).first())
        assertEquals("Личный", settings.calendarName(CalendarProvider.YANDEX).first())
        assertTrue(settings.lastSyncTime(CalendarProvider.YANDEX).first() > 0L)
    }

    @Test
    fun `pull inserts a remote event without a pending push`() = runBlocking {
        remote.stored["r1"] = RemoteEvent(
            remoteId = "r1",
            title = "Из облака",
            description = null,
            startTime = START,
            endTime = START + HOUR,
            reminderMinutes = emptyList(),
            repeatRule = null,
            updatedAt = 0L,
        )

        val result = engine.syncProvider(CalendarProvider.YANDEX)

        assertTrue(result.message.orEmpty(), result.success)
        assertEquals(1, result.pulled)
        val pulled = events.observeEventsBetween(0L, Long.MAX_VALUE).first().single()
        assertEquals("Из облака", pulled.title)
        assertEquals("r1", pulled.remoteId)
        assertEquals(CalendarProvider.YANDEX, pulled.calendarProvider)
        assertFalse(events.getPendingSyncEvents().any { it.event.id == pulled.id })
    }

    @Test
    fun `divergent edits become a conflict and the local value stays in force`() = runBlocking {
        val event = event(title = "Событие")
        events.saveFromUser(event)
        engine.syncProvider(CalendarProvider.YANDEX)
        val synced = events.getEvent(event.id)!!

        remote.stored["remote-1"] = remote.stored.getValue("remote-1").copy(title = "Облако")
        events.saveFromUser(synced.copy(title = "Локально"))

        val result = engine.syncProvider(CalendarProvider.YANDEX)

        assertTrue(result.message.orEmpty(), result.success)
        assertEquals(1, result.conflicts)
        val conflict = conflicts.observeConflicts().first().single()
        assertEquals(event.id, conflict.eventId)
        assertTrue(conflict.contested.contains(SyncField.TITLE))
        assertEquals("Локально", events.getEvent(event.id)!!.title)
        // Событие с открытым конфликтом не отправляется: облако остаётся при своей правке.
        assertEquals("Облако", remote.stored.getValue("remote-1").title)
    }

    private fun event(title: String) = Event(
        id = UUID.randomUUID().toString(),
        title = title,
        startTime = START,
        endTime = START + HOUR,
    )

    private class FakeRemote : RemoteCalendarDataSource {

        val stored = linkedMapOf<String, RemoteEvent>()
        private var nextId = 1

        override suspend fun fetchUpdatedSince(sinceMillis: Long): RemoteFetch = RemoteFetch(
            events = stored.values.toList(),
            presentRemoteIds = stored.keys.toSet(),
            collectionUrl = COLLECTION,
            calendarName = "Личный",
        )

        override suspend fun insert(event: Event): String {
            val remoteId = "remote-${nextId++}"
            stored[remoteId] = event.toRemote(remoteId)
            return remoteId
        }

        override suspend fun update(remoteId: String, event: Event) {
            stored[remoteId] = event.toRemote(remoteId)
        }

        override suspend fun delete(remoteId: String) {
            stored.remove(remoteId)
        }

        private fun Event.toRemote(remoteId: String) = RemoteEvent(
            remoteId = remoteId,
            title = title,
            description = description,
            startTime = startTime,
            endTime = endTime,
            reminderMinutes = reminderMinutes,
            repeatRule = repeatRule,
            updatedAt = updatedAt,
        )
    }

    private companion object {
        const val START = 1_700_000_000_000L
        const val HOUR = 3_600_000L
        const val COLLECTION = "https://caldav.yandex.ru/calendars/user/events/"
    }
}
