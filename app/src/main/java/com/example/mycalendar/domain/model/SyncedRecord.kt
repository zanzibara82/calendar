package com.example.mycalendar.domain.model

/**
 * Локальная запись глазами синхронизации: контент плюс служебное состояние,
 * нужное для трёхстороннего слияния (флаги строки и снимок-предок).
 */
data class SyncedRecord(
    val event: Event,
    val isDeleted: Boolean,
    val pendingSync: Boolean,
    val base: SyncSnapshot?,
)
