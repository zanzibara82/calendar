package com.example.mycalendar.domain.reminder

import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.util.RepeatRuleHelper

/**
 * К какому вхождению события привязывается напоминание.
 *
 * Общее правило для Android и desktop: ещё не начавшееся событие — это само первое
 * вхождение (шаг повтора от `startTime` увёл бы к следующему и потерял его);
 * начавшееся повторяющееся — ближайшее вхождение после текущего момента;
 * начавшееся одиночное — напоминать не о чем.
 */
object ReminderOccurrenceHelper {

    /** Epoch millis вхождения или null, если напоминать уже не о чем. */
    fun closestOccurrenceStart(startTime: Long, repeatRule: RepeatRule?, now: Long): Long? =
        startTime.takeIf { it > now }
            ?: RepeatRuleHelper.nextOccurrenceAfter(startTime, repeatRule, now - 1)
}
