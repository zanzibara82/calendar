package com.example.mycalendar.desktop.calendar

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.util.RepeatRuleHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Цвета точек событий по дням сетки: дата клетки -> провайдеры её событий по времени начала.
 *
 * Повторяющиеся события разворачиваются в вхождения, многодневные попадают в каждую
 * задетую клетку. Событие, кончающееся ровно в полночь, следующий день не закрашивает.
 */
fun eventsByDay(
    events: List<Event>,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<LocalDate, List<CalendarProvider>> {
    val fromMillis = from.atStartOfDay(zone).toInstant().toEpochMilli()
    val toMillis = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    val dots = mutableMapOf<LocalDate, MutableList<Pair<Long, CalendarProvider>>>()

    events.forEach { event ->
        RepeatRuleHelper
            .occurrencesInRange(event.startTime, event.endTime, event.repeatRule, fromMillis, toMillis)
            .forEach { (start, end) ->
                var day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate().coerceAtLeast(from)
                val lastDay = Instant.ofEpochMilli(end).atZone(zone).toLocalDate().coerceAtMost(to)
                while (!day.isAfter(lastDay)) {
                    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
                    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    if (dayStart < end && dayEnd > start) {
                        dots.getOrPut(day) { mutableListOf() } += start to event.calendarProvider
                    }
                    day = day.plusDays(1)
                }
            }
    }

    return dots.mapValues { (_, occurrences) ->
        occurrences.sortedBy { it.first }.map { it.second }
    }
}
