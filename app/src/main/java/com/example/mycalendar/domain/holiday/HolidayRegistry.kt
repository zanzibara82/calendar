package com.example.mycalendar.domain.holiday

import com.example.mycalendar.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import javax.inject.Inject

/**
 * Твёрдые даты праздников и правило выходного дня.
 *
 * Ключ таблицы — (месяц, день): год не участвует, поэтому високосное 29 февраля просто
 * отсутствует в наборе и не требует специальной ветки. Название — id строки, потому что
 * текст, который видит пользователь, остаётся ресурсом (спека, §2): домен знает id, но не
 * локализацию.
 *
 * Вне набора сознательно: переносы праздников, пасхальный круг и лунные даты (спека, §1).
 */
class HolidayRegistry @Inject constructor() {

    /** HOLIDAY важнее WEEKEND: 9 мая, выпавшее на субботу, остаётся праздником. */
    fun markFor(date: LocalDate): DayMark? {
        if (monthDayOf(date) in HOLIDAYS) return DayMark.HOLIDAY
        val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        return if (isWeekend) DayMark.WEEKEND else null
    }

    /** Ресурс названия праздника, или null, если день не праздничный. */
    fun holidayTitleRes(date: LocalDate): Int? = HOLIDAYS[monthDayOf(date)]

    /**
     * Метки всех дней диапазона включительно. Ключи есть только у помеченных дней, поэтому
     * карта остаётся маленькой: за месяц — не больше сорока двух записей.
     */
    fun marksBetween(from: LocalDate, to: LocalDate): Map<LocalDate, DayMark> {
        val marks = LinkedHashMap<LocalDate, DayMark>()
        var day = from
        while (!day.isAfter(to)) {
            markFor(day)?.let { marks[day] = it }
            day = day.plusDays(1)
        }
        return marks
    }

    /**
     * Ключ таблицы: у java.time.LocalDate в android.jar (libcore) нет публичного
     * getMonthDay(), поэтому берём MonthDay.from — это ровно то, что делает JDK-версия.
     */
    private fun monthDayOf(date: LocalDate): MonthDay = MonthDay.from(date)

    private companion object {
        val HOLIDAYS: Map<MonthDay, Int> = mapOf(
            MonthDay.of(1, 1) to R.string.holiday_new_year_holidays,
            MonthDay.of(1, 2) to R.string.holiday_new_year_holidays,
            MonthDay.of(1, 3) to R.string.holiday_new_year_holidays,
            MonthDay.of(1, 4) to R.string.holiday_new_year_holidays,
            MonthDay.of(1, 5) to R.string.holiday_new_year_holidays,
            MonthDay.of(1, 6) to R.string.holiday_new_year_holidays,
            MonthDay.of(1, 7) to R.string.holiday_christmas_orthodox,
            MonthDay.of(2, 14) to R.string.holiday_valentines,
            MonthDay.of(2, 23) to R.string.holiday_defender_of_fatherland,
            MonthDay.of(3, 8) to R.string.holiday_womens_day,
            MonthDay.of(5, 1) to R.string.holiday_spring_labor,
            MonthDay.of(5, 9) to R.string.holiday_victory_day,
            MonthDay.of(6, 1) to R.string.holiday_childrens_day,
            MonthDay.of(6, 12) to R.string.holiday_russia_day,
            MonthDay.of(10, 31) to R.string.holiday_halloween,
            MonthDay.of(11, 4) to R.string.holiday_national_unity,
            MonthDay.of(12, 25) to R.string.holiday_christmas_west,
            MonthDay.of(12, 31) to R.string.holiday_new_year_eve,
        )
    }
}
