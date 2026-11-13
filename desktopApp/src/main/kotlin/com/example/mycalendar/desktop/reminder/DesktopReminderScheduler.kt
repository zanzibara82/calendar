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
        val delay = (planned.triggerAt - clock()).coerceAtLeast(0L)
        val ref = JobRef()
        ref.cancellable = timer.schedule(delay) {
            synchronized(lock) { jobs[event.id]?.remove(ref.cancellable) }
            onReminder(DueReminder(event, planned.minutes, planned.occurrenceStart))
            if (event.repeatRule != null) {
                ReminderPlanner.planNext(event, planned.occurrenceStart, clock())
                    .forEach { next -> scheduleJob(event, next) }
            }
        }
        ref.cancellable?.let { cancellable ->
            synchronized(lock) { jobs.getOrPut(event.id) { mutableListOf() } += cancellable }
        }
    }

    /** Действие таймера ссылается на свой заказ, чтобы снять себя из очереди после срабатывания. */
    private class JobRef {
        @Volatile
        var cancellable: Cancellable? = null
    }
}
