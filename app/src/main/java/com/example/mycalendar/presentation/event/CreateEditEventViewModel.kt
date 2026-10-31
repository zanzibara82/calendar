package com.example.mycalendar.presentation.event

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mycalendar.data.audio.VoicePlayer
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.usecase.DeleteVoiceNoteUseCase
import com.example.mycalendar.domain.usecase.DiscardVoiceRecordingUseCase
import com.example.mycalendar.domain.usecase.GetEventUseCase
import com.example.mycalendar.domain.usecase.SaveEventUseCase
import com.example.mycalendar.domain.usecase.StartVoiceRecordingUseCase
import com.example.mycalendar.domain.usecase.StopVoiceRecordingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * Состояние экрана создания/редактирования события.
 */
data class EditEventUiState(
    val isLoading: Boolean = true,
    val isExisting: Boolean = false,
    val title: String = "",
    val description: String = "",
    /** Выбранные метки: День рождения / Свидание / Кино. */
    val tags: Set<EventTag> = emptySet(),
    val startDate: LocalDate = LocalDate.now(),
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endDate: LocalDate = LocalDate.now(),
    val endTime: LocalTime = LocalTime.of(10, 0),
    /** Выбранные напоминания в минутах: 5/10/15/30/60/1440. */
    val reminders: Set<Int> = emptySet(),
    val repeatRule: RepeatRule? = null,
    /** Путь к сохранённой или временной записи голосовой заметки. */
    val voiceNotePath: String? = null,
    /** Записали новую заметку в этом сеансе (SaveEventUseCase перенесёт temp-файл). */
    val hasNewRecording: Boolean = false,
    val isRecording: Boolean = false,
    val recordSeconds: Int = 0,
    // Плеер
    val isPlaying: Boolean = false,
    val playPositionMs: Int = 0,
    val playDurationMs: Int = 0,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val validationError: String? = null,
)

/**
 * ViewModel создания/редактирования события.
 *
 * Отвечает за поля события, выбор напоминаний и повтора, запись/воспроизведение
 * голосовой заметки и сохранение через SaveEventUseCase.
 */
@HiltViewModel
class CreateEditEventViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getEvent: GetEventUseCase,
    private val saveEvent: SaveEventUseCase,
    private val startRecording: StartVoiceRecordingUseCase,
    private val stopRecording: StopVoiceRecordingUseCase,
    private val discardRecording: DiscardVoiceRecordingUseCase,
    private val deleteVoiceNote: DeleteVoiceNoteUseCase,
    private val voicePlayer: VoicePlayer,
) : ViewModel() {

    private val eventId: String? = savedStateHandle["eventId"]

    /**
     * День, выбранный в календаре перед нажатием «Новое событие».
     * Подставляется в даты нового события; при редактировании даты берутся
     * из самого события.
     */
    private val preselectedDate: LocalDate? = savedStateHandle.get<String>("date")
        ?.let { LocalDate.parse(it) }

    /** Оригинал события при редактировании: сохраняем provider/remoteId для синхронизации. */
    private var originalEvent: Event? = null

    private val _uiState = MutableStateFlow(
        EditEventUiState(
            startDate = preselectedDate ?: LocalDate.now(),
            endDate = preselectedDate ?: LocalDate.now(),
        )
    )
    val uiState: StateFlow<EditEventUiState> = _uiState.asStateFlow()

    private var recordingTicker: Job? = null
    private var playbackPolling: Job? = null

    init {
        viewModelScope.launch {
            val existing = eventId?.let { getEvent.once(it) }
            originalEvent = existing
            _uiState.update { state ->
                if (existing == null) {
                    state.copy(isLoading = false)
                } else {
                    val zone = ZoneId.systemDefault()
                    val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(existing.startTime), zone)
                    val end = LocalDateTime.ofInstant(Instant.ofEpochMilli(existing.endTime), zone)
                    state.copy(
                        isLoading = false,
                        isExisting = true,
                        title = existing.title,
                        description = existing.description.orEmpty(),
                        tags = existing.tags.toSet(),
                        startDate = start.toLocalDate(),
                        startTime = start.toLocalTime(),
                        endDate = end.toLocalDate(),
                        endTime = end.toLocalTime(),
                        reminders = existing.reminderMinutes.toSet(),
                        repeatRule = existing.repeatRule,
                        voiceNotePath = existing.voiceNotePath,
                    )
                }
            }
        }
    }

    // ---------- Поля ----------

    fun onTitleChange(value: String) = _uiState.update { it.copy(title = value) }
    fun onDescriptionChange(value: String) = _uiState.update { it.copy(description = value) }

    /** Включение/выключение метки (чекбокс). */
    fun toggleTag(tag: EventTag) = _uiState.update { state ->
        val updated = if (tag in state.tags) state.tags - tag else state.tags + tag
        state.copy(tags = updated)
    }

    fun setStartDate(date: LocalDate) = _uiState.update {
        it.copy(startDate = date, endDate = if (it.endDate.isBefore(date)) date else it.endDate)
    }

    fun setEndDate(date: LocalDate) = _uiState.update { it.copy(endDate = date) }
    fun setStartTime(time: LocalTime) = _uiState.update { it.copy(startTime = time) }
    fun setEndTime(time: LocalTime) = _uiState.update { it.copy(endTime = time) }

    /** Включение/выключение напоминания (мультиселект чипов). */
    fun toggleReminder(minutes: Int) = _uiState.update { state ->
        val updated = if (minutes in state.reminders) state.reminders - minutes
        else state.reminders + minutes
        state.copy(reminders = updated)
    }

    fun setRepeatRule(rule: RepeatRule?) = _uiState.update { it.copy(repeatRule = rule) }

    fun dismissValidationError() = _uiState.update { it.copy(validationError = null) }

    // ---------- Голосовая заметка: запись ----------

    /** Запуск записи; вызывается только при наличии разрешения RECORD_AUDIO. */
    fun startVoiceRecording() {
        val result = startRecording()
        if (result.isSuccess) {
            _uiState.update { it.copy(isRecording = true, recordSeconds = 0, hasNewRecording = false) }
            recordingTicker = viewModelScope.launch {
                while (isActive) {
                    delay(1_000)
                    _uiState.update { it.copy(recordSeconds = it.recordSeconds + 1) }
                }
            }
        }
    }

    fun stopVoiceRecording() {
        recordingTicker?.cancel()
        val file = stopRecording()
        _uiState.update { state ->
            state.copy(
                isRecording = false,
                recordSeconds = 0,
                hasNewRecording = file != null,
                // Воспроизводим временный файл до сохранения события
                voiceNotePath = file?.absolutePath,
                playPositionMs = 0,
            )
        }
    }

    /** Удаление голосовой заметки (файл + путь). */
    fun removeVoiceNote() {
        playbackPolling?.cancel()
        voicePlayer.stop()
        val state = _uiState.value
        if (state.isRecording) {
            recordingTicker?.cancel()
            discardRecording()
            _uiState.update { it.copy(isRecording = false, recordSeconds = 0, voiceNotePath = null, hasNewRecording = false) }
            return
        }
        if (state.hasNewRecording) {
            discardRecording() // удаляет temp-файл
        } else if (state.isExisting && eventId != null) {
            viewModelScope.launch { deleteVoiceNote(eventId) }
        }
        _uiState.update { it.copy(voiceNotePath = null, hasNewRecording = false, isPlaying = false) }
    }

    // ---------- Голосовая заметка: воспроизведение ----------

    fun toggleVoicePlayback() {
        val path = _uiState.value.voiceNotePath ?: return
        if (voicePlayer.state.value.isPlaying) {
            voicePlayer.pause()
            playbackPolling?.cancel()
        } else {
            voicePlayer.play(path)
            startPlaybackPolling()
        }
        _uiState.update { it.copy(isPlaying = voicePlayer.state.value.isPlaying) }
    }

    fun seekVoiceTo(positionMs: Int) {
        voicePlayer.seekTo(positionMs)
        _uiState.update { it.copy(playPositionMs = positionMs) }
    }

    private fun startPlaybackPolling() {
        playbackPolling?.cancel()
        playbackPolling = viewModelScope.launch {
            while (isActive) {
                voicePlayer.refreshProgress()
                val s = voicePlayer.state.value
                _uiState.update { it.copy(isPlaying = s.isPlaying, playPositionMs = s.positionMs, playDurationMs = s.durationMs) }
                delay(200)
            }
        }
    }

    // ---------- Сохранение ----------

    fun save() {
        val state = _uiState.value
        val zone = ZoneId.systemDefault()
        val existingId = eventId
        _uiState.update { it.copy(isSaving = true, validationError = null) }

        viewModelScope.launch {
            val original = originalEvent
            val event = Event(
                id = existingId ?: UUID.randomUUID().toString(),
                title = state.title.trim(),
                description = state.description.trim().takeIf { it.isNotEmpty() },
                startTime = LocalDateTime.of(state.startDate, state.startTime).atZone(zone).toInstant().toEpochMilli(),
                endTime = LocalDateTime.of(state.endDate, state.endTime).atZone(zone).toInstant().toEpochMilli(),
                reminderMinutes = state.reminders.sorted(),
                repeatRule = state.repeatRule,
                textNote = null,
                tags = state.tags.toList(),
                voiceNotePath = state.voiceNotePath,
                // Сохраняем привязку к облаку при редактировании
                calendarProvider = original?.calendarProvider ?: CalendarProvider.LOCAL,
                remoteId = original?.remoteId,
                updatedAt = 0L,
            )
            saveEvent(event, state.hasNewRecording)
                .onSuccess {
                    _uiState.update { it.copy(isSaving = false, saved = true) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isSaving = false, validationError = error.message) }
                }
        }
    }

    override fun onCleared() {
        voicePlayer.stop()
    }
}
