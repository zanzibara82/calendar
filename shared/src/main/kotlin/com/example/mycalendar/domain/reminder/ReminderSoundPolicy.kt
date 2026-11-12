package com.example.mycalendar.domain.reminder

import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.ReminderSound
import com.example.mycalendar.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Вся арифметика выбора сигнала — здесь, до запуска оверлея. Порядок: тихий режим
 * поверх всего; день рождения (метка BIRTHDAY ИЛИ имя коллекции) → URI дня рождения;
 * иначе → URI будильника; пустой/null URI → встроенный reminder_sound. Пустая строка
 * в DataStore означает «не выбирали», а не «без звука» — для молчания служит тихий режим.
 */
class ReminderSoundPolicy @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    suspend fun resolve(event: Event, calendarName: String?): ReminderSound {
        if (settingsRepository.quietMode.first()) return ReminderSound.Silent
        val isBirthday = EventTag.BIRTHDAY in event.tags || BirthdayCalendar.matches(calendarName)
        val uri = if (isBirthday) {
            settingsRepository.birthdaySoundUri.first()
        } else {
            settingsRepository.alarmSoundUri.first()
        }
        return if (uri.isNullOrBlank()) ReminderSound.Builtin else ReminderSound.FromUri(uri)
    }
}