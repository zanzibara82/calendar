package com.example.mycalendar.data.repository

import com.example.mycalendar.data.remote.RemoteCalendarDataSource
import com.example.mycalendar.data.remote.RemoteDataSourceProvider
import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.data.remote.RemoteFetch
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.scheduleChangedFrom
import com.example.mycalendar.domain.model.SyncSnapshot
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.ConflictRepository
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import com.example.mycalendar.domain.repository.SyncRepository
import com.example.mycalendar.domain.repository.SyncResult
import com.example.mycalendar.work.ReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Двусторонняя синхронизация локальной БД с календарями провайдеров.
 *
 * Провайдеры НЕЗАВИСИМЫ: событие принадлежит одному из них (поле calendarProvider),
 * поэтому Google и Яндекс могут быть подключены одновременно и не затирают друг друга.
 *
 *  PULL: для каждого события провайдера [SyncConflictResolver.decide] сравнивает
 *  облачную версию и локальную запись с их общим предком (EventEntity.syncBase):
 *  - предок = облако                     -> Skip (локальные правки не трогаем);
 *  - менялось только облако              -> применяем, предок едет за ним;
 *  - правки не пересекаются              -> объединяем, предок ждёт успешного push;
 *  - пересечение по полю                 -> строка в sync_conflicts, локальное значение в силе;
 *  - удаление на одной из сторон с правками на другой -> тоже конфликт (см. design §3).
 *
 *  УДАЛЕНИЯ БЕЗ TOMBSTONE'ОВ: провайдер, перечисливший коллекцию целиком
 *  ([RemoteFetch.presentRemoteIds]), удаляет событие без отдельной строки — оно просто
 *  исчезает из выборки. Такие записи проходят через тот же [SyncConflictResolver.decide]
 *  с признаком `deleted`: без локальных правок строка вычищается, с ними — конфликт.
 *
 *  PUSH: локальные записи с pendingSync = этого провайдера; события с открытым
 *  конфликтом пропускаются. После успешной отправки syncBase догоняет отправленное.
 *
 *  Метки, текстовая и голосовая заметки есть только локально: при перезаписи события
 *  удалённой версией они переносятся из локальной записи (`Event.withLocalFieldsFrom`)
 *  и потому не могут быть спорными. Файл голосовой заметки удаляется вместе со строкой
 *  БД ([EventRepository.purge]).
 */
@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val eventRepository: EventRepository,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val conflictResolver: SyncConflictResolver,
    private val conflictRepository: ConflictRepository,
    private val dataSourceProvider: RemoteDataSourceProvider,
    private val reminderScheduler: ReminderScheduler,
) : SyncRepository {

    // Очередь конфликтов пишется без транзакции, а входов в pull два (периодический воркер и
    // интерфейс): конкурентный проход того же провайдера роняет уникальный индекс по eventId.
    private val providerLocks = CalendarProvider.entries.associateWith { Mutex() }

    /** Синхронизация всех подключённых календарей со сводным результатом. */
    override suspend fun syncNow(): SyncResult {
        val providers = authRepository.connectedProviders()
            .filter { it != CalendarProvider.LOCAL }
        if (providers.isEmpty()) return SyncResult.nothingConnected()

        var pushed = 0
        var pulled = 0
        var conflicts = 0
        var failures = 0
        var lastError: String? = null

        for (provider in providers) {
            val result = syncProvider(provider)
            if (result.success) {
                pushed += result.pushed
                pulled += result.pulled
                conflicts += result.conflicts
            } else {
                failures++
                lastError = result.message
            }
        }

        return when {
            failures == 0 -> SyncResult(
                success = true, pushed = pushed, pulled = pulled, conflicts = conflicts
            )

            failures == providers.size -> SyncResult(
                success = false, message = lastError, pushed = pushed,
                pulled = pulled, conflicts = conflicts,
            )

            // Частичный успех: один календарь синхронизировался, другой — нет
            else -> SyncResult(
                success = true, pushed = pushed, pulled = pulled, conflicts = conflicts,
                message = "partial:$lastError",
            )
        }
    }

    override suspend fun syncProvider(provider: CalendarProvider): SyncResult {
        if (provider == CalendarProvider.LOCAL) return SyncResult.nothingConnected()

        return providerLocks.getValue(provider).withLock {
            // Провайдер мог быть отключён — проверяем перед запросом токена
            val account = authRepository.accountFor(provider)
                ?: return@withLock SyncResult.failure(provider, "Календарь ${provider.name} не подключён")

            val remote = dataSourceProvider.forProvider(provider)
                ?: return@withLock SyncResult.failure(provider, "Провайдер ${provider.name} не обслуживается")

            try {
                val result = doSync(remote, provider)
                settingsRepository.setLastSyncTime(provider, System.currentTimeMillis())
                result
            } catch (e: CancellationException) {
                // Отмена (уход с экрана, остановка WorkManager) — не ошибка синхронизации.
                throw e
            } catch (e: Exception) {
                SyncResult.failure(provider, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun doSync(
        remote: RemoteCalendarDataSource,
        provider: CalendarProvider,
    ): SyncResult {
        var pulled = 0
        var conflicts = 0
        val rescheduleIds = mutableListOf<String>()

        // Время последней синхронизации ИМЕННО этого провайдера
        val since = settingsRepository.lastSyncTime(provider).first()

        val fetch = remote.fetchUpdatedSince(if (since > 0) since else 0L)

        for (remoteEvent in fetch.events) {
            val record = eventRepository.findSyncedRecord(provider, remoteEvent.remoteId)
            when (val decision = conflictResolver.decide(record, remoteEvent, provider)) {
                PullDecision.Skip, PullDecision.PushDelete -> Unit

                is PullDecision.Insert -> {
                    eventRepository.saveFromRemote(decision.event, decision.base)
                    pulled++
                    rescheduleIds += decision.event.id
                }

                is PullDecision.ApplyRemote -> {
                    eventRepository.saveFromRemote(decision.event, decision.base)
                    pulled++
                    rescheduleIds += decision.event.id
                }

                // Объединение непересекаемых полей: результат неотправлен, предок НЕ двигаем.
                is PullDecision.AutoMerge -> {
                    eventRepository.saveFromUser(decision.event)
                    pulled++
                    rescheduleIds += decision.event.id
                }

                is PullDecision.Conflict -> {
                    // Для спорных полей держим локальное значение; молча перезаписывать нельзя (§7).
                    if (!decision.kind.isDeletion) {
                        eventRepository.saveFromUser(decision.mergedLocal)
                        // Переставляем будильник только когда сдвинулись поля заказа:
                        // schedule() первым делом снимает прежний заказ вместе с маркером
                        // отложенного напоминания, а конфликт часто решается в пользу облачного
                        // названия или описания. Нет прежней записи — заказ нужен заново.
                        val reschedule = record?.let {
                            decision.mergedLocal.scheduleChangedFrom(it.event)
                        } ?: true
                        if (reschedule) {
                            rescheduleIds += decision.eventId
                        }
                    }
                    conflictRepository.save(decision.toConflict(provider, remoteEvent.remoteId))
                    conflicts++
                }

                PullDecision.Purge -> {
                    record?.let { eventRepository.purge(it.event.id) }
                    record?.let { reminderScheduler.cancel(it.event) }
                    pulled++
                }
            }
        }

        // Пустой выборке верим только как продолжению той же коллекции: первый ответ после
        // смены аккаунта не должен вычищать привязки старого календаря.
        val pinnedUrl = settingsRepository.collectionUrl(provider).first()
        val sameCollection = fetch.collectionUrl != null && fetch.collectionUrl == pinnedUrl
        val (purgedVanished, vanishedConflicts) =
            handleVanished(fetch.presentRemoteIds, provider, sameCollection)
        pulled += purgedVanished
        conflicts += vanishedConflicts

        val pushed = push(remote, provider)

        // Сменившийся URL не подменяем: иначе защита от чужой коллекции отключилась бы сама.
        if (sameCollection || pinnedUrl == null) {
            fetch.collectionUrl?.let { settingsRepository.setCollectionUrl(provider, it) }
        }

        // Перепланирование напоминаний для событий, которым pull двигал содержимое
        rescheduleIds.distinct().forEach { id ->
            eventRepository.getEvent(id)?.let { reminderScheduler.schedule(it) }
        }

        return SyncResult(
            success = true, pushed = pushed, pulled = pulled,
            conflicts = conflicts, provider = provider,
        )
    }

    /**
     * Удаления, о которых провайдер не сообщает: события нет в полной выборке коллекции.
     * Запись проходит через общий [SyncConflictResolver.decide] с признаком `deleted`,
     * поэтому неотправленные локальные правки дают конфликт, а не молчаливую потерю.
     *
     * @param sameCollection выборка пришла из той же коллекции, что и прошлая успешная
     *        синхронизация: только тогда пустой ответ — доказательство удаления.
     * @return вычищено строк и заведено конфликтов; обе величины нулевые, если
     *         выборка неполна ([presentIds] == null) или не пересекается с локальными привязками.
     */
    private suspend fun handleVanished(
        presentIds: Set<String>?,
        provider: CalendarProvider,
        sameCollection: Boolean,
    ): Pair<Int, Int> {
        if (presentIds == null) return 0 to 0
        val attached = eventRepository.getAttachedRecords(provider)
        // Хотя бы одна привязка обязана найтись в выборке. Иначе это ответ не по этой
        // коллекции (другой календарь, пустой REPORT), и удалять по нему весь набор нельзя.
        // Для той же коллекции проверка снята: там пустая выборка — законное «удалено всё»,
        // иначе последнее событие не вычистилось бы никогда.
        if (!sameCollection && attached.none { it.event.remoteId in presentIds }) return 0 to 0

        var purged = 0
        var conflicts = 0
        for (record in attached) {
            val remoteId = record.event.remoteId ?: continue
            if (remoteId in presentIds) continue
            // Облачной копии нет, поэтому её «последней версией» служит предок —
            // последнее согласованное состояние. Пустой снимок сделал бы «создать
            // заново» пустым событием (экран разбора показывает этот снимок).
            val last = record.base ?: SyncSnapshot.of(record.event)
            val gone = RemoteEvent(
                remoteId = remoteId,
                title = last.title,
                description = last.description,
                startTime = last.startTime,
                endTime = last.endTime,
                reminderMinutes = last.reminderMinutes,
                repeatRule = last.repeatRule,
                updatedAt = 0L,
                deleted = true,
            )
            when (val decision = conflictResolver.decide(record, gone, provider)) {
                PullDecision.Purge -> {
                    eventRepository.purge(record.event.id)
                    reminderScheduler.cancel(record.event)
                    purged++
                }

                is PullDecision.Conflict -> {
                    conflictRepository.save(decision.toConflict(provider, remoteId))
                    conflicts++
                }

                // Для отсутствующего события других решений быть не может; трактовать
                // их как вставку или слияние значило бы подменить удаление правкой.
                else -> Unit
            }
        }
        return purged to conflicts
    }

    private fun PullDecision.Conflict.toConflict(
        provider: CalendarProvider,
        remoteId: String,
    ): SyncConflict = SyncConflict(
        id = UUID.randomUUID().toString(),
        eventId = eventId,
        provider = provider,
        remoteId = remoteId,
        kind = kind,
        base = base,
        remote = remote,
        contested = contested,
        detectedAt = System.currentTimeMillis(),
    )

    /**
     * Отправка локальных изменений в облако [provider].
     *
     * @return количество отправленных записей.
     */
    private suspend fun push(
        remote: RemoteCalendarDataSource,
        provider: CalendarProvider,
    ): Int {
        // Локальные события отправляем только если это ЕДИНСТВЕННЫЙ подключённый
        // календарь — иначе они задвоились бы в оба облака.
        val connected = authRepository.connectedProviders()
        val includeLocal = connected.size == 1 && connected.first() == provider

        // Спорные поля в облако не идут: локальное значение держим до разбора (§7).
        val contestedIds = conflictRepository.openEventIds().toSet()

        val pending = eventRepository.getPendingSyncEvents().filter { item ->
            item.event.id !in contestedIds &&
                (item.event.calendarProvider == provider ||
                    (includeLocal && item.event.calendarProvider == CalendarProvider.LOCAL))
        }

        var pushed = 0
        for (item in pending) {
            val event = item.event
            // Отправленное состояние и есть новый предок — после подтверждения провайдера.
            val sent = SyncSnapshot.of(event)
            when {
                item.isDeleted -> {
                    // Tombstone: удалить в облаке (если событие там есть) и локально
                    event.remoteId?.let { remote.delete(it) }
                    eventRepository.purge(event.id)
                    reminderScheduler.cancel(event)
                    pushed++
                }

                event.remoteId == null -> {
                    // Новое локальное событие -> create в облаке и привязка к провайдеру
                    val remoteId = remote.insert(event)
                    eventRepository.attachToProvider(event.id, remoteId, provider, sent)
                    reminderScheduler.schedule(event)
                    pushed++
                }

                else -> {
                    // Локальное изменение существующего события -> update в облаке
                    remote.update(event.remoteId, event)
                    eventRepository.markSynced(event.id, sent)
                    reminderScheduler.schedule(event)
                    pushed++
                }
            }
        }
        return pushed
    }
}
