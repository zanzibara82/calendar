package com.example.mycalendar

import com.example.mycalendar.data.repository.SyncConflictResolver
import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты стратегии last-write-wins при разрешении конфликтов синхронизации.
 */
class SyncConflictResolverTest {

    private val resolver = SyncConflictResolver()

    private fun local(updatedAt: Long) = Event(
        id = "local-1",
        title = "Локальное событие",
        startTime = 1_000L,
        endTime = 2_000L,
        updatedAt = updatedAt,
    )

    private fun remote(updatedAt: Long) = RemoteEvent(
        remoteId = "remote-1",
        title = "Удалённое событие",
        description = null,
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = emptyList(),
        repeatRule = RepeatRule.WEEKLY,
        updatedAt = updatedAt,
    )

    @Test
    fun `remote newer wins`() {
        val resolution = resolver.resolve(local(updatedAt = 100), remote(updatedAt = 200))
        assertEquals(SyncConflictResolver.Resolution.TAKE_REMOTE, resolution)
    }

    @Test
    fun `local newer wins`() {
        val resolution = resolver.resolve(local(updatedAt = 300), remote(updatedAt = 200))
        assertEquals(SyncConflictResolver.Resolution.KEEP_LOCAL, resolution)
    }

    @Test
    fun `equal timestamps prefer local`() {
        val resolution = resolver.resolve(local(updatedAt = 200), remote(updatedAt = 200))
        assertEquals(SyncConflictResolver.Resolution.KEEP_LOCAL, resolution)
    }
}
