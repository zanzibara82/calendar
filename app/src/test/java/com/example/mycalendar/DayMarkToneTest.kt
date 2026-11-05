package com.example.mycalendar

import com.example.mycalendar.domain.holiday.DayMark
import com.example.mycalendar.presentation.theme.DarkDayHolidayContainer
import com.example.mycalendar.presentation.theme.DarkDayWeekendContainer
import com.example.mycalendar.presentation.theme.LightDayHolidayContainer
import com.example.mycalendar.presentation.theme.LightDayWeekendContainer
import com.example.mycalendar.presentation.theme.dayMarkContainer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тон дня собирается прямо из палитры: ни одного цвета, заданного по месту. Это то окно,
 * из которого Windows-версия копирует hex-значения.
 */
class DayMarkToneTest {

    @Test
    fun `weekend tone comes from the palette`() {
        assertEquals(DarkDayWeekendContainer, dayMarkContainer(DayMark.WEEKEND, dark = true))
        assertEquals(LightDayWeekendContainer, dayMarkContainer(DayMark.WEEKEND, dark = false))
    }

    @Test
    fun `holiday tone comes from the palette`() {
        assertEquals(DarkDayHolidayContainer, dayMarkContainer(DayMark.HOLIDAY, dark = true))
        assertEquals(LightDayHolidayContainer, dayMarkContainer(DayMark.HOLIDAY, dark = false))
    }
}
