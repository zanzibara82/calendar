package com.example.mycalendar.domain.model

/** Чем именно столкнулись локальная запись и облако. */
enum class ConflictKind {
    /** Разные значения в одном и том же поле — разбирается по полям. */
    CONTENT,
    /** Облако удалило событие, а локально есть неотправленные правки. */
    REMOTE_DELETED,
    /** Локальный tombstone, а в облаке событие правили. */
    LOCAL_DELETED,
    ;

    /** Конфликт-удаление: спорных полей нет, выбор бинарный (удалить или оставить). */
    val isDeletion: Boolean get() = this != CONTENT
}

/**
 * Открытый конфликт синхронизации — то, что пользователь разбирает на экране.
 *
 * Локальная версия не хранится: разбор идёт против текущего значения строки.
 */
data class SyncConflict(
    val id: String,
    val eventId: String,
    val provider: CalendarProvider,
    val remoteId: String,
    val kind: ConflictKind,
    val base: SyncSnapshot?,
    val remote: SyncSnapshot,
    /** Спорные поля; пусто для [ConflictKind.REMOTE_DELETED] и [ConflictKind.LOCAL_DELETED]. */
    val contested: Set<SyncField>,
    val detectedAt: Long,
) {
    val isDeletion: Boolean get() = kind.isDeletion
}
