package com.example.mycalendar.desktop.conflict

import com.example.mycalendar.desktop.data.DesktopConflictRepository
import com.example.mycalendar.desktop.data.DesktopConflictStore
import com.example.mycalendar.desktop.data.DesktopEventRepository
import com.example.mycalendar.desktop.data.EventFileStore
import com.example.mycalendar.desktop.reminder.DesktopReminderScheduler
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot
import com.example.mycalendar.domain.usecase.DeletionChoice
import com.example.mycalendar.domain.usecase.ResolveConflictUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Разбор конфликта в desktop-окне на настоящих файловых хранилищах: очередь и события —
 * `conflicts.json` и `events.json`, разбор — общий [ResolveConflictUseCase].
 */
class ConflictsControllerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var events: DesktopEventRepository
    private lateinit var conflicts: DesktopConflictRepository
    private lateinit var controller: ConflictsController

    private val base = SyncSnapshot(
        title = "Общий предок",
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(10),
        description = "",
        repeatRule = null,
    )
    private val cloud = base.copy(title = "Облако")

    @Before
    fun setUp() {
        val dir = folder.newFolder().toPath()
        events = DesktopEventRepository(EventFileStore(dir.resolve("events.json")))
        conflicts = DesktopConflictRepository(DesktopConflictStore(dir.resolve("conflicts.json")))
        controller = ConflictsController(
            conflictRepository = conflicts,
            eventRepository = events,
            resolveConflict = ResolveConflictUseCase(conflicts, events, DesktopReminderScheduler()),
        )
    }

    private fun event() = Event(
        id = "e1",
        title = "Локальное",
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(10),
        calendarProvider = CalendarProvider.YANDEX,
        remoteId = "uid-1",
    )

    private fun conflict(
        kind: ConflictKind = ConflictKind.CONTENT,
        contested: Set<SyncField> = setOf(SyncField.TITLE),
        remote: SyncSnapshot = cloud,
    ) = SyncConflict(
        id = "c1",
        eventId = "e1",
        provider = CalendarProvider.YANDEX,
        remoteId = "uid-1",
        kind = kind,
        base = base,
        remote = remote,
        contested = contested,
        detectedAt = 5_000L,
    )

    @Test
    fun `open loads the conflict and the local event`() = runBlocking {
        events.saveFromUser(event())
        conflicts.save(conflict())

        controller.open("c1")

        val state = controller.state.value
        assertEquals("e1", state.selected?.eventId)
        assertEquals("Локальное", state.local?.title)
        assertTrue(state.remoteChosen.isEmpty())
        assertNull(state.notice)
    }

    @Test
    fun `open explains when the local row is gone`() = runBlocking {
        conflicts.save(conflict())

        controller.open("c1")

        val state = controller.state.value
        assertNull(state.selected)
        assertNull(state.local)
        assertEquals(ConflictsController.Notice.NO_LOCAL_ROW, state.notice)
    }

    @Test
    fun `only contested fields take the cloud value`() = runBlocking {
        events.saveFromUser(event())
        conflicts.save(conflict())
        controller.open("c1")

        controller.choose(SyncField.DESCRIPTION, true)
        assertTrue(controller.state.value.remoteChosen.isEmpty())

        controller.choose(SyncField.TITLE, true)
        assertEquals(setOf(SyncField.TITLE), controller.state.value.remoteChosen)

        controller.choose(SyncField.TITLE, false)
        assertTrue(controller.state.value.remoteChosen.isEmpty())
    }

    @Test
    fun `apply takes the chosen value and clears the queue`() = runBlocking {
        events.saveFromUser(event())
        conflicts.save(conflict())
        controller.open("c1")
        controller.choose(SyncField.TITLE, true)

        controller.apply()

        val saved = events.getEvent("e1")!!
        assertEquals("Облако", saved.title)
        // Разобранная строка уже равна облаку: предок ставится записью из облака,
        // подтверждение повторно не отправляется.
        val record = events.findSyncedRecord(CalendarProvider.YANDEX, "uid-1")!!
        assertEquals(cloud, record.base)
        assertFalse(record.pendingSync)
        assertTrue(conflicts.observeConflicts().first().isEmpty())
        assertNull(controller.state.value.selected)
        assertNull(controller.state.value.notice)
    }

    @Test
    fun `apply refuses a conflict that changed while the screen was open`() = runBlocking {
        events.saveFromUser(event())
        conflicts.save(conflict())
        controller.open("c1")
        // Очередь переписала строку на месте: облачная версия другая, чем видел пользователь.
        conflicts.save(conflict(remote = cloud.copy(title = "Другое облако")))

        controller.apply()

        assertEquals(ConflictsController.Notice.STALE, controller.state.value.notice)
        assertNull(controller.state.value.selected)
        assertEquals("Локальное", events.getEvent("e1")!!.title)
        assertEquals(1, conflicts.observeConflicts().first().size)
    }

    @Test
    fun `delete everywhere keeps the tombstone for push and closes the conflict`() = runBlocking {
        events.saveFromUser(event())
        events.markDeleted("e1")
        conflicts.save(conflict(kind = ConflictKind.LOCAL_DELETED, contested = setOf(SyncField.TITLE)))
        controller.open("c1")

        controller.resolveDeletion(DeletionChoice.DELETE_EVERYWHERE)

        val record = events.findSyncedRecord(CalendarProvider.YANDEX, "uid-1")!!
        assertTrue(record.isDeleted)
        assertEquals(cloud, record.base)
        assertTrue(conflicts.observeConflicts().first().isEmpty())
        assertNull(controller.state.value.selected)
    }

    @Test
    fun `dismissing the notice leaves the screen usable`() = runBlocking {
        conflicts.save(conflict())
        controller.open("c1")

        controller.dismissNotice()

        assertNull(controller.state.value.notice)
    }
}
