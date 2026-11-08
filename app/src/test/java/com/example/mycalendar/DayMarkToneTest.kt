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
 * Тон дня собирается из констант палитры: ассерты сравнивают результат с теми же
 * именованными цветами, что объявлены в палитре. Это то окно, из которого Windows-версия
 * копирует hex-значения.
 *
 * Известное ограничение, а не проверка: если кто-то перенесёт hex прямо в `dayMarkContainer`
 * в `Theme.kt`, значение совпадёт с константой палитры и тест не покраснеет. Здесь
 * проверяется источник тона, а не способ его записи.
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
