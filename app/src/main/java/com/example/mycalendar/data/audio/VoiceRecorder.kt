package com.example.mycalendar.data.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Запись голосовых заметок через MediaRecorder в формат AAC/M4A
 * (OutputFormat.MPEG_4 + AudioEncoder.AAC — требование ТЗ).
 */
@Singleton
class VoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: VoiceNoteStorage,
) {
    private var recorder: MediaRecorder? = null
    private var startedAtMillis: Long = 0L

    /** Начать запись во временный файл. Требует разрешение RECORD_AUDIO. */
    fun start(): Result<Unit> = runCatching {
        discard()
        val outputFile = storage.tempFile
        recorder = createRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            // MPEG_4 контейнер + AAC кодек = файл .m4a
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
        startedAtMillis = System.currentTimeMillis()
    }

    /** Остановить запись; возвращает файл записи (temp) или null, если запись не велась. */
    fun stop(): File? {
        val current = recorder ?: return null
        return runCatching {
            current.stop() // бросает исключение, если запись короче ~1 сек
            storage.tempFile.takeIf { it.exists() && it.length() > 0 }
        }.getOrElse {
            storage.tempFile.delete()
            null
        }.also {
            current.release()
            recorder = null
            startedAtMillis = 0L
        }
    }

    /** Отменить запись и удалить файл. */
    fun discard() {
        runCatching {
            recorder?.stop()
        }
        recorder?.release()
        recorder = null
        startedAtMillis = 0L
        storage.tempFile.delete()
    }

    /** Длительность текущей записи в секундах (для таймера UI). */
    fun elapsedSeconds(): Int =
        if (startedAtMillis == 0L) 0 else ((System.currentTimeMillis() - startedAtMillis) / 1000).toInt()

    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
}
