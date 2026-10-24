package com.example.mycalendar.domain.usecase

import com.example.mycalendar.data.audio.VoiceNoteStorage
import com.example.mycalendar.data.audio.VoiceRecorder
import com.example.mycalendar.domain.repository.EventRepository
import java.io.File
import javax.inject.Inject

/**
 * Старт записи голосовой заметки (AAC/M4A через MediaRecorder).
 * Запись идёт во временный файл; перенос в файл события происходит в [SaveEventUseCase].
 */
class StartVoiceRecordingUseCase @Inject constructor(
    private val voiceRecorder: VoiceRecorder,
) {
    operator fun invoke(): Result<Unit> = voiceRecorder.start()
}

/**
 * Остановка записи. Возвращает файл записи (temp) или null, если ничего не записано.
 */
class StopVoiceRecordingUseCase @Inject constructor(
    private val voiceRecorder: VoiceRecorder,
) {
    operator fun invoke(): File? = voiceRecorder.stop()
}

/**
 * Отмена текущей записи (файл удаляется).
 */
class DiscardVoiceRecordingUseCase @Inject constructor(
    private val voiceRecorder: VoiceRecorder,
) {
    operator fun invoke() = voiceRecorder.discard()
}

/**
 * Удаление сохранённой голосовой заметки события (файл + путь в БД).
 */
class DeleteVoiceNoteUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val voiceNoteStorage: VoiceNoteStorage,
) {
    suspend operator fun invoke(eventId: String) {
        val event = eventRepository.getEvent(eventId) ?: return
        // Удаляем и путь из строки, и файл события в стандартном хранилище. Отказ виден в логе
        // data-слоя; путь из строки снимается в любом случае, иначе событие ссылалось бы на мёртвый файл.
        event.voiceNotePath?.let { path -> voiceNoteStorage.deleteFile(path) }
        voiceNoteStorage.deleteFile(voiceNoteStorage.fileFor(eventId).absolutePath)
        eventRepository.saveFromUser(event.copy(voiceNotePath = null))
    }
}
