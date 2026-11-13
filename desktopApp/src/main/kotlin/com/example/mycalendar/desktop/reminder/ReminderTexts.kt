package com.example.mycalendar.desktop.reminder

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Подпись плашки повторяет строки Android: «Начало в 09:30 (через 5 мин)».
 * Единицы короткие, как в `notification_reminder_text` и `minutes_short`
 * («мин», «ч», «д»), поэтому склонять их не нужно.
 */
internal fun reminderLabel(
    occurrenceStart: Long,
    minutes: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (minutes <= 0) return "Событие начинается"
    val time = Instant.ofEpochMilli(occurrenceStart).atZone(zone).format(TIME_FORMAT)
    return "Начало в $time (через ${durationLabel(minutes)})"
}

private fun durationLabel(minutes: Int): String = when {
    minutes >= MINUTES_PER_DAY -> "${minutes / MINUTES_PER_DAY} д"
    minutes >= MINUTES_PER_HOUR -> "${minutes / MINUTES_PER_HOUR} ч"
    else -> "$minutes мин"
}

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
