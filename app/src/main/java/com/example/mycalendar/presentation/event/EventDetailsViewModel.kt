package com.example.mycalendar.presentation.event

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mycalendar.data.audio.VoicePlayer
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.usecase.DeleteEventUseCase
import com.example.mycalendar.domain.usecase.DeleteVoiceNoteUseCase
import com.example.mycalendar.domain.usecase.GetEventUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EventDetailsUiState(
    val event: Event? = null,
    val isLoading: Boolean = true,
    // Плеер
    val isPlaying: Boolean = false,
    val playPositionMs: Int = 0,
    val playDurationMs: Int = 0,
)

/**
 * ViewModel экрана деталей события: наблюдение за событием + плеер голосовой заметки.
 */
@HiltViewModel
class EventDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    getEvent: GetEventUseCase,
    private val deleteEvent: DeleteEventUseCase,
    private val deleteVoiceNote: DeleteVoiceNoteUseCase,
    private val voicePlayer: VoicePlayer,
) : ViewModel() {

    private val eventId: String = checkNotNull(savedStateHandle["eventId"])

    private val playbackState = voicePlayer.state

    val uiState: StateFlow<EventDetailsUiState> =
        combine(
            getEvent(eventId),
            playbackState,
        ) { event, play ->
            EventDetailsUiState(
                event = event,
                isLoading = false,
                isPlaying = play.isPlaying,
                playPositionMs = play.positionMs,
                playDurationMs = play.durationMs,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventDetailsUiState())

    private var pollingJob: Job? = null

    init {
        // Поллинг прогресса воспроизведения (MediaPlayer не шлёт события позиций)
        pollingJob = viewModelScope.launch {
            while (isActive) {
                voicePlayer.refreshProgress()
                delay(200)
            }
        }
    }

    fun togglePlayback() {
        val path = uiState.value.event?.voiceNotePath ?: return
        if (voicePlayer.state.value.isPlaying) voicePlayer.pause() else voicePlayer.play(path)
    }

    fun seekTo(positionMs: Int) = voicePlayer.seekTo(positionMs)

    fun deleteVoiceNote() {
        viewModelScope.launch {
            voicePlayer.stop()
            deleteVoiceNote(eventId)
        }
    }

    fun deleteEvent(onDeleted: () -> Unit) {
        viewModelScope.launch {
            voicePlayer.stop()
            deleteEvent(eventId)
            onDeleted()
        }
    }

    override fun onCleared() {
        voicePlayer.stop()
    }
}
