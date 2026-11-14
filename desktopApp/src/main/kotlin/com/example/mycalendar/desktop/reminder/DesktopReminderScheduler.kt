package com.example.mycalendar.desktop.reminder

import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.reminder.ReminderScheduler

/** Сработавшее напоминание: по нему окно строит плашку и выбирает сигнал. */
data class DueReminder(
    val event: Event,
    val minutes: Int,
    val occurrenceStart: Long,
)

/**
 * Расписание напоминаний desktop-клиента.
 *
 * Заказы живут в памяти: напоминания приходят, пока программа запущена (в том числе
 * свёрнутой в трей). После перезапуска окно заново ставит в очередь ближайшие события,
 * а прошедшие напоминания ещё не начавшихся событий срабатывают сразу. Повторяющееся
 * событие после срабатывания заказывает следующее вхождение — та же цепочка, что
 * у будильников Android.
 *
 * «Отложить» — разовый заказ через [SNOOZE_MINUTES] минут с той же записью вхождения,
 * но с меткой [SNOOZE_MINUTES_MARKER]: подпись становится «Событие начинается», как
 * у отсроченного уведомления Android. Цепочку повтора отложенный заказ не трогает —
 * она уже поставлена в момент первого срабатывания.
 */
class DesktopReminderScheduler(
    private val onReminder: (DueReminder) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timer: DelayScheduler = ExecutorDelayScheduler(),
) : ReminderScheduler {

    private val lock = Any()
    private val jobs = mutableMapOf<String, MutableList<Cancellable>>()

    override fun schedule(event: Event) {
        cancel(event)
        val now = clock()
        ReminderPlanner.plan(event, now).forEach { planned -> scheduleJob(event, planned) }
    }

    override fun cancel(event: Event) {
        val pending = synchronized(lock) { jobs.remove(event.id) }.orEmpty()
        pending.forEach { it.cancel() }
    }

    /** Заказывает то же напоминание ещё раз — кнопка «Отложить» в плашке. */
    fun snooze(due: DueReminder, delayMinutes: Int = SNOOZE_MINUTES) {
        scheduleOneOff(due.event.id, delayMinutes * MINUTE_MILLIS) {
            onReminder(DueReminder(due.event, SNOOZE_MINUTES_MARKER, due.occurrenceStart))
        }
    }

    /** Заново ставит в очередь набор событий — после загрузки хранилища при старте окна. */
    fun rescheduleAll(events: List<Event>) {
        events.forEach(::schedule)
    }

    fun shutdown() {
        val pending = synchronized(lock) {
            val all = jobs.values.flatten()
            jobs.clear()
            all
        }
        pending.forEach { it.cancel() }
        timer.shutdown()
    }

    private fun scheduleJob(event: Event, planned: PlannedReminder) {
        scheduleOneOff(event.id, planned.triggerAt - clock()) {
            onReminder(DueReminder(event, planned.minutes, planned.occurrenceStart))
            if (event.repeatRule != null) {
                ReminderPlanner.planNext(event, planned.occurrenceStart, clock())
                    .forEach { next -> scheduleJob(event, next) }
            }
        }
    }

    /** Заказ под ключом события: `cancel(event)` снимет и обычные, и отложенные напоминания. */
    private fun scheduleOneOff(eventId: String, delayMillis: Long, action: () -> Unit) {
        val ref = JobRef()
        ref.cancellable = timer.schedule(delayMillis.coerceAtLeast(0L)) {
            synchronized(lock) { jobs[eventId]?.remove(ref.cancellable) }
            action()
        }
        ref.cancellable?.let { cancellable ->
            synchronized(lock) { jobs.getOrPut(eventId) { mutableListOf() } += cancellable }
        }
    }

    /** Действие таймера ссылается на свой заказ, чтобы снять себя из очереди после срабатывания. */
    private class JobRef {
        @Volatile
        var cancellable: Cancellable? = null
    }

    companion object {
        /** Отсрочка кнопки «Отложить» — тот же интервал, что SNOOZE_MINUTES на Android. */
        const val SNOOZE_MINUTES = 10

        /** Метка отложенного напоминания: подпись «Событие начинается», как на Android. */
        const val SNOOZE_MINUTES_MARKER = -1
    }
}

private const val MINUTE_MILLIS = 60_000L
