package com.example.mycalendar.desktop.reminder

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Отмена одного заказа. */
fun interface Cancellable {
    fun cancel()
}

/**
 * Отложенный запуск без привязки к конкретному таймеру: тесты подставляют
 * свой исполнитель и сами решают, когда «наступает» заказ.
 */
fun interface DelayScheduler {

    fun schedule(delayMillis: Long, action: () -> Unit): Cancellable

    /** Останавливает исполнитель; у фейков делать нечего. */
    fun shutdown() = Unit
}

/** Реальный таймер: один фоновый поток-демон, чтобы не держать процесс живым. */
class ExecutorDelayScheduler(
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mycalendar-reminders").apply { isDaemon = true }
    },
) : DelayScheduler {

    override fun schedule(delayMillis: Long, action: () -> Unit): Cancellable {
        val future = executor.schedule(Runnable { action() }, delayMillis, TimeUnit.MILLISECONDS)
        return Cancellable { future.cancel(false) }
    }

    override fun shutdown() {
        executor.shutdownNow()
    }
}
