package com.example.mycalendar.desktop.reminder

import com.example.mycalendar.domain.model.ReminderSound
import java.awt.Toolkit
import java.io.File
import java.net.URI
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent

/** Чем озвучить напоминание; выбор сигнала приходит из общей политики Android и desktop. */
fun interface ReminderSoundPlayer {
    fun play(sound: ReminderSound)
}

/**
 * Сигнал desktop-клиента: тихий режим молчит, выбранный файл играет через
 * `javax.sound` (WAV/AU/AIFF), остальное — системный звук Windows.
 * MP3 и другие форматы `javax.sound` не читает, для них остаётся системный сигнал.
 */
class DesktopReminderSoundPlayer : ReminderSoundPlayer {

    override fun play(sound: ReminderSound) {
        when (sound) {
            ReminderSound.Silent -> Unit
            ReminderSound.Builtin -> beep()
            is ReminderSound.FromUri -> if (!playFile(sound.uri)) beep()
        }
    }

    private fun playFile(uri: String): Boolean = runCatching {
        val clip = AudioSystem.getClip()
        clip.addLineListener { event ->
            if (event.type == LineEvent.Type.STOP) clip.close()
        }
        AudioSystem.getAudioInputStream(File(URI(uri))).use { stream ->
            clip.open(stream)
            clip.start()
        }
        true
    }.getOrDefault(false)

    private fun beep() {
        runCatching { Toolkit.getDefaultToolkit().beep() }
    }
}
