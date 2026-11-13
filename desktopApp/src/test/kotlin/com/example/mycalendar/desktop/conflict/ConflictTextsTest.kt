package com.example.mycalendar.desktop.conflict

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тексты разбора конфликтов повторяют формулировки Android: очередь, подписи полей и значения.
 * Форматтеры принимают зону явно, чтобы тест не зависел от часового пояса машины.
 */
class ConflictTextsTest {

    private val moscow = ZoneId.of("Europe/Moscow")
    private val base = SyncSnapshot(
        title = "Общий предок",
        startTime = at("2026-03-12T09:00"),
        endTime = at("2026-03-12T10:00"),
        reminderMinutes = listOf(10),
        description = "",
        repeatRule = null,
    )
    private val cloud = base.copy(
        title = "Правка облака",
        startTime = at("2026-03-12T11:30"),
        reminderMinutes = listOf(30),
        description = "описание из облака",
    )
    private val local = Event(
        id = "e1",
        title = "Правка пользователя",
        description = null,
        startTime = at("2026-03-12T09:00"),
        endTime = at("2026-03-12T10:00"),
        reminderMinutes = listOf(10),
        repeatRule = RepeatRule.DAILY,
        calendarProvider = CalendarProvider.YANDEX,
        remoteId = "uid-1",
    )

    private fun conflict(
        kind: ConflictKind = ConflictKind.CONTENT,
        contested: Set<SyncField> = setOf(SyncField.TITLE, SyncField.START_TIME),
    ) = SyncConflict(
        id = "c1",
        eventId = "e1",
        provider = CalendarProvider.YANDEX,
        remoteId = "uid-1",
        kind = kind,
        base = base,
        remote = cloud,
        contested = contested,
        detectedAt = at("2026-03-13T18:05"),
    )

    private fun at(iso: String): Long =
        java.time.LocalDateTime.parse(iso).atZone(moscow).toInstant().toEpochMilli()

    @Test
    fun `contested fields use russian plural forms`() {
        assertEquals("1 спорное поле", contestedFieldsLabel(1))
        assertEquals("3 спорных поля", contestedFieldsLabel(3))
        assertEquals("5 спорных полей", contestedFieldsLabel(5))
        assertEquals("21 спорное поле", contestedFieldsLabel(21))
        assertEquals("11 спорных полей", contestedFieldsLabel(11))
        assertEquals("0 спорных полей", contestedFieldsLabel(0))
    }

    @Test
    fun `content conflict summary shows the number of contested fields`() {
        assertEquals("2 спорных поля", conflictSummary(conflict()))
    }

    @Test
    fun `deletion conflicts describe what happened`() {
        assertEquals(
            "Событие удалено в облаке, локально есть ваши правки",
            conflictSummary(conflict(kind = ConflictKind.REMOTE_DELETED, contested = emptySet())),
        )
        assertEquals(
            "Событие удалено локально, а в облаке есть версия",
            conflictSummary(conflict(kind = ConflictKind.LOCAL_DELETED, contested = setOf(SyncField.TITLE))),
        )
    }

    @Test
    fun `detail help explains the field choice`() {
        assertEquals(
            "Выберите, какое значение оставить; непересекающиеся правки приложение объединило само",
            conflictHelp(ConflictKind.CONTENT),
        )
        assertEquals(
            "Событие удалено в облаке, локально есть ваши правки",
            conflictHelp(ConflictKind.REMOTE_DELETED),
        )
        assertEquals(
            "Событие удалено локально, а в облаке есть версия",
            conflictHelp(ConflictKind.LOCAL_DELETED),
        )
    }

    @Test
    fun `field labels match android`() {
        assertEquals("Название", fieldLabel(SyncField.TITLE))
        assertEquals("Описание", fieldLabel(SyncField.DESCRIPTION))
        assertEquals("Начало", fieldLabel(SyncField.START_TIME))
        assertEquals("Конец", fieldLabel(SyncField.END_TIME))
        assertEquals("Напоминания", fieldLabel(SyncField.REMINDER_MINUTES))
        assertEquals("Повтор", fieldLabel(SyncField.REPEAT_RULE))
    }

    @Test
    fun `field values fall back to placeholders and format dates in moscow zone`() {
        assertEquals("Правка пользователя", localValue(SyncField.TITLE, local, moscow))
        assertEquals("без описания", localValue(SyncField.DESCRIPTION, local, moscow))
        assertEquals("12 марта 2026, 09:00", localValue(SyncField.START_TIME, local, moscow))
        assertEquals("Каждый день", localValue(SyncField.REPEAT_RULE, local, moscow))
        assertEquals("10 мин", localValue(SyncField.REMINDER_MINUTES, local, moscow))

        assertEquals("Правка облака", cloudValue(SyncField.TITLE, conflict(), moscow))
        assertEquals("описание из облака", cloudValue(SyncField.DESCRIPTION, conflict(), moscow))
        assertEquals("12 марта 2026, 11:30", cloudValue(SyncField.START_TIME, conflict(), moscow))
        assertEquals("Без повтора", cloudValue(SyncField.REPEAT_RULE, conflict(), moscow))
        assertEquals("30 мин", cloudValue(SyncField.REMINDER_MINUTES, conflict(), moscow))
    }

    @Test
    fun `reminders and repeats have russian labels`() {
        assertEquals("без напоминаний", remindersLabel(emptyList()))
        assertEquals("10 мин, 30 мин", remindersLabel(listOf(30, 10)))
        assertEquals("Без повтора", repeatLabel(null))
        assertEquals("Каждый день", repeatLabel(RepeatRule.DAILY))
        assertEquals("Каждую неделю", repeatLabel(RepeatRule.WEEKLY))
        assertEquals("Каждый месяц", repeatLabel(RepeatRule.MONTHLY))
        assertEquals("Каждый год", repeatLabel(RepeatRule.YEARLY))
    }

    @Test
    fun `detected time uses the short android format`() {
        assertEquals("13 мар 2026, 18:05", detectedAtText(conflict().detectedAt, moscow))
    }

    @Test
    fun `titles fall back to a placeholder`() {
        assertEquals("Событие без названия", displayTitle("   "))
        assertEquals("Повестка", displayTitle("Повестка"))
    }

    @Test
    fun `notices explain why a choice was not applied`() {
        assertEquals(
            "Конфликт изменился — откройте его заново из очереди",
            noticeText(ConflictsController.Notice.STALE),
        )
        assertEquals(
            "Конфликт больше не актуален",
            noticeText(ConflictsController.Notice.NO_LOCAL_ROW),
        )
    }
}
