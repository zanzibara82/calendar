package com.example.mycalendar.domain.repository

/** Удаление файла голосовой заметки события (реализация — VoiceNoteStorage). */
interface VoiceNoteCleanup {
    fun deleteFile(absolutePath: String)
}
