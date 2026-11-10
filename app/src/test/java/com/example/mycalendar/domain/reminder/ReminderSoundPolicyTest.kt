package com.example.mycalendar.domain.reminder

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.ReminderSound
import com.example.mycalendar.domain.model.ThemeMode
import com.example.mycalendar.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Порядок разрешения сигнала: тихий режим поверх всего; день рождения (метка BIRTHDAY
 * ИЛИ имя коллекции) → URI дня рождения; иначе → URI будильника; пустой/null URI → Builtin.
 */
class ReminderSoundPolicyTest {

    private fun event(tags: List<EventTag> = emptyList()) = Event(
        id = "e1",
        title = "Событие",
        startTime = 1_700_000_000_000L,
        endTime = 1_700_003_600_000L,
        tags = tags,
    )

    private fun policy(
        quiet: Boolean = false,
        alarm: String? = null,
        birthday: String? = null,
    ) = ReminderSoundPolicy(FakeSettingsRepository(quiet, alarm, birthday))

    @Test
    fun `quiet mode wins over chosen uri`() = runBlocking {
        val sound = policy(quiet = true, alarm = "content://alarm").resolve(event(), null)
        assertEquals(ReminderSound.Silent, sound)
    }

    @Test
    fun `birthday tag uses birthday uri`() = runBlocking {
        val sound = policy(birthday = "content://bday", alarm = "content://alarm")
            .resolve(event(tags = listOf(EventTag.BIRTHDAY)), null)
        assertEquals(ReminderSound.FromUri("content://bday"), sound)
    }

    @Test
    fun `birthday collection name uses birthday uri without tag`() = runBlocking {
        val sound = policy(birthday = "content://bday", alarm = "content://alarm")
            .resolve(event(), "Дни рождения")
        assertEquals(ReminderSound.FromUri("content://bday"), sound)
    }

    @Test
    fun `plain event uses alarm uri`() = runBlocking {
        val sound = policy(alarm = "content://alarm").resolve(event(), null)
        assertEquals(ReminderSound.FromUri("content://alarm"), sound)
    }

    @Test
    fun `null uri falls back to builtin`() = runBlocking {
        val sound = policy(alarm = null).resolve(event(), null)
        assertEquals(ReminderSound.Builtin, sound)
    }

    @Test
    fun `empty uri falls back to builtin`() = runBlocking {
        val sound = policy(alarm = "").resolve(event(), null)
        assertEquals(ReminderSound.Builtin, sound)
    }

    /** Фейк: три рабочих потока из памяти, остальные члены интерфейса — заглушки. */
    private class FakeSettingsRepository(
        quiet: Boolean,
        alarm: String?,
        birthday: String?,
    ) : SettingsRepository {
        override val quietMode: Flow<Boolean> = flowOf(quiet)
        override val alarmSoundUri: Flow<String?> = flowOf(alarm)
        override val birthdaySoundUri: Flow<String?> = flowOf(birthday)
        override val themeMode: Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
        override val lastSyncTime: Flow<Long> = flowOf(0L)
        override val notificationsAsked: Flow<Boolean> = flowOf(false)
        override val onboardingCompleted: Flow<Boolean> = flowOf(true)
        override fun lastSyncTime(provider: CalendarProvider): Flow<Long> = flowOf(0L)
        override fun collectionUrl(provider: CalendarProvider): Flow<String?> = flowOf(null)
        override fun calendarName(provider: CalendarProvider): Flow<String?> = flowOf(null)
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setLastSyncTime(timeMillis: Long) = Unit
        override suspend fun setNotificationsAsked() = Unit
        override suspend fun setOnboardingCompleted(completed: Boolean) = Unit
        override suspend fun setLastSyncTime(provider: CalendarProvider, timeMillis: Long) = Unit
        override suspend fun setCollectionUrl(provider: CalendarProvider, url: String) = Unit
        override suspend fun clearCollectionUrl(provider: CalendarProvider) = Unit
        override suspend fun setAlarmSoundUri(uri: String?) = Unit
        override suspend fun setBirthdaySoundUri(uri: String?) = Unit
        override suspend fun setQuietMode(enabled: Boolean) = Unit
        override suspend fun setCalendarName(provider: CalendarProvider, name: String) = Unit
    }
}