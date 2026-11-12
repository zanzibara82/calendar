package com.example.mycalendar.data.remote.yandex

import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Сериализация/парсинг событий в формат iCalendar (RFC 5545) для CalDAV.
 *
 * Поддерживаем подмножество полей доменной модели [Event]:
 *  - UID, SUMMARY, DESCRIPTION, DTSTART/DTEND (UTC, с суффиксом Z);
 *  - RRULE (только FREQ=DAILY/WEEKLY/MONTHLY/YEARLY — интервалы и концы не моделируем);
 *  - VALARM (напоминания, TRIGGER со знаком минус относительно начала);
 *  - LAST-MODIFIED — основа для сравнения, если провайдер его честно отдаёт.
 *
 * Метки события ([Event.tags]) и голосовые заметки НЕ синхронизируются
 * (файлы остаются на устройстве), как и для остальных провайдеров.
 */
object IcsMapper {

    private val UTC = TimeZone.getTimeZone("UTC")
    private val DT_FMT = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = UTC }
    private val DATE_FMT = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = UTC }

    // ---------- Сериализация ----------

    /** Собрать VCALENDAR с одним VEVENT из доменного [event]. [uid] — стабильный идентификатор. */
    fun toIcs(event: Event, uid: String, nowMillis: Long): String {
        val lines = mutableListOf<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:-//MyCalendar//EN"
        lines += "CALSCALE:GREGORIAN"
        lines += "BEGIN:VEVENT"
        lines += "UID:$uid"
        lines += "DTSTAMP:${fmtUtc(nowMillis)}"
        lines += "CREATED:${fmtUtc(nowMillis)}"
        lines += "LAST-MODIFIED:${fmtUtc(nowMillis)}"
        lines += "SUMMARY:${escape(event.title)}"
        event.description?.takeIf { it.isNotBlank() }?.let { lines += "DESCRIPTION:${escape(it)}" }
        lines += "DTSTART:${fmtUtc(event.startTime)}"
        lines += "DTEND:${fmtUtc(event.endTime)}"
        repeatToRrule(event.repeatRule)?.let { lines += "RRULE:$it" }
        for (min in event.reminderMinutes) {
            lines += "BEGIN:VALARM"
            lines += "TRIGGER:${reminderToTrigger(min)}"
            lines += "ACTION:DISPLAY"
            lines += "DESCRIPTION:${escape(event.title)}"
            lines += "END:VALARM"
        }
        lines += "END:VEVENT"
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n")
    }

    // ---------- Парсинг ----------

    /** Разобрать VCALENDAR -> [RemoteEvent]; null, если VEVENT не найден или нет обязательных полей. */
    fun fromIcs(ics: String): RemoteEvent? {
        var uid: String? = null
        var title: String? = null
        var description: String? = null
        var dtStart: Long? = null
        var dtEnd: Long? = null
        var rrule: String? = null
        var lastModified: Long? = null
        val reminders = mutableListOf<Int>()

        var inEvent = false
        var inAlarm = false

        for (raw in unfold(ics)) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when (line) {
                "BEGIN:VEVENT" -> inEvent = true
                "END:VEVENT" -> inEvent = false
                "BEGIN:VALARM" -> inAlarm = true
                "END:VALARM" -> inAlarm = false
            }
            if (!inEvent) continue
            val idx = line.indexOf(':')
            if (idx < 0) continue
            val name = line.substring(0, idx).substringBefore(';').uppercase(Locale.US)
            val value = line.substring(idx + 1)
            when (name) {
                "UID" -> if (!inAlarm) uid = value
                "SUMMARY" -> if (!inAlarm) title = unescape(value)
                "DESCRIPTION" -> if (!inAlarm) description = unescape(value)
                "DTSTART" -> if (!inAlarm) dtStart = parseUtc(value)
                "DTEND" -> if (!inAlarm) dtEnd = parseUtc(value)
                "RRULE" -> if (!inAlarm) rrule = value
                "LAST-MODIFIED" -> if (!inAlarm) lastModified = parseUtc(value)
                "TRIGGER" -> if (inAlarm) triggerToMinutes(value)?.let { reminders += it }
            }
        }

        if (uid == null || dtStart == null || dtEnd == null) return null
        return RemoteEvent(
            remoteId = uid,
            title = title ?: "",
            description = description,
            startTime = dtStart,
            endTime = dtEnd,
            reminderMinutes = reminders,
            repeatRule = rruleToRepeat(rrule),
            updatedAt = lastModified ?: dtStart,
        )
    }

    // ---------- Вспомогательное ----------

    private fun fmtUtc(epochMillis: Long): String {
        val c = Calendar.getInstance(UTC).apply { timeInMillis = epochMillis }
        return "%04d%02d%02dT%02d%02d%02dZ".format(
            Locale.US,
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
        )
    }

    private fun parseUtc(value: String): Long? {
        val v = value.trim()
        if (v.length == 8 && v.all { it.isDigit() }) {
            return runCatching { DATE_FMT.parse(v)?.time }.getOrNull()
        }
        val s = v.removeSuffix("Z")
        return runCatching { DT_FMT.parse(s)?.time }.getOrNull()
    }

    /** Раскрыть переносы строк iCalendar (строка, начинающаяся с пробела/таба, доКлеивается к предыдущей). */
    private fun unfold(ics: String): List<String> = buildList {
        for (raw in ics.split("\r\n", "\n")) {
            if (raw.startsWith(' ') || raw.startsWith('\t')) {
                val prev = lastOrNull()
                if (prev != null) this[lastIndex] = prev + raw.substring(1)
            } else {
                add(raw)
            }
        }
    }

    private fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(",", "\\,")
        .replace(";", "\\;")

    /**
     * Обратная сторона [escape]. Однопроходный разбор: захватываем обратный слэш
     * вместе со следующим символом, поэтому «\\n» (экранированный слэш + буква n)
     * никогда не превращается в перевод строки. \N (RFC 5545) тоже считаем переносом.
     */
    private fun unescape(text: String): String {
        return text.replace("""\\(.)""".toRegex()) { m ->
            when (val c = m.groupValues[1]) {
                "\\" -> "\\"
                "n", "N" -> "\n"
                ";" -> ";"
                "," -> ","
                else -> c
            }
        }
    }

    private fun repeatToRrule(rule: RepeatRule?): String? = when (rule) {
        RepeatRule.DAILY -> "FREQ=DAILY"
        RepeatRule.WEEKLY -> "FREQ=WEEKLY"
        RepeatRule.MONTHLY -> "FREQ=MONTHLY"
        RepeatRule.YEARLY -> "FREQ=YEARLY"
        null -> null
    }

    private fun rruleToRepeat(rrule: String?): RepeatRule? {
        val freq = rrule?.substringAfter("FREQ=", "")
            ?.substringBefore(";")
            ?.substringBefore(":")
            ?.uppercase(Locale.US)
        return when (freq) {
            "DAILY" -> RepeatRule.DAILY
            "WEEKLY" -> RepeatRule.WEEKLY
            "MONTHLY" -> RepeatRule.MONTHLY
            "YEARLY" -> RepeatRule.YEARLY
            else -> null
        }
    }

    private fun reminderToTrigger(min: Int): String = when {
        min <= 0 -> "-PT0M"
        min % 1440 == 0 -> "-P${min / 1440}D"
        min % 60 == 0 -> "-PT${min / 60}H"
        else -> "-PT${min}M"
    }

    private fun triggerToMinutes(trigger: String): Int? {
        val t = trigger.trim().removePrefix("-").removePrefix("+").uppercase(Locale.US)
        val hms = Regex("""PT(\d+)([HMS])""").find(t)
        if (hms != null) {
            val n = hms.groupValues[1].toIntOrNull() ?: return null
            return when (hms.groupValues[2]) {
                "H" -> n * 60
                "M" -> n
                "S" -> n / 60
                else -> null
            }
        }
        val days = Regex("""P(\d+)D""").find(t)
        if (days != null) {
            val n = days.groupValues[1].toIntOrNull() ?: return null
            return n * 1440
        }
        return null
    }
}
