package com.example.mycalendar

import com.example.mycalendar.data.remote.yandex.IcsMapper
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Проверка сериализации/парсинга iCalendar (RFC 5545) — самая «хрупкая» часть
 * CalDAV-клиента. Гоняем событие туда-обратно и сверяем поля.
 */
class IcsMapperTest {

    private val event = Event(
        id = "local-1",
        title = "Встреча с ; Ивановым, Петром",
        description = "Обсудить \\ план \\n и точки",
        startTime = 1_700_000_000_000L, // 2023-11-14T22:13:20Z
        endTime = 1_700_003_400_000L,   // +90 мин
        reminderMinutes = listOf(5, 10, 60, 1440),
        repeatRule = RepeatRule.WEEKLY,
    )

    @Test
    fun `round-trip preserves core fields`() {
        val uid = "test-uid-123"
        val ics = IcsMapper.toIcs(event, uid, nowMillis = 1_699_999_000_000L)
        val remote = IcsMapper.fromIcs(ics)

        assertNotNull(remote)
        assertEquals(uid, remote!!.remoteId)
        assertEquals(event.title, remote.title)
        assertEquals(event.description, remote.description)
        assertEquals(event.startTime, remote.startTime)
        assertEquals(event.endTime, remote.endTime)
        assertEquals(RepeatRule.WEEKLY, remote.repeatRule)
    }

    @Test
    fun `special characters are escaped and unescaped`() {
        val ics = IcsMapper.toIcs(event, "u", nowMillis = 0L)
        // В ICS спецсимволы должны быть экранированы обратным слэшем
        assert(ics.contains("Встреча с \\; Ивановым\\, Петром")) {
            "SUMMARY должен быть экранирован:\n$ics"
        }
        assert(ics.contains("Обсудить \\\\ план \\\\n и точки")) {
            "DESCRIPTION должен быть экранирован:\n$ics"
        }
    }

    @Test
    fun `reminders map to VALARM triggers and back`() {
        val ics = IcsMapper.toIcs(event, "u", nowMillis = 0L)
        assert(ics.contains("TRIGGER:-PT5M")) { ics }
        assert(ics.contains("TRIGGER:-PT10M")) { ics }
        assert(ics.contains("TRIGGER:-PT1H")) { ics }   // 60 мин
        assert(ics.contains("TRIGGER:-P1D")) { ics }    // 1440 мин

        val remote = IcsMapper.fromIcs(ics)!!
        assertEquals(listOf(5, 10, 60, 1440), remote.reminderMinutes.sorted())
    }

    @Test
    fun `RRULE round-trips`() {
        for (rule in RepeatRule.values()) {
            val e = event.copy(repeatRule = rule)
            val remote = IcsMapper.fromIcs(IcsMapper.toIcs(e, "u", nowMillis = 0L))!!
            assertEquals(rule, remote.repeatRule)
        }
    }

    @Test
    fun `empty ics yields no event`() {
        assertNull(IcsMapper.fromIcs("BEGIN:VCALENDAR\nEND:VCALENDAR"))
    }
}
