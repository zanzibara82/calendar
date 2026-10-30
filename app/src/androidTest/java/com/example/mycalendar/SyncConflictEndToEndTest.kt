package com.example.mycalendar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mycalendar.data.audio.VoiceNoteStorage
import com.example.mycalendar.data.local.MyCalendarDatabase
import com.example.mycalendar.data.remote.RemoteCalendarDataSource
import com.example.mycalendar.data.remote.RemoteDataSourceProvider
import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.data.remote.RemoteFetch
import com.example.mycalendar.data.repository.ConflictRepositoryImpl
import com.example.mycalendar.data.repository.EventRepositoryImpl
import com.example.mycalendar.data.repository.SyncConflictResolver
import com.example.mycalendar.data.repository.SyncRepositoryImpl
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot
import com.example.mycalendar.domain.model.ThemeMode
import com.example.mycalendar.domain.model.UserAccount
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.CalendarConnection
import com.example.mycalendar.domain.repository.ConflictRepository
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import com.example.mycalendar.domain.usecase.DeletionChoice
import com.example.mycalendar.domain.usecase.ResolveConflictUseCase
import com.example.mycalendar.work.ReminderScheduler
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Сквозной сценарий ТЗ §21.9: пересечение правок не приводит к незаметной потере.
 *
 * Собирается вручную, без Hilt: настоящая Room в памяти, настоящие репозитории, настоящий движок
 * синхронизации и настоящий планировщик напоминаний. Подменено ровно одно — облако (`FakeRemote`).
 */
@RunWith(AndroidJUnit4::class)
class SyncConflictEndToEndTest {

    private lateinit var db: MyCalendarDatabase
    private lateinit var events: EventRepository
    private lateinit var conflicts: ConflictRepository
    private lateinit var sync: SyncRepositoryImpl
    private lateinit var resolve: ResolveConflictUseCase
    private lateinit var voiceNotes: VoiceNoteStorage
    private lateinit var scheduler: ReminderScheduler
    private val cloud = FakeRemote()
    private val settings = FakeSettings()

    private companion object {
        const val COLLECTION = "https://caldav.example/events-1/"
    }

    private val base = SyncSnapshot(
        title = "Общее название",
        description = "Общее описание",
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(5),
        repeatRule = RepeatRule.WEEKLY,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MyCalendarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        voiceNotes = VoiceNoteStorage(context)
        events = EventRepositoryImpl(db.eventDao(), voiceNotes)
        conflicts = ConflictRepositoryImpl(db.syncConflictDao())
        scheduler = ReminderScheduler(context)
        sync = SyncRepositoryImpl(
            eventRepository = events,
            authRepository = FakeAuth(),
            settingsRepository = settings,
            conflictResolver = SyncConflictResolver(),
            conflictRepository = conflicts,
            dataSourceProvider = RemoteDataSourceProvider { provider ->
                if (provider == CalendarProvider.GOOGLE) cloud else null
            },
            reminderScheduler = scheduler,
        )
        resolve = ResolveConflictUseCase(conflicts, events, scheduler)
    }

    @After
    fun tearDown() {
        if (::scheduler.isInitialized) {
            // Настоящий планировщик заказывает будильники в системном AlarmManager: снимаем за собой,
            // иначе заказы теста переживут прогон. `cancel` смотрит только на id и минуты.
            scheduler.cancel(
                Event(
                    id = "e1",
                    title = "",
                    startTime = 0L,
                    endTime = 0L,
                    reminderMinutes = base.reminderMinutes,
                )
            )
        }
        if (::db.isInitialized) db.close()
    }

    /** Синхронизированное событие с предком + локальная правка названия. */
    private suspend fun seedLocallyEdited(title: String) {
        events.saveFromRemote(
            Event(
                id = "e1",
                title = base.title,
                description = base.description,
                startTime = base.startTime,
                endTime = base.endTime,
                reminderMinutes = base.reminderMinutes,
                repeatRule = base.repeatRule,
                calendarProvider = CalendarProvider.GOOGLE,
                remoteId = "r1",
                updatedAt = 10L,
            ),
            syncBase = base,
        )
        events.saveFromUser(events.getEvent("e1")!!.copy(title = title))
    }

    /** Синхронизированное событие с предком и без локальных правок. */
    private suspend fun seedSynced(id: String, remoteId: String) {
        events.saveFromRemote(
            Event(
                id = id,
                title = base.title,
                description = base.description,
                startTime = base.startTime,
                endTime = base.endTime,
                reminderMinutes = base.reminderMinutes,
                repeatRule = base.repeatRule,
                calendarProvider = CalendarProvider.GOOGLE,
                remoteId = remoteId,
                updatedAt = 10L,
            ),
            syncBase = base,
        )
    }

    private fun remoteEvent(snapshot: SyncSnapshot) = RemoteEvent(
        remoteId = "r1",
        title = snapshot.title,
        description = snapshot.description,
        startTime = snapshot.startTime,
        endTime = snapshot.endTime,
        reminderMinutes = snapshot.reminderMinutes,
        repeatRule = snapshot.repeatRule,
        updatedAt = 900L,
    )

    @Test
    fun intersection_keeps_local_value_and_creates_one_conflict() = runBlocking {
        seedLocallyEdited("Моя правка")
        cloud.events += remoteEvent(base.copy(title = "Правка из облака"))

        val result = sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(1, result.conflicts)
        assertEquals("Моя правка", events.getEvent("e1")?.title)
        assertEquals(1, conflicts.observeOpenCount().first())
        val open = conflicts.observeConflicts().first().single()
        assertEquals(ConflictKind.CONTENT, open.kind)
        assertEquals(setOf(SyncField.TITLE), open.contested)
        // Пока конфликт открыт, спорная строка в облако не уходит.
        assertEquals(0, cloud.updateCount)
    }

    @Test
    fun second_pull_of_same_cloud_does_not_duplicate_conflict() = runBlocking {
        seedLocallyEdited("Моя правка")
        cloud.events += remoteEvent(base.copy(title = "Правка из облака"))

        val first = sync.syncProvider(CalendarProvider.GOOGLE)
        val second = sync.syncProvider(CalendarProvider.GOOGLE)

        // Нарушение уникального индекса по eventId движок гасит исключением: без проверки успеха
        // поломка переиспользования id прошла бы незамеченной, счётчик остался бы единицей.
        assertTrue(first.success)
        assertTrue(second.success)
        assertEquals(1, conflicts.observeOpenCount().first())
    }

    @Test
    fun resolving_keeps_local_choice_and_pushes_it() = runBlocking {
        seedLocallyEdited("Моя правка")
        cloud.events += remoteEvent(base.copy(title = "Правка из облака"))
        sync.syncProvider(CalendarProvider.GOOGLE)
        val open = conflicts.observeConflicts().first().single()

        // Пустой выбор: все спорные поля остаются локальными.
        resolve(open, remoteChosen = emptySet())

        assertEquals(0, conflicts.observeOpenCount().first())
        assertEquals("Моя правка", events.getEvent("e1")?.title)
        // Разобранное состояние ещё не в облаке — оно в очереди на отправку.
        assertTrue(events.getPendingSyncEvents().any { it.event.id == "e1" })
        // Разбор ПЕРЕДВИГАЕТ предка: выбранное значение и есть новая согласованная точка.
        val storedBase = SyncSnapshot.fromJson(db.eventDao().getSyncState("e1")?.syncBase)
        assertEquals(SyncSnapshot.of(events.getEvent("e1")!!), storedBase)

        sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(1, cloud.updateCount)
        // В облако ушло именно выбранное пользователем значение, а не облачное.
        assertEquals("Моя правка", cloud.lastUpdated?.title)
        assertFalse(events.getPendingSyncEvents().any { it.event.id == "e1" })
        // Тот же облачный снимок после разбора не воскрешает конфликт.
        assertEquals(0, conflicts.observeOpenCount().first())
    }

    @Test
    fun resolving_with_cloud_values_sends_nothing() = runBlocking {
        seedLocallyEdited("Моя правка")
        cloud.events += remoteEvent(base.copy(title = "Правка из облака"))
        sync.syncProvider(CalendarProvider.GOOGLE)
        val open = conflicts.observeConflicts().first().single()

        // Взято облачное значение единственного спорного поля: строка стала равна облаку.
        resolve(open, remoteChosen = setOf(SyncField.TITLE))

        assertEquals("Правка из облака", events.getEvent("e1")?.title)
        // Подтверждать нечего: отправка подняла бы отметку провайдера без изменений (R66).
        assertFalse(events.getPendingSyncEvents().any { it.event.id == "e1" })
        val storedBase = SyncSnapshot.fromJson(db.eventDao().getSyncState("e1")?.syncBase)
        assertEquals(open.remote, storedBase)

        sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(0, cloud.updateCount)
        assertEquals(0, conflicts.observeOpenCount().first())
    }

    @Test
    fun disjoint_changes_merge_without_touching_the_conflict_queue() = runBlocking {
        seedLocallyEdited("Моя правка")
        cloud.events += remoteEvent(
            base.copy(title = base.title, description = "Облачное описание")
        )

        val result = sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(0, result.conflicts)
        assertEquals("Моя правка", events.getEvent("e1")?.title)
        assertEquals("Облачное описание", events.getEvent("e1")?.description)
        assertEquals(0, conflicts.observeOpenCount().first())
    }

    @Test
    fun cloud_deletion_with_local_edits_keeps_row_until_choice() = runBlocking {
        seedLocallyEdited("Моя правка")
        cloud.events += remoteEvent(base).copy(deleted = true)

        sync.syncProvider(CalendarProvider.GOOGLE)

        assertNotNull(events.getEvent("e1"))
        val open = conflicts.observeConflicts().first().single()
        assertEquals(ConflictKind.REMOTE_DELETED, open.kind)

        resolve.resolveDeletion(open, DeletionChoice.DELETE_EVERYWHERE)

        assertNull(events.getEvent("e1"))
        assertEquals(0, conflicts.observeOpenCount().first())
    }

    @Test
    fun restore_after_cloud_deletion_recreates_event_in_cloud() = runBlocking {
        seedLocallyEdited("Моя правка")
        cloud.events += remoteEvent(base).copy(deleted = true)
        sync.syncProvider(CalendarProvider.GOOGLE)
        val open = conflicts.observeConflicts().first().single()

        resolve.resolveDeletion(open, DeletionChoice.RESTORE_FROM_CLOUD)

        val restored = events.getEvent("e1")
        assertNotNull(restored)
        // В облаке события уже нет: push обязан создать его заново, а не обновлять.
        assertNull(restored!!.remoteId)
        assertEquals("Общее название", restored.title)
        assertEquals(0, conflicts.observeOpenCount().first())

        sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(1, cloud.insertCount)
        // Создано событие с содержимым возвращённой строки.
        assertEquals("Общее название", cloud.lastInserted?.title)
        assertEquals("r-new", events.getEvent("e1")?.remoteId)
        assertEquals(0, conflicts.observeOpenCount().first())
    }

    @Test
    fun restore_after_local_tombstone_keeps_remote_id_and_row() = runBlocking {
        seedLocallyEdited("Моя правка")
        events.markDeleted("e1")
        cloud.events += remoteEvent(base.copy(title = "Правка из облака"))

        sync.syncProvider(CalendarProvider.GOOGLE)
        val open = conflicts.observeConflicts().first().single()
        assertEquals(ConflictKind.LOCAL_DELETED, open.kind)

        resolve.resolveDeletion(open, DeletionChoice.RESTORE_FROM_CLOUD)

        val restored = events.getEvent("e1")
        assertNotNull(restored)
        // Строка вернулась из облака: tombstone снят, внешний идентификатор сохранён.
        assertEquals("r1", restored!!.remoteId)
        assertEquals("Правка из облака", restored.title)
        assertEquals(0, conflicts.observeOpenCount().first())

        sync.syncProvider(CalendarProvider.GOOGLE)

        // Строка уже равна облаку: подтверждающего update нет (R66).
        assertEquals(0, cloud.updateCount)
        assertEquals(0, conflicts.observeOpenCount().first())
    }

    @Test
    fun local_tombstone_without_cloud_change_deletes_in_cloud() = runBlocking {
        seedLocallyEdited("Моя правка")
        events.markDeleted("e1")
        cloud.events += remoteEvent(base)

        val result = sync.syncProvider(CalendarProvider.GOOGLE)

        // Удаление не спорно: локальный tombstone уходит в облако молча, очередь пуста.
        assertEquals(0, result.conflicts)
        assertEquals("r1", cloud.deletedRemoteId)
        assertNull(events.getEvent("e1"))
        assertEquals(0, conflicts.observeOpenCount().first())
    }

    @Test
    fun delete_everywhere_removes_voice_file_with_row() = runBlocking {
        seedLocallyEdited("Моя правка")
        val file = voiceNotes.fileFor("e1").apply { writeText("запись") }
        events.saveFromUser(events.getEvent("e1")!!.copy(voiceNotePath = file.absolutePath))
        cloud.events += remoteEvent(base).copy(deleted = true)
        sync.syncProvider(CalendarProvider.GOOGLE)
        val open = conflicts.observeConflicts().first().single()

        resolve.resolveDeletion(open, DeletionChoice.DELETE_EVERYWHERE)

        assertNull(events.getEvent("e1"))
        assertFalse(file.exists())
        assertEquals(0, conflicts.observeOpenCount().first())
    }

    @Test
    fun concurrent_syncs_of_one_provider_keep_one_conflict_row() = runBlocking {
        seedLocallyEdited("Моя правка")
        cloud.events += remoteEvent(base.copy(title = "Правка из облака"))

        val results = coroutineScope {
            val first = async { sync.syncProvider(CalendarProvider.GOOGLE) }
            val second = async { sync.syncProvider(CalendarProvider.GOOGLE) }
            listOf(first.await(), second.await())
        }

        // Очередь пишется без транзакции, поэтому проходы одного провайдера сериализует мьютекс:
        // без него вторая вставка падала бы на уникальном индексе по eventId.
        assertTrue(results.all { it.success })
        assertEquals(1, conflicts.observeOpenCount().first())
    }

    /** R63: выбор «удалить везде» обязан доехать до облака, а не воспроизводить конфликт. */
    @Test
    fun delete_everywhere_after_cloud_edit_reaches_cloud() = runBlocking {
        seedLocallyEdited("Моя правка")
        events.markDeleted("e1")
        cloud.events += remoteEvent(base.copy(title = "Правка из облака"))

        sync.syncProvider(CalendarProvider.GOOGLE)
        val open = conflicts.observeConflicts().first().single()
        assertEquals(ConflictKind.LOCAL_DELETED, open.kind)

        resolve.resolveDeletion(open, DeletionChoice.DELETE_EVERYWHERE)

        // Разбор сдвинул предка на облако: tombstone ждёт отправки, очередь пуста.
        assertEquals(0, conflicts.observeOpenCount().first())
        assertTrue(events.getPendingSyncEvents().any { it.event.id == "e1" })

        val result = sync.syncProvider(CalendarProvider.GOOGLE)

        // Второй проход не повторяет конфликт: облачная копия удалена, строка снята.
        assertEquals(0, result.conflicts)
        assertEquals("r1", cloud.deletedRemoteId)
        assertNull(events.getEvent("e1"))
        assertEquals(0, conflicts.observeOpenCount().first())
    }

    /** R65: разбор по полям не принимает конфликт-удаление. */
    @Test
    fun field_resolution_refuses_deletion_conflict() = runBlocking {
        seedLocallyEdited("Моя правка")
        cloud.events += remoteEvent(base).copy(deleted = true)
        sync.syncProvider(CalendarProvider.GOOGLE)
        val deletion = conflicts.observeConflicts().first().single()
        assertEquals(ConflictKind.REMOTE_DELETED, deletion.kind)

        var rejected = false
        try {
            resolve(deletion, remoteChosen = emptySet())
        } catch (expected: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        // Разбор не состоялся: строка на месте, очередь не тронута.
        assertNotNull(events.getEvent("e1"))
        assertEquals(1, conflicts.observeOpenCount().first())
    }

    /** R65: строку удалили, пока конфликт висел, — воскрешать её разбором нельзя. */
    @Test
    fun field_resolution_refuses_tombstoned_row() = runBlocking {
        seedLocallyEdited("Моя правка")
        cloud.events += remoteEvent(base.copy(title = "Правка из облака"))
        sync.syncProvider(CalendarProvider.GOOGLE)
        val open = conflicts.observeConflicts().first().single()
        assertEquals(ConflictKind.CONTENT, open.kind)

        // Пользователь удалил событие из списка, конфликт остался в очереди.
        events.markDeleted("e1")

        var rejected = false
        try {
            resolve(open, remoteChosen = setOf(SyncField.TITLE))
        } catch (expected: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        // Строка осталась tombstone'ом: локальное удаление не потеряно.
        assertTrue(db.eventDao().getEvent("e1")?.isDeleted == true)
        assertEquals(1, conflicts.observeOpenCount().first())
    }

    /** CalDAV не присылает tombstone'ов: отсутствие id в полной выборке — это удаление. */
    @Test
    fun event_absent_from_full_report_is_purged() = runBlocking {
        seedSynced("e1", "r1")
        seedSynced("e2", "r2")
        cloud.events += remoteEvent(base).copy(remoteId = "r2")
        cloud.presentRemoteIds = setOf("r2")

        val result = sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(1, result.pulled)
        assertEquals(0, result.conflicts)
        assertNull(events.getEvent("e1"))
        assertNotNull(events.getEvent("e2"))
    }

    /** Исчезновение с локальными правками — не молчаливая потеря, а конфликт удаления. */
    @Test
    fun absent_event_with_local_edits_becomes_deletion_conflict() = runBlocking {
        seedLocallyEdited("Моя правка")
        seedSynced("e2", "r2")
        cloud.events += remoteEvent(base).copy(remoteId = "r2")
        cloud.presentRemoteIds = setOf("r2")

        val result = sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(1, result.conflicts)
        assertEquals("Моя правка", events.getEvent("e1")?.title)
        assertEquals(ConflictKind.REMOTE_DELETED, conflicts.observeConflicts().first().single().kind)
    }

    /** «Создать заново» по исчезнувшему событию берёт последний согласованный снимок, не пустоту. */
    @Test
    fun restore_of_vanished_event_uses_last_known_snapshot() = runBlocking {
        seedLocallyEdited("Моя правка")
        seedSynced("e2", "r2")
        cloud.events += remoteEvent(base).copy(remoteId = "r2")
        cloud.presentRemoteIds = setOf("r2")
        sync.syncProvider(CalendarProvider.GOOGLE)
        val open = conflicts.observeConflicts().first().single()

        resolve.resolveDeletion(open, DeletionChoice.RESTORE_FROM_CLOUD)

        val restored = events.getEvent("e1")
        assertNotNull(restored)
        assertEquals("Общее название", restored!!.title)
        assertEquals(base.description, restored.description)
        assertEquals(base.startTime, restored.startTime)
        // В облаке строки нет: push создаст её заново.
        assertNull(restored.remoteId)
        assertEquals(0, conflicts.observeOpenCount().first())
    }

    /** Удаление последнего события: пустая выборка той же коллекции — это «удалено всё». */
    @Test
    fun empty_report_of_same_collection_purges_last_event() = runBlocking {
        seedSynced("e1", "r1")
        cloud.collectionUrl = COLLECTION
        settings.storedCollectionUrl = COLLECTION
        cloud.presentRemoteIds = emptySet()

        val result = sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(1, result.pulled)
        assertNull(events.getEvent("e1"))
    }

    /**
     * Сценарий живого прогона: первый проход после подключения лишь запоминает коллекцию
     * и пустому ответу не верит, второй — вычищает событие, удалённое на сервере.
     */
    @Test
    fun first_sync_after_connect_pins_collection_and_second_purges_absent_event() = runBlocking {
        seedSynced("e1", "r1")
        cloud.collectionUrl = COLLECTION
        settings.storedCollectionUrl = null
        cloud.presentRemoteIds = emptySet()

        val first = sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(0, first.pulled)
        assertNotNull(events.getEvent("e1"))
        assertEquals(COLLECTION, settings.storedCollectionUrl)

        val second = sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(1, second.pulled)
        assertNull(events.getEvent("e1"))
    }

    /** Пустой ответ ДРУГОЙ коллекции (сменился аккаунт) не трогает привязки и не переписывает URL. */
    @Test
    fun empty_report_of_other_collection_keeps_rows() = runBlocking {
        seedSynced("e1", "r1")
        cloud.collectionUrl = "https://caldav.example/other/"
        settings.storedCollectionUrl = COLLECTION
        cloud.presentRemoteIds = emptySet()

        val result = sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(0, result.pulled)
        assertNotNull(events.getEvent("e1"))
        assertEquals(COLLECTION, settings.storedCollectionUrl)
    }

    /** Выборка не пересекается с привязками — это чужая коллекция, а не удаление всего. */
    @Test
    fun disjoint_report_does_not_purge_anything() = runBlocking {
        seedSynced("e1", "r1")
        cloud.presentRemoteIds = setOf("r-other")

        val result = sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(0, result.pulled)
        assertEquals(0, result.conflicts)
        assertNotNull(events.getEvent("e1"))
    }

    /** Провайдер не гарантирует полноту выборки — детект по отсутствию выключен. */
    @Test
    fun null_collection_composition_disables_absence_detection() = runBlocking {
        seedSynced("e1", "r1")
        cloud.presentRemoteIds = null

        val result = sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(0, result.pulled)
        assertNotNull(events.getEvent("e1"))
    }
}

/** Облако-фикс: список событий и счётчики вызовов — без сети и без системного календаря. */
private class FakeRemote : RemoteCalendarDataSource {
    val events = mutableListOf<RemoteEvent>()

    /** Состав коллекции для детекта удалений по отсутствию; null — провайдер его не отдаёт. */
    var presentRemoteIds: Set<String>? = null

    /** Коллекция, из которой пришла выборка; null — провайдер URL не сообщает. */
    var collectionUrl: String? = null
    var updateCount = 0
    var insertCount = 0
    var deletedRemoteId: String? = null
    var lastUpdated: Event? = null
    var lastInserted: Event? = null

    override suspend fun fetchUpdatedSince(sinceMillis: Long): RemoteFetch =
        RemoteFetch(events.toList(), presentRemoteIds, collectionUrl)

    override suspend fun insert(event: Event): String {
        insertCount++
        lastInserted = event
        return "r-new"
    }

    override suspend fun update(remoteId: String, event: Event) {
        updateCount++
        lastUpdated = event
    }

    override suspend fun delete(remoteId: String) {
        deletedRemoteId = remoteId
    }
}

private class FakeAuth : AuthRepository {
    // syncProvider проверяет наличие аккаунта ДО doSync: без него тест вернул бы
    // «Календарь GOOGLE не подключён» и до слияния не дошёл.
    private val account = UserAccount(CalendarProvider.GOOGLE, "test@example.com")

    override val connections: Flow<List<CalendarConnection>> = flowOf(
        listOf(CalendarConnection(CalendarProvider.GOOGLE, account))
    )

    override suspend fun accountFor(provider: CalendarProvider): UserAccount? =
        account.takeIf { provider == CalendarProvider.GOOGLE }

    override suspend fun connectedProviders(): List<CalendarProvider> =
        listOf(CalendarProvider.GOOGLE)

    override suspend fun connectGoogleCalendar(): Result<UserAccount> =
        Result.failure(UnsupportedOperationException())

    override suspend fun signInWithYandex(): Result<UserAccount> =
        Result.failure(UnsupportedOperationException())

    override suspend fun signOut(provider: CalendarProvider) {}
    override suspend fun signOutAll() {}
    override suspend fun getAccessToken(provider: CalendarProvider, forceRefresh: Boolean): String? = null
}

private class FakeSettings : SettingsRepository {
    override val themeMode: Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
    override val dynamicColor: Flow<Boolean> = flowOf(false)
    override val lastSyncTime: Flow<Long> = flowOf(0L)
    override val notificationsAsked: Flow<Boolean> = flowOf(false)
    override val onboardingCompleted: Flow<Boolean> = flowOf(true)

    override suspend fun setThemeMode(mode: ThemeMode) {}
    override suspend fun setDynamicColor(enabled: Boolean) {}
    override suspend fun setLastSyncTime(timeMillis: Long) {}
    override suspend fun setNotificationsAsked() {}
    override suspend fun setOnboardingCompleted(completed: Boolean) {}

    override fun lastSyncTime(provider: CalendarProvider): Flow<Long> = flowOf(0L)
    override suspend fun setLastSyncTime(provider: CalendarProvider, timeMillis: Long) {}

    /** Что помнит приложение о коллекции провайдера между проходами. */
    var storedCollectionUrl: String? = null

    override fun collectionUrl(provider: CalendarProvider): Flow<String?> = flowOf(storedCollectionUrl)
    override suspend fun setCollectionUrl(provider: CalendarProvider, url: String) {
        storedCollectionUrl = url
    }

    override suspend fun clearCollectionUrl(provider: CalendarProvider) {
        storedCollectionUrl = null
    }
}
