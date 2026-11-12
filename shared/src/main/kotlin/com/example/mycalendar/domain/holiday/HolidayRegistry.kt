package com.example.mycalendar.domain.holiday

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import javax.inject.Inject

/**
 * Твёрдые даты праздников и правило выходного дня.
 *
 * Ключ таблицы — (месяц, день): год не участвует, поэтому високосное 29 февраля просто
 * отсутствует в наборе и не требует специальной ветки. Название — [HolidayKey], потому что
 * текст, который видит пользователь, остаётся ресурсом клиента (спека, §2): домен знает
 * праздник, но не его подпись.
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

    /** Ключ названия праздника, или null, если день не праздничный. */
    fun holidayKey(date: LocalDate): HolidayKey? = HOLIDAYS[monthDayOf(date)]

    /**
     * Метки всех дней диапазона включительно. Ключи есть только у помеченных дней, поэтому
     * карта остаётся маленькой: за календарный месяц — не больше пятнадцати записей (максимум
     * даёт январь: семь твёрдых дат плюс до десяти выходных, из которых два всегда совпадают
     * с праздником). Обходится ровно переданный диапазон, а вызывающий передаёт шире месяца:
     * сетка месяца — до сорока двух клеток, и дни соседних месяцев приходят в карту тоже.
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
        val HOLIDAYS: Map<MonthDay, HolidayKey> = mapOf(
            MonthDay.of(1, 1) to HolidayKey.NEW_YEAR_HOLIDAYS,
            MonthDay.of(1, 2) to HolidayKey.NEW_YEAR_HOLIDAYS,
            MonthDay.of(1, 3) to HolidayKey.NEW_YEAR_HOLIDAYS,
            MonthDay.of(1, 4) to HolidayKey.NEW_YEAR_HOLIDAYS,
            MonthDay.of(1, 5) to HolidayKey.NEW_YEAR_HOLIDAYS,
            MonthDay.of(1, 6) to HolidayKey.NEW_YEAR_HOLIDAYS,
            MonthDay.of(1, 7) to HolidayKey.CHRISTMAS_ORTHODOX,
            MonthDay.of(2, 14) to HolidayKey.VALENTINES,
            MonthDay.of(2, 23) to HolidayKey.DEFENDER_OF_FATHERLAND,
            MonthDay.of(3, 8) to HolidayKey.WOMENS_DAY,
            MonthDay.of(5, 1) to HolidayKey.SPRING_LABOR,
            MonthDay.of(5, 9) to HolidayKey.VICTORY_DAY,
            MonthDay.of(6, 1) to HolidayKey.CHILDRENS_DAY,
            MonthDay.of(6, 12) to HolidayKey.RUSSIA_DAY,
            MonthDay.of(10, 31) to HolidayKey.HALLOWEEN,
            MonthDay.of(11, 4) to HolidayKey.NATIONAL_UNITY,
            MonthDay.of(12, 25) to HolidayKey.CHRISTMAS_WEST,
            MonthDay.of(12, 31) to HolidayKey.NEW_YEAR_EVE,
        )
    }
}
