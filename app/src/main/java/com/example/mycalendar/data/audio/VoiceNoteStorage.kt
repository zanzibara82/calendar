package com.example.mycalendar.data.audio

import android.content.Context
import android.util.Log
import com.example.mycalendar.domain.repository.VoiceNoteCleanup
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Хранилище голосовых заметок: app-specific каталог files/voice_notes.
 * Файлы не требуют внешних разрешений и удаляются вместе с приложением.
 */
@Singleton
class VoiceNoteStorage @Inject constructor(
    @ApplicationContext context: Context,
) : VoiceNoteCleanup {
    private val voiceDir: File = File(context.filesDir, "voice_notes").apply { mkdirs() }

    /** Постоянный файл заметки для события. */
    fun fileFor(eventId: String): File = File(voiceDir, "$eventId.m4a")

    /** Временный файл активной записи (до сохранения события). */
    val tempFile: File = File(voiceDir, "temp_recording.m4a")

    /**
     * Удаление по пути из строки: репозиторий знает путь события, но не каталог заметок.
     * `true` — файл удалён или его уже не было, `false` — файл остался (отказ файловой системы).
     */
    override fun deleteFile(absolutePath: String): Boolean {
        val file = File(absolutePath)
        if (file.delete()) return true
        // delete() возвращает false и когда файла нет: предупреждение пишем только при отказе.
        if (file.exists()) {
            Log.w(TAG, "Файл голосовой заметки не удалён: $absolutePath")
            return false
        }
        return true
    }

    private companion object {
        private const val TAG = "VoiceNoteStorage"
    }
}
