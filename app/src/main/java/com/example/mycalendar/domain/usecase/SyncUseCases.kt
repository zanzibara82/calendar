package com.example.mycalendar.domain.usecase

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.repository.SyncRepository
import com.example.mycalendar.work.ReminderScheduler
import javax.inject.Inject

/**
 * Запуск синхронизации «здесь и сейчас» (кнопка в настройках / после входа).
 *
 * Календари подключаются по отдельности, поэтому есть и общий вызов
 * (все подключённые), и точечный — только один провайдер.
 */
class SyncEventsUseCase @Inject constructor(
    private val syncRepository: SyncRepository,
) {
    /** Все подключённые календари. */
    suspend operator fun invoke() = syncRepository.syncNow()

    /** Только указанный календарь. */
    suspend fun forProvider(provider: CalendarProvider) = syncRepository.syncProvider(provider)
}

/**
 * Перепланирование напоминаний для всех предстоящих событий.
 * Используется после перезагрузки устройства (BootReceiver)
 * и после массовых изменений (например, после синхронизации).
 */
class RescheduleRemindersUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val reminderScheduler: ReminderScheduler,
) {
    suspend operator fun invoke() {
        val now = System.currentTimeMillis()
        eventRepository.getUpcomingEvents(now).forEach { event ->
            reminderScheduler.schedule(event)
        }
    }
}
