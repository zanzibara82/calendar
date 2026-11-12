package com.example.mycalendar.desktop.calendar

import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.util.RepeatRuleHelper
import java.time.LocalDate
import java.time.ZoneId

/** Вхождение события, задевающее день сетки; начало и конец — фактические, без обрезки днём. */
data class DayOccurrence(
    val event: Event,
    val start: Long,
    val end: Long,
)

/**
 * События выбранного дня: повторения разворачиваются, многодневное событие попадает
 * в каждый задетый день и сохраняет своё настоящее начало и конец — панель дня
 * показывает открытую границу, а не поддельное «00:00».
 */
fun dayOccurrences(
    events: List<Event>,
    date: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): List<DayOccurrence> {
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return events
        .flatMap { event ->
            RepeatRuleHelper
                .occurrencesInRange(event.startTime, event.endTime, event.repeatRule, dayStart, dayEnd - 1)
                .filter { (start, end) -> start < dayEnd && end > dayStart }
                .map { (start, end) -> DayOccurrence(event, start, end) }
        }
        .sortedBy { it.start }
}
