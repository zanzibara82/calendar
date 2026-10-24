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
 * Чистый класс без Android-зависимостей — вся матрица покрыта unit-тестами.
 */
@Singleton
class SyncConflictResolver @Inject constructor() {

    fun decide(record: SyncedRecord?, remote: RemoteEvent, provider: CalendarProvider): PullDecision {
        val incoming = remote.toEvent(provider)
        val remoteSnapshot = SyncSnapshot.of(incoming)
        val local = record?.event
            ?: return PullDecision.Insert(incoming, remoteSnapshot)
        val base = record.base

        if (remote.deleted) return when {
            record.isDeleted || !record.pendingSync -> PullDecision.Purge
            else -> PullDecision.Conflict(
                eventId = local.id,
                kind = ConflictKind.REMOTE_DELETED,
                base = base,
                remote = remoteSnapshot,
                contested = emptySet(),
                mergedLocal = local,
            )
        }

        if (record.isDeleted) {
            // Tombstone удаляем в облаке молча только если независимой правки там нет.
            val changedInCloud = base?.let { diffFields(it, remoteSnapshot) }.orEmpty()
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

        val localSnapshot = SyncSnapshot.of(local)

        // Предка нет (строки до миграции): локальная правка — единственное, что мы знаем.
        if (base == null) {
            val differs = diffFields(localSnapshot, remoteSnapshot)
            return when {
                differs.isEmpty() -> PullDecision.Skip
                !record.pendingSync -> PullDecision.ApplyRemote(incoming.appliedTo(local), remoteSnapshot)
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
            else PullDecision.ApplyRemote(incoming.appliedTo(local), remoteSnapshot)
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

    // TODO(Task 6): удалить вместе с переходом pull на decide()
    enum class Resolution { KEEP_LOCAL, TAKE_REMOTE }

    fun resolve(local: Event, remote: RemoteEvent): Resolution = when {
        remote.updatedAt > local.updatedAt -> Resolution.TAKE_REMOTE
        else -> Resolution.KEEP_LOCAL
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

    /** Изменения не пересеклись: результат нужно отправить, предок ждёт успешного push. */
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
