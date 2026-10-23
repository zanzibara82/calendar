package com.example.mycalendar.data.audio

import android.content.Context
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
) {
    private val voiceDir: File = File(context.filesDir, "voice_notes").apply { mkdirs() }

    /** Постоянный файл заметки для события. */
    fun fileFor(eventId: String): File = File(voiceDir, "$eventId.m4a")

    /** Временный файл активной записи (до сохранения события). */
    val tempFile: File = File(voiceDir, "temp_recording.m4a")

    fun deleteFor(eventId: String) {
        fileFor(eventId).delete()
    }
}
