package com.example.mycalendar.domain.model

import kotlinx.serialization.Serializable

/** Чем именно столкнулись локальная запись и облако. */
@Serializable
enum class ConflictKind {
    /** Разные значения в одном и том же поле — разбирается по полям. */
    CONTENT,
    /** Облако удалило событие, а локально есть неотправленные правки. */
    REMOTE_DELETED,
    /** Локальный tombstone, а в облаке событие правили. */
    LOCAL_DELETED,
    ;

    /** Вид, где выбор разбора бинарный — удалить строку или оставить её: удаление не побеждает
     *  молча. Пустой список спорных полей этому виду не принадлежит: у `LOCAL_DELETED` без предка
     *  в нём лежат все отличия строки от облака. */
    val isDeletion: Boolean get() = this != CONTENT
}

/**
 * Открытый конфликт синхронизации — то, что пользователь разбирает на экране.
 *
 * Локальная версия не хранится: разбор идёт против текущего значения строки.
 * Сериализуема: desktop-клиент хранит очередь конфликтов файлом JSON, Android — таблицей Room.
 */
@Serializable
data class SyncConflict(
    val id: String,
    val eventId: String,
    val provider: CalendarProvider,
    val remoteId: String,
    val kind: ConflictKind,
    val base: SyncSnapshot?,
    val remote: SyncSnapshot,
    /** Спорные поля: пусто для [ConflictKind.REMOTE_DELETED]; для [ConflictKind.LOCAL_DELETED] —
     *  изменения облака, если предок есть, и все отличия строки от облака, если предка нет. */
    val contested: Set<SyncField>,
    val detectedAt: Long,
) {
    val isDeletion: Boolean get() = kind.isDeletion
}

/**
 * Совпадает ли всё, что видит пользователь в разборе: вид, набор спорных полей и облачная версия.
 * Сравнение облачных значений нормализованное, как у решателя: `null` и пустая строка описания,
 * а также порядок минут одним значением не считаются.
 */
fun SyncConflict.showsSameState(other: SyncConflict): Boolean =
    kind == other.kind &&
        contested == other.contested &&
        diffFields(remote, other.remote).isEmpty()
