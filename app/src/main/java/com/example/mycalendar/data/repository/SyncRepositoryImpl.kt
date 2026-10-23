package com.example.mycalendar.data.repository

import com.example.mycalendar.data.remote.RemoteCalendarDataSource
import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.data.remote.toEvent
import com.example.mycalendar.data.remote.yandex.YandexCalendarDataSource
import com.example.mycalendar.data.system.SystemCalendarDataSource
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import com.example.mycalendar.domain.repository.SyncRepository
import com.example.mycalendar.domain.repository.SyncResult
import com.example.mycalendar.work.ReminderScheduler
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Двусторонняя синхронизация локальной БД с календарями провайдеров.
 *
 * Провайдеры НЕЗАВИСИМЫ: событие принадлежит одному из них (поле calendarProvider),
 * поэтому Google и Яндекс могут быть подключены одновременно и синхронизироваться
 * параллельно, не затирая друг друга.
 *
 * Алгоритм для одного провайдера (за одну итерацию):
 *
 *  PULL: события провайдера, изменённые с момента его lastSyncTime
 *    (системный календарь не отдаёт время изменения — читаются все события,
 *     а конфликт решается по содержимому, см. [sameContent]):
 *    - нет локальной пары (по remoteId)      -> вставить локально;
 *    - локальная пара есть                   -> last-write-wins по updatedAt;
 *      - удалённая версия новее              -> перезаписать локальную;
 *      - локальная версия новее              -> пометить pendingSync (уйдёт в push).
 *
 *  PUSH: локальные записи с pendingSync = true, принадлежащие ЭТОМУ провайдеру:
 *    - tombstone (isDeleted)                 -> удалить в облаке + hardDelete локально;
 *    - без remoteId                          -> insert в облако + сохранить remoteId;
 *    - с remoteId                            -> update в облаке.
 *
 *  Исключение: если подключён РОВНО ОДИН провайдер, в него также уходят локальные
 *  (LOCAL) события — так поведение не отличается от единственного календаря,
 *  а при двух подключённых локальные события не дублируются в оба облака.
 *
 *  Финал: lastSyncTime провайдера = now; напоминания перепланируются для затронутых
 *  событий. Голосовые заметки НЕ синхронизируются (файлы остаются на устройстве).
 */
@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val eventRepository: EventRepository,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val conflictResolver: SyncConflictResolver,
    private val systemCalendarDataSource: SystemCalendarDataSource,
    private val yandexCalendarDataSource: YandexCalendarDataSource,
    private val reminderScheduler: ReminderScheduler,
) : SyncRepository {

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

        // Провайдер мог быть отключён — проверяем перед запросом токена
        val account = authRepository.accountFor(provider)
            ?: return SyncResult.failure(provider, "Календарь ${provider.name} не подключён")

        val remote: RemoteCalendarDataSource = when (provider) {
            // Google идёт через СИСТЕМНЫЙ календарь Android (CalendarContract):
            // OAuth-клиент и регистрация в Google Cloud Console не нужны.
            CalendarProvider.GOOGLE -> systemCalendarDataSource
            CalendarProvider.YANDEX -> yandexCalendarDataSource
            CalendarProvider.LOCAL -> return SyncResult.nothingConnected()
        }

        return try {
            val result = doSync(remote, provider)
            settingsRepository.setLastSyncTime(provider, System.currentTimeMillis())
            result
        } catch (e: Exception) {
            SyncResult.failure(provider, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun doSync(
        remote: RemoteCalendarDataSource,
        provider: CalendarProvider,
    ): SyncResult {
        var pushed = 0
        var pulled = 0
        var conflicts = 0
        val pulledIds = mutableListOf<String>()

        // Время последней синхронизации ИМЕННО этого провайдера
        val since = settingsRepository.lastSyncTime(provider).first()

        // Провайдер без updatedAt (системный календарь) разрешает конфликты
        // по содержимому, поэтому список локальных изменений нужен заранее —
        // именно он отвечает на вопрос «кто изменился».
        val pendingIds = if (remote.hasReliableUpdatedAt) {
            emptySet()
        } else {
            eventRepository.getPendingSyncEvents().map { it.event.id }.toSet()
        }

        // ---------- PULL ----------
        val remoteEvents = remote.fetchUpdatedSince(if (since > 0) since else 0L)
        for (remoteEvent in remoteEvents) {
            val incoming = remoteEvent.toEvent(provider)
            val local = eventRepository.findByRemoteId(provider, remoteEvent.remoteId)

            // Удалённое на удалённой стороне событие. Системный календарь помечает
            // такие строки DELETED=1 — их можно вычистить локально. REST-провайдеры
            // (Google API: status=cancelled) пока пропускаем, как и раньше.
            if (remoteEvent.deleted) {
                if (!remote.hasReliableUpdatedAt && local != null) {
                    eventRepository.hardDelete(local.id)
                    reminderScheduler.cancel(local)
                    pulled++
                }
                continue
            }

            when {
                // Новое событие на стороне облака
                local == null -> {
                    eventRepository.saveFromRemote(incoming)
                    pulled++
                    pulledIds += incoming.id
                }

                // Провайдер без updatedAt: сравниваем по содержимому
                !remote.hasReliableUpdatedAt -> when {
                    // Ничего не изменилось — не трогаем запись вообще
                    sameContent(local, remoteEvent) -> Unit

                    // Локально есть неотправленные изменения -> уйдёт в push
                    local.id in pendingIds -> conflicts++

                    // Изменилось только в календаре -> забираем удалённую версию
                    else -> {
                        eventRepository.saveFromRemote(incoming.copy(id = local.id))
                        pulled++
                        conflicts++
                        pulledIds += local.id
                    }
                }

                // Конфликт: изменены и локально, и в облаке -> last-write-wins
                conflictResolver.resolve(local, remoteEvent) == SyncConflictResolver.Resolution.TAKE_REMOTE -> {
                    eventRepository.saveFromRemote(incoming.copy(id = local.id))
                    pulled++
                    conflicts++
                    pulledIds += local.id
                }

                // Локальная версия новее: уйдёт в облако в push-фазе
                else -> {
                    eventRepository.markPendingSync(local.id)
                    conflicts++
                }
            }
        }

        // ---------- PUSH ----------
        pushed = push(remote, provider)

        // Напоминания для событий, приехавших из облака
        pulledIds.distinct().forEach { id ->
            eventRepository.getEvent(id)?.let { reminderScheduler.schedule(it) }
        }

        return SyncResult(
            success = true, pushed = pushed, pulled = pulled,
            conflicts = conflicts, provider = provider,
        )
    }

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

        val pending = eventRepository.getPendingSyncEvents().filter { item ->
            item.event.calendarProvider == provider ||
                (includeLocal && item.event.calendarProvider == CalendarProvider.LOCAL)
        }

        var pushed = 0
        for (item in pending) {
            val event = item.event
            when {
                item.isDeleted -> {
                    // Tombstone: удалить в облаке (если событие там есть) и локально
                    event.remoteId?.let { remote.delete(it) }
                    eventRepository.hardDelete(event.id)
                    reminderScheduler.cancel(event)
                    pushed++
                }

                event.remoteId == null -> {
                    // Новое локальное событие -> create в облаке и привязка к провайдеру
                    val remoteId = remote.insert(event)
                    eventRepository.attachToProvider(event.id, remoteId, provider)
                    reminderScheduler.schedule(event)
                    pushed++
                }

                else -> {
                    // Локальное изменение существующего события -> update в облаке
                    remote.update(event.remoteId, event)
                    eventRepository.markSynced(listOf(event.id))
                    reminderScheduler.schedule(event)
                    pushed++
                }
            }
        }
        return pushed
    }

    /**
     * Совпадает ли локальная запись с удалённой по СОДЕРЖИМОМУ.
     *
     * Нужно для провайдеров без updatedAt (системный календарь): сравнивать
     * версии по времени там нечем, поэтому сравниваются зна́чимые поля.
     * updatedAt намеренно исключён — у системного календаря это момент
     * наблюдения, а не реальное время изменения.
     */
    private fun sameContent(local: Event, remote: RemoteEvent): Boolean =
        local.title == remote.title &&
            local.description.orEmpty() == remote.description.orEmpty() &&
            local.startTime == remote.startTime &&
            local.endTime == remote.endTime &&
            local.reminderMinutes.sorted() == remote.reminderMinutes.sorted() &&
            local.repeatRule == remote.repeatRule
}
