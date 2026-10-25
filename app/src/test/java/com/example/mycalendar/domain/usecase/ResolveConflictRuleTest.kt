package com.example.mycalendar.domain.usecase

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Правила разбора конфликта: исход выбора «удалить везде»/«вернуть из облака» и состав
 * разобранной записи по выбранным полям. Чистые функции — потому что сам usecase
 * держит планировщик напоминаний и на JVM недоступен.
 */
class ResolveConflictRuleTest {

    private val base = SyncSnapshot(
        title = "Общий предок",
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(10),
        description = "",
        repeatRule = null,
    )
    private val cloud = base.copy(title = "Правка облака", startTime = 7_000L)
    private val local = Event(
        id = "e1",
        title = "Правка пользователя",
        description = "локальное описание",
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(10),
        tags = listOf(EventTag.BIRTHDAY),
        textNote = "заметка",
        voiceNotePath = "/sdcard/e1.m4a",
        calendarProvider = CalendarProvider.YANDEX,
        remoteId = "uid-1",
    )
    private val conflict = SyncConflict(
        id = "c1",
        eventId = "e1",
        provider = CalendarProvider.YANDEX,
        remoteId = "uid-1",
        kind = ConflictKind.CONTENT,
        base = base,
        remote = cloud,
        contested = setOf(SyncField.TITLE),
        detectedAt = 5_000L,
    )

    @Test
    fun `local tombstone deleted everywhere keeps tombstone for push`() {
        assertEquals(
            ResolutionAction.TombstoneAndPush,
            planResolution(ConflictKind.LOCAL_DELETED, DeletionChoice.DELETE_EVERYWHERE),
        )
    }

    @Test
    fun `cloud already gone means purge without push`() {
        assertEquals(
            ResolutionAction.PurgeLocal,
            planResolution(ConflictKind.REMOTE_DELETED, DeletionChoice.DELETE_EVERYWHERE),
        )
    }

    @Test
    fun `restoring after local deletion keeps the local row`() {
        assertEquals(
            ResolutionAction.RestoreFromCloud,
            planResolution(ConflictKind.LOCAL_DELETED, DeletionChoice.RESTORE_FROM_CLOUD),
        )
    }

    @Test
    fun `restoring after cloud deletion keeps the local row`() {
        assertEquals(
            ResolutionAction.RestoreFromCloud,
            planResolution(ConflictKind.REMOTE_DELETED, DeletionChoice.RESTORE_FROM_CLOUD),
        )
    }

    /**
     * Конфликт содержимого матрице неизвестен: любой выбор даёт возврат из облака, а от бинарного
     * выбора защищает `require` в самом разборе.
     */
    @Test
    fun `content conflict is unknown to the deletion matrix`() {
        assertEquals(
            ResolutionAction.RestoreFromCloud,
            planResolution(ConflictKind.CONTENT, DeletionChoice.DELETE_EVERYWHERE),
        )
        assertEquals(
            ResolutionAction.RestoreFromCloud,
            planResolution(ConflictKind.CONTENT, DeletionChoice.RESTORE_FROM_CLOUD),
        )
    }

    @Test
    fun `chosen field comes from the cloud, the rest stays local`() {
        val resolved = resolvedEvent(conflict, local, setOf(SyncField.TITLE))

        assertEquals("Правка облака", resolved.title)
        assertEquals(1_000L, resolved.startTime)
        assertEquals("e1", resolved.id)
        assertEquals(local.tags, resolved.tags)
        assertEquals(local.textNote, resolved.textNote)
        assertEquals(local.voiceNotePath, resolved.voiceNotePath)
    }

    /**
     * Поле, которое не спорно, облаком не перезаписывается: экран мог послать всё множество,
     * а локальная правка вне спора — данные пользователя, их нельзя терять молча.
     */
    @Test
    fun `field outside the dispute is not overwritten by the cloud`() {
        val resolved = resolvedEvent(conflict, local, SyncField.entries.toSet())

        assertEquals("Правка облака", resolved.title)
        assertEquals(1_000L, resolved.startTime)
    }

    @Test
    fun `empty choice keeps the local record untouched`() {
        val resolved = resolvedEvent(conflict, local, emptySet())

        assertEquals(local, resolved)
    }
}
