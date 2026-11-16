package com.example.mycalendar.domain.util

import com.example.mycalendar.domain.model.RepeatRule

/**
 * Кодирование простых правил повтора в RRULE и обратно.
 *
 * Домен моделирует только частоту: INTERVAL, COUNT/UNTIL и BYDAY не поддерживаются
 * и при разборе игнорируются — так же, как в CalDAV-источнике.
 */
object RruleCodec {

    fun toRrule(rule: RepeatRule?): String? = when (rule) {
        RepeatRule.DAILY -> "FREQ=DAILY"
        RepeatRule.WEEKLY -> "FREQ=WEEKLY"
        RepeatRule.MONTHLY -> "FREQ=MONTHLY"
        RepeatRule.YEARLY -> "FREQ=YEARLY"
        null -> null
    }

    fun fromRrule(rrule: String?): RepeatRule? {
        if (rrule == null) return null
        val freq = rrule.substringAfter("FREQ=", "")
            .substringBefore(';')
            .substringBefore(':')
            .trim()
            .uppercase()
        return runCatching { RepeatRule.valueOf(freq) }.getOrNull()
    }
}
