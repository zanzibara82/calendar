package com.example.mycalendar.desktop.data

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.model.SyncSnapshot
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopEventRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val hour = 3_600_000L
    private val start = 1_700_000_000_000L
    private var now = start + 5 * hour
    private lateinit var file: Path
    private lateinit var repository: DesktopEventRepository

    @Before
    fun setUp() {
        file = temp.newFolder().toPath().resolve("events.json")
        repository = newRepository()
    }

    private fun newRepository() = DesktopEventRepository(
        EventFileStore(file, clock = { now }),
        clock = { now },
    )

    private fun event(
        id: String,
        startTime: Long = start,
        endTime: Long = startTime + hour,
        repeatRule: RepeatRule? = null,
        updatedAt: Long = 0L,
    ) = Event(
        id = id,
        title = "Событие $id",
        startTime = startTime,
        endTime = endTime,
        repeatRule = repeatRule,
        updatedAt = updatedAt,
    )

    @Test
    fun `saved event survives restart`() = runBlocking {
        repository.saveFromUser(event("e1"))

        val reopened = newRepository()

        assertEquals("e1", reopened.getEvent("e1")?.id)
        assertEquals(1, reopened.getPendingSyncEvents().size)
        assertTrue(Files.exists(file))
    }

    @Test
    fun `all event fields survive restart`() = runBlocking {
        val full = event("e1", repeatRule = RepeatRule.MONTHLY).copy(
            description = "описание",
            reminderMinutes = listOf(5, 15, 1440),
            textNote = "заметка",
            tags = listOf(EventTag.BIRTHDAY, EventTag.CINEMA),
            voiceNotePath = "C:\\notes\\a.m4a",
        )

        repository.saveFromUser(full)

        assertEquals(full.copy(updatedAt = now), newRepository().getEvent("e1"))
    }

    @Test
    fun `range query returns overlapping events only`() = runBlocking {
        repository.saveFromUser(event("e1", startTime = start, endTime = start + 3 * hour))
        repository.saveFromUser(event("e2", startTime = start + 24 * hour))

        val found = repository.observeEventsBetween(start, start + hour).first()

        assertEquals(listOf("e1"), found.map { it.id })
    }

    @Test
    fun `repeating master is returned outside its own dates`() = runBlocking {
        repository.saveFromUser(event("weekly", startTime = start - 30 * 24 * hour, repeatRule = RepeatRule.WEEKLY))

        val found = repository.observeEventsBetween(start, start + hour).first()

        assertEquals(listOf("weekly"), found.map { it.id })
    }

    @Test
    fun `tombstone disappears from views but stays in sync queue`() = runBlocking {
        repository.saveFromUser(event("e1"))
        now += hour

        repository.markDeleted("e1")

        assertTrue(repository.observeEventsBetween(start, start + hour).first().isEmpty())
        assertTrue(repository.getUpcomingEvents(0).isEmpty())
        val pending = repository.getPendingSyncEvents()
        assertEquals(1, pending.size)
        assertTrue(pending.single().isDeleted)
        assertEquals(now, pending.single().event.updatedAt)
    }

    @Test
    fun `upcoming includes repeaters and excludes past one-off events`() = runBlocking {
        repository.saveFromUser(event("past", startTime = start - hour))
        repository.saveFromUser(event("future", startTime = start + hour))
        repository.saveFromUser(event("yearly", startTime = start - hour, repeatRule = RepeatRule.YEARLY))

        val upcoming = repository.getUpcomingEvents(start)

        assertEquals(listOf("yearly", "future"), upcoming.map { it.id })
    }

    @Test
    fun `attach to provider exposes record and clears pending flag`() = runBlocking {
        val base = SyncSnapshot.of(event("e1"))
        repository.saveFromUser(event("e1"))

        repository.attachToProvider("e1", "remote-1", CalendarProvider.YANDEX, base)

        val record = repository.findSyncedRecord(CalendarProvider.YANDEX, "remote-1")
        assertNotNull(record)
        assertEquals("e1", record!!.event.id)
        assertEquals(base, record.base)
        assertFalse(record.pendingSync)
        assertTrue(repository.getPendingSyncEvents().isEmpty())
        assertEquals(1, repository.getAttachedRecords(CalendarProvider.YANDEX).size)
    }

    @Test
    fun `user edit keeps previous base and marks pending`() = runBlocking {
        val base = SyncSnapshot.of(event("e1"))
        repository.saveFromUser(event("e1"))
        repository.attachToProvider("e1", "remote-1", CalendarProvider.GOOGLE, base)
        now += hour

        val edited = repository.getEvent("e1")!!.copy(startTime = start + 2 * hour, endTime = start + 3 * hour)
        repository.saveFromUser(edited)

        val record = repository.findSyncedRecord(CalendarProvider.GOOGLE, "remote-1")
        assertNotNull(record)
        assertEquals(base, record!!.base)
        assertTrue(record.pendingSync)
        assertEquals(start + 2 * hour, record.event.startTime)
        assertEquals(now, record.event.updatedAt)
    }

    @Test
    fun `remote save stores base and does not queue synchronization`() = runBlocking {
        val base = SyncSnapshot.of(event("e1"))
        val remote = event("e1").copy(remoteId = "remote-1", calendarProvider = CalendarProvider.YANDEX)

        repository.saveFromRemote(remote, base)

        assertTrue(repository.getPendingSyncEvents().isEmpty())
        val record = repository.findSyncedRecord(CalendarProvider.YANDEX, "remote-1")
        assertEquals(base, record?.base)
        assertFalse(record!!.pendingSync)
    }

    @Test
    fun `purge removes record from disk`() = runBlocking {
        repository.saveFromUser(event("e1"))

        repository.purge("e1")

        assertNull(repository.getEvent("e1"))
        assertNull(newRepository().getEvent("e1"))
        assertTrue(repository.getPendingSyncEvents().isEmpty())
    }

    @Test
    fun `corrupt file is quarantined and storage starts empty`() = runBlocking {
        Files.createDirectories(file.parent)
        Files.writeString(file, "{ broken", StandardCharsets.UTF_8)

        val loaded = newRepository()

        assertTrue(loaded.getUpcomingEvents(0).isEmpty())
        assertFalse(Files.exists(file))
        val backups = Files.list(file.parent).use { stream ->
            stream.toList().filter { it.fileName.toString().startsWith("events.json.corrupt-") }
        }
        assertEquals(1, backups.size)
    }

    @Test
    fun `parallel saves keep every record and leave no temp files`() = runBlocking {
        coroutineScope {
            (1..50).map { index ->
                async(Dispatchers.Default) { repository.saveFromUser(event("e$index")) }
            }.awaitAll()
        }

        assertEquals(50, repository.getUpcomingEvents(0).size)
        assertEquals(50, newRepository().getUpcomingEvents(0).size)
        val tempFiles = Files.list(file.parent).use { stream ->
            stream.toList().filter { it.fileName.toString().endsWith(".tmp") }
        }
        assertTrue(tempFiles.isEmpty())
    }
}
