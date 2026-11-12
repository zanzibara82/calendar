package com.example.mycalendar.desktop.calendar

import com.example.mycalendar.domain.holiday.HolidayKey
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каждому ключу праздника обязана соответствовать подпись: пропуск в `when`
 * компилятор ловит, а пустую строку — нет.
 */
class HolidayTitlesTest {

    @Test
    fun `every holiday key has a title`() {
        HolidayKey.entries.forEach { key ->
            assertTrue("нет подписи для $key", holidayTitle(key).isNotBlank())
        }
    }
}
