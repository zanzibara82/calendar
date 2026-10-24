package com.example.mycalendar.data.repository

import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.data.remote.toEvent
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot
import com.example.mycalendar.domain.model.SyncedRecord
import com.example.mycalendar.domain.model.diffFields
import com.example.mycalendar.domain.model.withLocalFieldsFrom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Решение по одному событию из облака против локальной записи.
 *
 * Основа — трёхстороннее слияние относительно снимка-предка: «кто менялся» отвечает
 * diff с предком, а не сравнение updatedAt. Время изменения провайдеры отдают
 * по-разному (у системного календаря такой колонки нет вовсе), и по нему правки
 * терялись молча.
 *
 * Чистота здесь — по побочным эффектам: метод ничего не читает и не пишет, Android-зависимостей
 * нет, вся матрица покрыта unit-тестами. Детерминизма по полю `id` при этом нет: идентификатор
 * вставляемой копии генерирует `toEvent`, поэтому решения сверяют по снимкам и содержимому,
 * а не по идентификатору.
 */
@Singleton
class SyncConflictResolver @Inject constructor() {

    fun decide(record: SyncedRecord?, remote: RemoteEvent, provider: CalendarProvider): PullDecision {
        val incoming = remote.toEvent(provider)
        val remoteSnapshot = SyncSnapshot.of(incoming)

        if (remote.deleted) {
            // Облако удалено, а локальной пары нет: вставлять нечего, предка нет, чистить нечего.
            if (record == null) return PullDecision.Skip
            val local = record.event
            // Признак «локальных правок нет» — `pendingSync`: он корректен ровно потому, что
            // предка двигают `saveFromRemote`, `markSynced`, `attachToProvider`, `saveResolved`,
            // а `pendingSync` из них снимают только первые три. Потеря флага без движения
            // предка невозможна; других мест, двигающих предка, нет.
            if (record.isDeleted || !record.pendingSync) return PullDecision.Purge
            return PullDecision.Conflict(
                eventId = local.id,
                kind = ConflictKind.REMOTE_DELETED,
                base = record.base,
                remote = remoteSnapshot,
                contested = emptySet(),
                mergedLocal = local,
            )
        }

        if (record == null) return PullDecision.Insert(incoming, remoteSnapshot)
        val local = record.event
        val base = record.base
        val localSnapshot = SyncSnapshot.of(local)

        if (record.isDeleted) {
            // Без предка мы не знаем, правлено ли облако: молча удалять нельзя, спорим всё,
            // чем грозящая строка отличается от облачной версии.
            if (base == null) return PullDecision.Conflict(
                eventId = local.id,
                kind = ConflictKind.LOCAL_DELETED,
                base = null,
                remote = remoteSnapshot,
                contested = diffFields(localSnapshot, remoteSnapshot),
                mergedLocal = local,
            )
            // Tombstone удаляем в облаке молча только если независимой правки там нет.
            val changedInCloud = diffFields(base, remoteSnapshot)
            return if (changedInCloud.isEmpty()) PullDecision.PushDelete
            else PullDecision.Conflict(
                eventId = local.id,
                kind = ConflictKind.LOCAL_DELETED,
                base = base,
                remote = remoteSnapshot,
                contested = changedInCloud,
                mergedLocal = local,
            )
        }

        // Предка нет (строки до миграции): локальная правка — единственное, что мы знаем.
        if (base == null) {
            val differs = diffFields(localSnapshot, remoteSnapshot)
            return when {
                differs.isEmpty() -> PullDecision.Skip
                !record.pendingSync -> PullDecision.ApplyRemote(incoming.withLocalFieldsFrom(local), remoteSnapshot)
                else -> PullDecision.Conflict(
                    eventId = local.id,
                    kind = ConflictKind.CONTENT,
                    base = null,
                    remote = remoteSnapshot,
                    contested = differs,
                    mergedLocal = local,
                )
            }
        }

        val remoteChanges = diffFields(base, remoteSnapshot)
        if (remoteChanges.isEmpty()) return PullDecision.Skip

        val localChanges = diffFields(base, localSnapshot)
        if (localChanges.isEmpty()) {
            // Строка равна предку, но её состояние (наш разбор или возврат из облака) ещё
            // не подтверждено облаком. Молча заменять её нельзя: дождёмся push.
            return if (record.pendingSync) PullDecision.Skip
            else PullDecision.ApplyRemote(incoming.withLocalFieldsFrom(local), remoteSnapshot)
        }

        // Пересечение изменений спорно только если значения реально разошлись:
        // обе стороны могли независимо прийти к одному и тому же.
        val contested = (remoteChanges intersect localChanges)
            .filter { remoteSnapshot.fieldOf(it) != localSnapshot.fieldOf(it) }
            .toSet()
        val merged = remoteSnapshot.appliedTo(local, remoteChanges - contested)
        if (contested.isEmpty()) {
            // Повтор pull'а до успешного push не должен «сливать» уже слитое.
            return if (diffFields(SyncSnapshot.of(merged), localSnapshot).isEmpty()) {
                PullDecision.Skip
            } else {
                PullDecision.AutoMerge(merged)
            }
        }
        return PullDecision.Conflict(
            eventId = local.id,
            kind = ConflictKind.CONTENT,
            base = base,
            remote = remoteSnapshot,
            contested = contested,
            mergedLocal = merged,
        )
    }
}

/** Что делает pull с одной локальной записью по итогам сравнения с предком. */
sealed interface PullDecision {

    /** Облако не отличилось от предка — локальную строку не трогаем. */
    data object Skip : PullDecision

    /** Локальной пары нет: вставляем облачную копию, она и есть предок. */
    data class Insert(val event: Event, val base: SyncSnapshot) : PullDecision

    /** Правки только на стороне облака: применяем молча, предок едет за облаком. */
    data class ApplyRemote(val event: Event, val base: SyncSnapshot) : PullDecision

    /**
     * Изменения не пересеклись: результат нужно отправить, предок ждёт успешного push.
     * Сохранять его вправе только пара, поднимающая флаг, — `saveFromUser` / `saveResolved`:
     * сохранение через `saveFromRemote(..., null)` убрало бы `pendingSync`, не отправив слияние,
     * и автослитое значение повисло бы локально с вечным `Skip`.
     */
    data class AutoMerge(val event: Event) : PullDecision

    /** Нужен разбор пользователем; спорные поля остаются с локальным значением. */
    data class Conflict(
        val eventId: String,
        val kind: ConflictKind,
        val base: SyncSnapshot?,
        val remote: SyncSnapshot,
        val contested: Set<SyncField>,
        /** Что сохранить локально до разбора: объединённая запись либо текущая — для удалений. */
        val mergedLocal: Event,
    ) : PullDecision

    /** Облако уже без события, локальных правок нет: удаляем строку и файл заметки. */
    data object Purge : PullDecision

    /** Локальный tombstone, облако не менялось: удаление уйдёт в облако через push. */
    data object PushDelete : PullDecision
}
