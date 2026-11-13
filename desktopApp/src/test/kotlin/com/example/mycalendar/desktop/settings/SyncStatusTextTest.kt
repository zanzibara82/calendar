package com.example.mycalendar.desktop.settings

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.repository.SyncResult
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatusTextTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val lastSync = LocalDateTime.of(2026, 11, 13, 18, 20)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun `no result and no time tells it never ran`() {
        assertEquals(
            "Синхронизация ещё не выполнялась",
            syncStatusText(result = null, lastSyncTime = 0L, syncing = false, zone = zone),
        )
    }

    @Test
    fun `previous sync time is shown when there is no result`() {
        assertEquals(
            "Последняя синхронизация: 13.11.2026, 18:20",
            syncStatusText(result = null, lastSyncTime = lastSync, syncing = false, zone = zone),
        )
    }

    @Test
    fun `successful sync counts sent pulled and conflicts`() {
        val result = SyncResult(success = true, pushed = 2, pulled = 5, conflicts = 1)

        assertEquals(
            "Готово: отправлено 2, получено 5, конфликтов 1",
            syncStatusText(result, lastSyncTime = lastSync, syncing = false, zone = zone),
        )
    }

    @Test
    fun `failure names the reason`() {
        val result = SyncResult.failure(CalendarProvider.YANDEX, "Нет сети")

        assertEquals(
            "Ошибка синхронизации: Нет сети",
            syncStatusText(result, lastSyncTime = lastSync, syncing = false, zone = zone),
        )
    }

    @Test
    fun `partial sync keeps the reason`() {
        val result = SyncResult(success = true, message = "partial:Нет сети")

        assertEquals(
            "Синхронизировано частично: Нет сети",
            syncStatusText(result, lastSyncTime = lastSync, syncing = false, zone = zone),
        )
    }

    @Test
    fun `nothing connected is spelled out`() {
        assertEquals(
            "Календарь не подключён",
            syncStatusText(SyncResult.nothingConnected(), lastSyncTime = 0L, syncing = false, zone = zone),
        )
    }

    @Test
    fun `running sync wins over the previous result`() {
        val result = SyncResult(success = true, pushed = 2, pulled = 5, conflicts = 1)

        assertEquals(
            "Синхронизация…",
            syncStatusText(result, lastSyncTime = lastSync, syncing = true, zone = zone),
        )
    }
}
