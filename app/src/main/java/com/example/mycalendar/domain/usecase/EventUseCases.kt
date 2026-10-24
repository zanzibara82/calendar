package com.example.mycalendar.domain.usecase

import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.util.RepeatRuleHelper
import com.example.mycalendar.work.ReminderScheduler
import com.example.mycalendar.data.audio.VoiceNoteStorage
import java.io.File
import javax.inject.Inject

/**
 * Получение событий в диапазоне дат (для Month/Week/Day видов календаря).
 */
class ObserveEventsBetweenUseCase @Inject constructor(
    private val eventRepository: EventRepository,
) {
    operator fun invoke(from: Long, to: Long) = eventRepository.observeEventsBetween(from, to)
}

/**
 * Получение одного события (для экрана деталей и редактирования).
 */
class GetEventUseCase @Inject constructor(
    private val eventRepository: EventRepository,
) {
    operator fun invoke(id: String) = eventRepository.observeEvent(id)

    suspend fun once(id: String): Event? = eventRepository.getEvent(id)
}

/**
 * Валидация события перед сохранением.
 */
sealed interface EventValidation {
    data object Ok : EventValidation
    data class Error(val reason: Reason) : EventValidation

    enum class Reason { EMPTY_TITLE, END_BEFORE_START }
}

/**
 * Создание/редактирование события.
 *
 * Отвечает за:
 *  1. валидацию;
 *  2. перенос временного файла свежезаписанной голосовой заметки в постоянное хранилище;
 *  3. сохранение в Room (с пометкой pendingSync);
 *  4. перепланирование напоминаний через WorkManager.
 */
class SaveEventUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val reminderScheduler: ReminderScheduler,
    private val voiceNoteStorage: VoiceNoteStorage,
) {
    suspend operator fun invoke(event: Event, hasNewRecording: Boolean): Result<String> {
        // 1. Валидация
        if (event.title.isBlank()) {
            return Result.failure(IllegalArgumentException(EventValidation.Reason.EMPTY_TITLE.name))
        }
        if (event.endTime <= event.startTime) {
            return Result.failure(IllegalArgumentException(EventValidation.Reason.END_BEFORE_START.name))
        }

        // 2. Голосовая заметка: если только что записали — переносим temp-файл в файл события
        val voicePath = if (hasNewRecording && voiceNoteStorage.tempFile.exists()) {
            event.voiceNotePath?.let { File(it).takeIf { f -> f != voiceNoteStorage.tempFile }?.delete() }
            val target = voiceNoteStorage.fileFor(event.id)
            if (!voiceNoteStorage.tempFile.renameTo(target)) {
                // rename между томами невозможен — делаем копию
                voiceNoteStorage.tempFile.copyTo(target, overwrite = true)
                voiceNoteStorage.tempFile.delete()
            }
            target.absolutePath
        } else {
            event.voiceNotePath
        }

        // 3. Сохранение (updatedAt/pendingSync выставит репозиторий)
        val eventToSave = event.copy(voiceNotePath = voicePath)
        // Старые будильники снимаем ДО сохранения: после редактирования минуты
        // напоминаний могли измениться, и их надо перевыполнить заново
        eventRepository.getEvent(event.id)?.let { reminderScheduler.cancel(it) }
        eventRepository.saveFromUser(eventToSave)

        // 4. Точные будильники напоминаний
        reminderScheduler.schedule(eventToSave)
        return Result.success(eventToSave.id)
    }
}

/**
 * Удаление события: отмена напоминаний и tombstone.
 *
 * Файл голосовой заметки здесь НЕ удаляется: облако могло не принять удаление,
 * а потеря записи хуже отложенного файла. Файл снимается вместе со строкой БД
 * в [com.example.mycalendar.domain.repository.EventRepository.purge].
 */
class DeleteEventUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val reminderScheduler: ReminderScheduler,
) {
    suspend operator fun invoke(id: String) {
        val event = eventRepository.getEvent(id) ?: return
        reminderScheduler.cancel(event)
        eventRepository.markDeleted(id)
    }
}

/**
 * Разворачивание повторяющихся событий в конкретные вхождения
 * (start/end пары) для отображения в календаре.
 */
class ExpandOccurrencesUseCase @Inject constructor() {
    operator fun invoke(
        events: List<Event>,
        from: Long,
        to: Long,
    ): List<Pair<Event, Pair<Long, Long>>> = buildList {
        for (event in events) {
            for (occurrence in RepeatRuleHelper.occurrencesInRange(
                originalStart = event.startTime,
                originalEnd = event.endTime,
                rule = event.repeatRule,
                from = from,
                to = to,
            )) {
                add(event to occurrence)
            }
        }
    }.sortedBy { it.second.first }
}
