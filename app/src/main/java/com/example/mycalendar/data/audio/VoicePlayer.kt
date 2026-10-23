package com.example.mycalendar.data.audio

import android.media.MediaPlayer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Состояние воспроизведения голосовой заметки для UI.
 */
data class VoicePlayState(
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)

/**
 * Воспроизведение голосовых заметок через MediaPlayer.
 * Один экземпляр на приложение — одновременное воспроизведение нескольких заметок запрещено.
 */
@Singleton
class VoicePlayer @Inject constructor() {

    private var player: MediaPlayer? = null
    private var currentPath: String? = null

    val state = kotlinx.coroutines.flow.MutableStateFlow(VoicePlayState())

    /** Начать (или продолжить) воспроизведение файла [path]. */
    fun play(path: String) {
        try {
            if (player != null && currentPath == path) {
                player?.start()
                state.value = state.value.copy(isPlaying = true)
                return
            }
            stop()
            currentPath = path
            player = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                setOnCompletionListener {
                    state.value = VoicePlayState(isPlaying = false, positionMs = 0, durationMs = state.value.durationMs)
                    // Перематываем в начало после окончания
                    it.seekTo(0)
                }
                start()
                state.value = VoicePlayState(isPlaying = true, positionMs = currentPosition, durationMs = duration)
            }
        } catch (e: Exception) {
            stop()
        }
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        state.value = state.value.copy(isPlaying = false)
    }

    fun seekTo(positionMs: Int) {
        player?.seekTo(positionMs)
        state.value = state.value.copy(positionMs = positionMs)
    }

    /** Обновление прогресса — вызывается поллингом из ViewModel. */
    fun refreshProgress() {
        val p = player ?: return
        state.value = state.value.copy(
            isPlaying = p.isPlaying,
            positionMs = p.currentPosition,
            durationMs = if (p.duration > 0) p.duration else state.value.durationMs,
        )
    }

    fun stop() {
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        currentPath = null
        state.value = VoicePlayState()
    }
}
