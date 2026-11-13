package com.example.mycalendar.desktop.reminder

import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.reminder.ReminderOccurrenceHelper
import com.example.mycalendar.domain.util.RepeatRuleHelper

/** Заказ напоминания: когда сработать и к какому вхождению он относится. */
internal data class PlannedReminder(
    val minutes: Int,
    val triggerAt: Long,
    val occurrenceStart: Long,
)

/**
 * Арифметика расписания без таймера и UI — правила повторяют Android:
 *  - берётся ближайшее вхождение повторяющегося события или само событие, если оно в будущем;
 *  - время напоминания уже прошло, а событие ещё не началось — сработать сразу
 *    (небольшая задержка, чтобы окно успело показаться вместе с плашкой);
 *  - событие началось или одиночное событие в прошлом — заказов нет;
 *  - у события без минут напоминаний заказов нет.
 */
internal object ReminderPlanner {

    /** Отсрочка «сработать сразу»: как на Android, 1,5 секунды. */
    const val IMMEDIATE_DELAY_MILLIS = 1_500L

    fun plan(event: Event, now: Long): List<PlannedReminder> {
        val occurrenceStart = ReminderOccurrenceHelper
            .closestOccurrenceStart(event.startTime, event.repeatRule, now)
            ?: return emptyList()
        return reminders(event, occurrenceStart, now)
    }

    /** Заказы следующего вхождения — продолжение цепочки после срабатывания. */
    fun planNext(event: Event, previousStart: Long, now: Long): List<PlannedReminder> {
        val rule = event.repeatRule ?: return emptyList()
        val occurrenceStart = RepeatRuleHelper.nextOccurrenceAfter(event.startTime, rule, previousStart)
            ?: return emptyList()
        return reminders(event, occurrenceStart, now)
    }

    private fun reminders(event: Event, occurrenceStart: Long, now: Long): List<PlannedReminder> =
        event.reminderMinutes.distinct().mapNotNull { minutes ->
            val triggerAt = occurrenceStart - minutes * MILLIS_PER_MINUTE
            when {
                triggerAt > now -> PlannedReminder(minutes, triggerAt, occurrenceStart)
                occurrenceStart > now ->
                    PlannedReminder(minutes, now + IMMEDIATE_DELAY_MILLIS, occurrenceStart)
                else -> null
            }
        }

    private const val MILLIS_PER_MINUTE = 60_000L
}
