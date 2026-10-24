package com.example.mycalendar.domain.repository

/**
 * Удаление файла голосовой заметки события (реализация — `VoiceNoteStorage`).
 *
 * `true` — файл удалён либо его уже не было, `false` — файловая система отказала
 * и файл остался на месте.
 */
interface VoiceNoteCleanup {
    fun deleteFile(absolutePath: String): Boolean
}
