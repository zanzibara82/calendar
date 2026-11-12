package com.example.mycalendar.domain.util

import com.example.mycalendar.domain.model.RepeatRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Расчёт вхождений повторяющихся событий.
 *
 * Событие хранится один раз (мастер-запись с originalStartTime), а все его
 * вхождения вычисляются на лету — так же, как "primary events" в Google Calendar.
 * Часовой пояс устройства применяется в момент расчёта.
 */
object RepeatRuleHelper {

    /**
     * Следующее вхождение события строго после момента [after].
     * @param originalStart время начала мастер-записи (первого вхождения).
     * @return epoch millis или null, если правила повторения нет.
     */
    fun nextOccurrenceAfter(originalStart: Long, rule: RepeatRule?, after: Long): Long? {
        if (rule == null) return null
        val zone = ZoneId.systemDefault()
        var current = Instant.ofEpochMilli(originalStart).atZone(zone).toLocalDateTime()
        val afterDateTime = Instant.ofEpochMilli(after).atZone(zone).toLocalDateTime()
        // Защита от зацикливания: максимум 5000 шагов (~13 лет для yearly)
        repeat(5000) {
            current = step(current, rule)
            if (current.isAfter(afterDateTime)) {
                return current.atZone(zone).toInstant().toEpochMilli()
            }
        }
        return null
    }

    /**
     * Все вхождения события (по epoch millis), попадающие в диапазон [from, to].
     * Одиночные события проверяются простым условием.
     */
    fun occurrencesInRange(
        originalStart: Long,
        originalEnd: Long,
        rule: RepeatRule?,
        from: Long,
        to: Long,
    ): List<Pair<Long, Long>> {
        if (rule == null) {
            return if (originalStart <= to && originalEnd >= from) {
                listOf(originalStart to originalEnd)
            } else {
                emptyList()
            }
        }
        val zone = ZoneId.systemDefault()
        val fromDateTime = Instant.ofEpochMilli(from).atZone(zone).toLocalDateTime()
        val toDateTime = Instant.ofEpochMilli(to).atZone(zone).toLocalDateTime()

        val duration = originalEnd - originalStart
        var start = Instant.ofEpochMilli(originalStart).atZone(zone).toLocalDateTime()
        val result = mutableListOf<Pair<Long, Long>>()
        // Идём от первого вхождения, пока не выйдем за правую границу диапазона
        repeat(5000) {
            val startMillis = start.atZone(zone).toInstant().toEpochMilli()
            val endMillis = startMillis + duration
            if (startMillis > to) return result
            if (endMillis >= from) result += startMillis to endMillis
            start = step(start, rule)
        }
        return result
    }

    /** Сдвиг момента на один шаг правила повторения. */
    private fun step(dateTime: LocalDateTime, rule: RepeatRule): LocalDateTime = when (rule) {
        RepeatRule.DAILY -> dateTime.plusDays(1)
        RepeatRule.WEEKLY -> dateTime.plusWeeks(1)
        RepeatRule.MONTHLY -> plusMonthClamped(dateTime)
        RepeatRule.YEARLY -> plusYearClamped(dateTime)
    }

    /**
     * Прибавление месяца: java.time сам "зажимает" день месяца
     * (31 января -> 28/29 февраля), поведение фиксируем явно.
     */
    private fun plusMonthClamped(dateTime: LocalDateTime): LocalDateTime =
        LocalDateTime.of(dateTime.toLocalDate().plusMonths(1), dateTime.toLocalTime())

    private fun plusYearClamped(dateTime: LocalDateTime): LocalDateTime {
        // 29 февраля -> 28 февраля в невисокосный год
        val day = minOf(dateTime.dayOfMonth, LocalDate.of(dateTime.year + 1, dateTime.month, 1).lengthOfMonth())
        return LocalDateTime.of(LocalDate.of(dateTime.year + 1, dateTime.month, day), dateTime.toLocalTime())
    }

    /** Человекочитаемая подпись для UI уже хранится в strings.xml; тут — серийный формат. */
    fun toRuleName(rule: RepeatRule): String = rule.name.lowercase()

    fun fromRuleName(name: String?): RepeatRule? =
        name?.let { runCatching { RepeatRule.valueOf(it.uppercase()) }.getOrNull() }
}
