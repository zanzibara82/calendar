package com.example.mycalendar.domain.usecase

import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot
import com.example.mycalendar.domain.repository.ConflictRepository
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.work.ReminderScheduler
import javax.inject.Inject

/** Что пользователь выбрал для конфликта-удаления. */
enum class DeletionChoice {
    DELETE_EVERYWHERE,
    RESTORE_FROM_CLOUD,
}

/** Что делать с локальной строкой по этому выбору. */
enum class ResolutionAction {
    /** Строку и файл удаляем сразу: в облаке события уже нет. */
    PurgeLocal,
    /** Tombstone остаётся, push удалит облачную копию (конфликт уже закрыт). */
    TombstoneAndPush,
    /** Локальная запись выживает со значениями из облака. */
    RestoreFromCloud,
}

fun planResolution(kind: ConflictKind, choice: DeletionChoice): ResolutionAction = when {
    kind == ConflictKind.REMOTE_DELETED && choice == DeletionChoice.DELETE_EVERYWHERE ->
        ResolutionAction.PurgeLocal
    kind == ConflictKind.LOCAL_DELETED && choice == DeletionChoice.DELETE_EVERYWHERE ->
        ResolutionAction.TombstoneAndPush
    else -> ResolutionAction.RestoreFromCloud
}

/**
 * Запись после разбора по полям: из облака берётся только [remoteChosen] И ровно те поля,
 * которые спорные. Неспорное поле — локальная правка, которую облаком не заменяют (§7);
 * идентификатор, метки, текстовая и голосовая заметки не синхронизируются и остаются локальными.
 */
fun resolvedEvent(conflict: SyncConflict, local: Event, remoteChosen: Set<SyncField>): Event =
    conflict.remote.appliedTo(local, remoteChosen intersect conflict.contested)

/**
 * Разбор открытого конфликта (экран `conflict/{id}`, задача 10).
 *
 * Разобранное состояние становится НОВЫМ ПРЕДКОМ ([EventRepository.saveResolved]): выбранное
 * пользователем значение и есть согласованная точка, от которой дальше считаются локальные
 * изменения. Оставить предка на месте нельзя: следующий pull увидел бы то же пересечение и
 * воскресил бы конфликт, а выбор пользователя так и не ушёл бы в облако. Флаг pendingSync
 * включается — в облаке разобранного состояния ещё нет, гейт открытых конфликтов к этому моменту
 * уже снят.
 *
 * Строка очереди удаляется всегда, в том числе когда локальной записи уже нет (её мог снять
 * каскад при чистке tombstone'а).
 */
class ResolveConflictUseCase @Inject constructor(
    private val conflictRepository: ConflictRepository,
    private val eventRepository: EventRepository,
    private val reminderScheduler: ReminderScheduler,
) {

    /** Разбор по полям: в [remoteChosen] — поля, по которым выбрано облачное значение. */
    suspend operator fun invoke(conflict: SyncConflict, remoteChosen: Set<SyncField>) {
        val local = eventRepository.getEvent(conflict.eventId)
            ?: return conflictRepository.delete(conflict.id)
        val resolved = resolvedEvent(conflict, local, remoteChosen)
        eventRepository.saveResolved(resolved, SyncSnapshot.of(resolved))
        reminderScheduler.schedule(resolved)
        conflictRepository.delete(conflict.id)
    }

    /** Исход конфликта-удаления по выбору пользователя. */
    suspend fun resolveDeletion(conflict: SyncConflict, choice: DeletionChoice) {
        require(conflict.kind.isDeletion) {
            "Удалением можно разбирать только конфликт-удаление, а не [$conflict.kind]."
        }
        val local = eventRepository.getEvent(conflict.eventId)
            ?: return conflictRepository.delete(conflict.id)
        when (planResolution(conflict.kind, choice)) {
            // Tombstone уже стоит, предка не трогаем: облачная правка, из-за которой был
            // конфликт, остаётся в облаке и перестанет быть спорной только после push.
            ResolutionAction.TombstoneAndPush -> Unit
            ResolutionAction.PurgeLocal -> {
                eventRepository.purge(conflict.eventId)
                reminderScheduler.cancel(local)
            }
            ResolutionAction.RestoreFromCloud -> {
                val restored = conflict.remote
                    .appliedTo(local, SyncField.entries.toSet())
                    // В облаке события уже нет — push должен создать его заново.
                    .copy(
                        remoteId = if (conflict.kind == ConflictKind.REMOTE_DELETED) null
                        else local.remoteId
                    )
                // Предок = снимок облака: строка после возврата равна ему, и следующий pull
                // обязан встать в очередь на push, а не повторить конфликт.
                eventRepository.saveResolved(restored, conflict.remote)
                reminderScheduler.schedule(restored)
            }
        }
        conflictRepository.delete(conflict.id)
    }
}
