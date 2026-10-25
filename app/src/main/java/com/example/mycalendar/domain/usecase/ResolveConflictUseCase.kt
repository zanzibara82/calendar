package com.example.mycalendar.domain.usecase

import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot
import com.example.mycalendar.domain.model.diffFields
import com.example.mycalendar.domain.model.scheduleChangedFrom
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
 * Снимок с экрана может устареть: очередь переиспользует идентификатор строки и перезаписывает вид,
 * поэтому оба метода начинают с перечитывания конфликта и работают только с прочитанным объектом.
 *
 * По полям: разобранное состояние становится НОВЫМ ПРЕДКОМ ([EventRepository.saveResolved]) —
 * выбранное значение и есть согласованная точка, от которой считаются дальнейшие изменения. Если
 * разобранная строка уже равна облаку, предок ставится записью из облака
 * ([EventRepository.saveFromRemote]): отправлять подтверждение повторно незачем.
 *
 * Удаления: «удалить везде» при локальном tombstone сдвигает предка на увиденное облако
 * ([EventRepository.moveSyncBase]) — иначе следующий pull повторил бы тот же конфликт, а push
 * остался бы закрыт гейтом открытых конфликтов, и удаление не доехало бы до облака никогда.
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
        val current = conflictRepository.get(conflict.id) ?: return
        require(!current.kind.isDeletion) {
            "По полям разбирается только конфликт содержимого, а не [${current.kind}]."
        }
        val local = eventRepository.getEvent(current.eventId)
            ?: return conflictRepository.delete(current.id)
        // Пока конфликт висел, строку могли удалить из списка: воскрешать её нельзя (§7).
        require(
            eventRepository.findSyncedRecord(current.provider, current.remoteId)?.isDeleted != true
        ) {
            "Событие уже удалено локально: разбирать по полям нечего."
        }
        val resolved = resolvedEvent(current, local, remoteChosen)
        // schedule() снимает только будильники НОВОГО набора минут; прежние снимаем явно.
        // Если поля заказа не сдвинулись, планировщик не трогаем вовсе: иначе на строке, которую
        // пользователь оставил как была, гибнет маркер отложенного напоминания.
        val reschedule = resolved.scheduleChangedFrom(local)
        if (reschedule) reminderScheduler.cancel(local)
        // Сравнение — по нормализованным полям, как у решателя: `null` и пустая строка описания,
        // а также порядок минут одним значением не считаются.
        if (diffFields(current.remote, SyncSnapshot.of(resolved)).isEmpty()) {
            eventRepository.saveFromRemote(resolved, current.remote)
        } else {
            eventRepository.saveResolved(resolved, SyncSnapshot.of(resolved))
        }
        if (reschedule) reminderScheduler.schedule(resolved)
        conflictRepository.delete(current.id)
    }

    /** Исход конфликта-удаления по выбору пользователя. */
    suspend fun resolveDeletion(conflict: SyncConflict, choice: DeletionChoice) {
        val current = conflictRepository.get(conflict.id) ?: return
        require(current.kind.isDeletion) {
            "Удалением можно разбирать только конфликт-удаление, а не [${current.kind}]."
        }
        val local = eventRepository.getEvent(current.eventId)
            ?: return conflictRepository.delete(current.id)
        when (planResolution(current.kind, choice)) {
            // Tombstone остаётся, но предок догоняет облако: следующий pull не повторит спор,
            // а push снимет облачную копию.
            ResolutionAction.TombstoneAndPush ->
                eventRepository.moveSyncBase(current.eventId, current.remote)

            ResolutionAction.PurgeLocal -> {
                eventRepository.purge(current.eventId)
                reminderScheduler.cancel(local)
            }

            ResolutionAction.RestoreFromCloud -> {
                val restored = current.remote
                    .appliedTo(local, SyncField.entries.toSet())
                    // В облаке события уже нет — push должен создать его заново.
                    .copy(
                        remoteId = if (current.kind == ConflictKind.REMOTE_DELETED) null
                        else local.remoteId
                    )
                // Прежние будильники снимаются по минутам удалённой записи, а не только новые.
                reminderScheduler.cancel(local)
                if (current.kind == ConflictKind.REMOTE_DELETED) {
                    // Строки в облаке нет: она обязана уйти в push как новое событие.
                    eventRepository.saveResolved(restored, current.remote)
                } else {
                    // Облачное состояние у провайдера уже есть: отправлять подтверждение незачем.
                    eventRepository.saveFromRemote(restored, current.remote)
                }
                reminderScheduler.schedule(restored)
            }
        }
        conflictRepository.delete(current.id)
    }
}
