# Сохранность данных при конфликтах синхронизации — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ни одна правка события не теряется молча: синхронизация знает общего предка, сама объединяет непересекающиеся поля, а спорные кладёт в очередь на разбор пользователем.

**Architecture:** к локальной строке события добавляется снимок-предок `syncBase` (JSON по шести синхронизируемым полям). Решение по каждому событию из облака принимает чистая функция `SyncConflictResolver.decide(...) -> PullDecision`; она же классифицирует оба вида конфликтов-удалений. Неясные случаи уходят в таблицу `sync_conflicts` и на экран разбора по полям. Файл голосовой заметки живёт ровно столько, сколько строка БД.

**Tech Stack:** Kotlin 2.2.10, Room 2.8.2 (KSP), kotlinx.serialization 1.9.0, Hilt 2.57.1, Jetpack Compose + Material 3, JUnit 4 (JVM), AndroidX Test + Room MigrationTestHelper (androidTest).

**Spec:** [docs/superpowers/specs/2026-10-23-sync-conflict-safety-design.md](../specs/2026-10-23-sync-conflict-safety-design.md) — там же разделы 1–5, от которых спорит этот план. Плюс ТЗ SyncPlanner §7 и §21.9.

## Global Constraints

- Kotlin `jvmTarget = 17`, `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36`, AGP 9.4.0 — не менять.
- Версии зависимостей только из `gradle/libs.versions.toml`; новая библиотека в этом плане одна — `androidx.room:room-testing` (только `androidTestImplementation`).
- Никаких секретов и токенов в коде и в тестах.
- Тесты запускать только через Gradle (`./gradlew` в Git Bash), не сырым `javac`/`java`.
- Текст для пользователя — в `app/src/main/res/values/strings.xml`, на русском; в Compose только `stringResource`.
- KDoc и комментарии — по-русски, как во всём проекте; по умолчанию комментарий нет, если из имени неясно «зачем».
- `git config` не трогать. Коммиты — только когда пользователь явно согласился на коммиты в этом репозитории (на момент написания плана правка `appliedTo` ещё не закоммичена).
- Задачи 2, 8, 9, 10 включают androidTest/проверку в UI — для них нужен эмулятор или устройство (`adb devices`). Если их нет, в докладе задачи прямо писать «тест не запущен», а не «зелёно».

## Карта файлов

Создаём:

- `app/src/main/java/com/example/mycalendar/domain/model/SyncSnapshot.kt` — снимок синхронизируемых полей, JSON-кодек, diff, наложение.
- `app/src/main/java/com/example/mycalendar/domain/model/SyncedRecord.kt` — локальная запись глазами синхронизации (событие + флаги + предок).
- `app/src/main/java/com/example/mycalendar/domain/model/SyncConflict.kt` — открытый конфликт + `ConflictKind`.
- `app/src/main/java/com/example/mycalendar/domain/repository/ConflictRepository.kt` — контракт очереди конфликтов.
- `app/src/main/java/com/example/mycalendar/domain/repository/VoiceNoteCleanup.kt` — контракт удаления файла заметки (нужен, чтобы репозиторий был тестируем без Android).
- `app/src/main/java/com/example/mycalendar/domain/usecase/ResolveConflictUseCase.kt` — разбор конфликта.
- `app/src/main/java/com/example/mycalendar/data/local/SyncConflictEntity.kt` — таблица + маппинг.
- `app/src/main/java/com/example/mycalendar/data/local/SyncConflictDao.kt` — запросы очереди.
- `app/src/main/java/com/example/mycalendar/data/repository/ConflictRepositoryImpl.kt` — реализация поверх DAO.
- `app/src/main/java/com/example/mycalendar/data/remote/RemoteDataSourceProvider.kt` — точка подмены источника в тестах.
- `app/src/main/java/com/example/mycalendar/data/remote/CalendarDataSourceProvider.kt` — реализация (системный календарь / CalDAV).
- `app/src/main/java/com/example/mycalendar/presentation/conflict/ConflictListScreen.kt`, `ConflictListViewModel.kt`
- `app/src/main/java/com/example/mycalendar/presentation/conflict/ConflictDetailScreen.kt`, `ConflictDetailViewModel.kt`
- тесты: `SyncSnapshotTest.kt`, `EventRepositorySyncStateTest.kt`, `ConflictRepositoryImplTest.kt`, `ResolveConflictRuleTest.kt` (JVM); `Migration2to3Test.kt`, `SyncConflictEndToEndTest.kt` (androidTest).

Меняем: `EventEntity.kt`, `EventDao.kt`, `MyCalendarDatabase.kt`, `di/DatabaseModule.kt`, `di/RepositoryModule.kt`,
`data/audio/VoiceNoteStorage.kt`,
`domain/repository/EventRepository.kt`, `data/repository/EventRepositoryImpl.kt`,
`data/repository/SyncConflictResolver.kt`, `data/repository/SyncRepositoryImpl.kt`,
`domain/usecase/EventUseCases.kt`, `presentation/settings/SettingsViewModel.kt`, `SettingsScreen.kt`,
`presentation/navigation/Screen.kt`, `AppNavGraph.kt`, `res/values/strings.xml`, `app/build.gradle.kts`,
`gradle/libs.versions.toml`, `test/SyncConflictResolverTest.kt`.

---

### Task 1: Снимок-предок SyncSnapshot

**Files:**
- Create: `app/src/main/java/com/example/mycalendar/domain/model/SyncSnapshot.kt`
- Test: `app/src/test/java/com/example/mycalendar/domain/model/SyncSnapshotTest.kt`

**Interfaces:**
- Consumes: `Event`, `RepeatRule` из `domain/model/Event.kt` (не меняются).
- Produces: `enum class SyncField { TITLE, DESCRIPTION, START_TIME, END_TIME, REMINDER_MINUTES, REPEAT_RULE }`;
  `@Serializable data class SyncSnapshot(title: String, description: String?, startTime: Long, endTime: Long, reminderMinutes: List<Int>, repeatRule: RepeatRule?)`
  с `fun toJson(): String`, `fun appliedTo(event: Event, fields: Set<SyncField>): Event`,
  `fun fieldOf(field: SyncField): Any?`,
  `SyncSnapshot.fromJson(raw: String?): SyncSnapshot?`, `SyncSnapshot.of(event: Event): SyncSnapshot`;
  top-level `fun diffFields(base: SyncSnapshot, other: SyncSnapshot): Set<SyncField>`.

- [ ] **Step 1: Написать падающий тест**

`app/src/test/java/com/example/mycalendar/domain/model/SyncSnapshotTest.kt`:

```kotlin
package com.example.mycalendar.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Снимок-предок — база для трёхстороннего слияния: он переживает JSON,
 * не видит разницы в порядке напоминаний и умеет налагать выбранные поля.
 */
class SyncSnapshotTest {

    private val base = SyncSnapshot(
        title = "Встреча с мамой",
        description = "Купить молоко и позвонить\nмаме, через 2 дня",
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(15, 5),
        repeatRule = RepeatRule.WEEKLY,
    )

    private val event = Event(
        id = "local-1",
        title = "Встреча с мамой",
        description = base.description,
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(15, 5),
        repeatRule = RepeatRule.WEEKLY,
        textNote = "Локальная заметка",
    )

    @Test
    fun `snapshot survives json round trip`() {
        assertEquals(base, SyncSnapshot.fromJson(base.toJson()))
    }

    @Test
    fun `absent snapshot parses as null`() {
        assertNull(SyncSnapshot.fromJson(null))
    }

    @Test
    fun `garbage snapshot parses as null instead of throwing`() {
        assertNull(SyncSnapshot.fromJson("{не json"))
    }

    @Test
    fun `reminder order is not a difference`() {
        assertEquals(
            emptySet<SyncField>(),
            diffFields(base, base.copy(reminderMinutes = listOf(5, 15))),
        )
    }

    @Test
    fun `missing and blank description are the same`() {
        assertEquals(
            emptySet<SyncField>(),
            diffFields(base.copy(description = null), base.copy(description = "")),
        )
    }

    @Test
    fun `diff lists only changed fields`() {
        assertEquals(
            setOf(SyncField.START_TIME, SyncField.REPEAT_RULE),
            diffFields(base, base.copy(startTime = 3_000L, repeatRule = RepeatRule.DAILY)),
        )
    }

    @Test
    fun `apply replaces only chosen fields`() {
        val cloud = base.copy(title = "Перенос встречи", startTime = 9_000L)

        val merged = cloud.appliedTo(event, setOf(SyncField.TITLE))

        assertEquals("Перенос встречи", merged.title)
        assertEquals(1_000L, merged.startTime)
        assertEquals("Локальная заметка", merged.textNote)
    }

    @Test
    fun `apply of no fields keeps event untouched`() {
        assertEquals(event, base.appliedTo(event, emptySet()))
    }

    @Test
    fun `of reads only syncable fields`() {
        assertEquals(base, SyncSnapshot.of(event))
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `./gradlew :app:testDebugUnitTest --tests "*SyncSnapshotTest*"`
Expected: FAILURE на компиляции — `Unresolved reference: SyncSnapshot` (в Kotlin это и есть «красный» шаг).

- [ ] **Step 3: Написать реализацию**

`app/src/main/java/com/example/mycalendar/domain/model/SyncSnapshot.kt`:

```kotlin
package com.example.mycalendar.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Синхронизируемое поле события — единица спора при конфликте. */
enum class SyncField {
    TITLE,
    DESCRIPTION,
    START_TIME,
    END_TIME,
    REMINDER_MINUTES,
    REPEAT_RULE,
}

/**
 * Снимок синхронизируемых полей — «предок»: общее состояние локальной записи
 * и облака на момент последней успешной синхронизации.
 *
 * Метки, текстовая и голосовая заметки в снимок НЕ входят принципиально: облако
 * их не хранит и не возвращает, поэтому спорными они быть не могут (см. Event.appliedTo).
 */
@Serializable
data class SyncSnapshot(
    val title: String,
    val description: String? = null,
    val startTime: Long,
    val endTime: Long,
    val reminderMinutes: List<Int> = emptyList(),
    val repeatRule: RepeatRule? = null,
) {
    fun toJson(): String = json.encodeToString(this)

    /**
     * Значение одного синхронизируемого поля в сравнительном виде: порядок минут
     * не значим, пустое и отсутствующее описание — одно и то же.
     */
    fun fieldOf(field: SyncField): Any? = when (field) {
        SyncField.TITLE -> title
        SyncField.DESCRIPTION -> description.orEmpty()
        SyncField.START_TIME -> startTime
        SyncField.END_TIME -> endTime
        SyncField.REMINDER_MINUTES -> reminderMinutes.toSet()
        SyncField.REPEAT_RULE -> repeatRule
    }

    /** [event] с значениями полей [fields] из снимка; остальное остаётся локальным. */
    fun appliedTo(event: Event, fields: Set<SyncField>): Event = fields.fold(event) { acc, field ->
        when (field) {
            SyncField.TITLE -> acc.copy(title = title)
            SyncField.DESCRIPTION -> acc.copy(description = description)
            SyncField.START_TIME -> acc.copy(startTime = startTime)
            SyncField.END_TIME -> acc.copy(endTime = endTime)
            SyncField.REMINDER_MINUTES -> acc.copy(reminderMinutes = reminderMinutes)
            SyncField.REPEAT_RULE -> acc.copy(repeatRule = repeatRule)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** null — строка до миграции либо событие, которое ещё ни с чем не синхронизировалось. */
        fun fromJson(raw: String?): SyncSnapshot? =
            raw?.let { runCatching { json.decodeFromString<SyncSnapshot>(it) }.getOrNull() }

        fun of(event: Event): SyncSnapshot = SyncSnapshot(
            title = event.title,
            description = event.description,
            startTime = event.startTime,
            endTime = event.endTime,
            reminderMinutes = event.reminderMinutes,
            repeatRule = event.repeatRule,
        )
    }
}

/**
 * Поля, в которых снимки различаются. Сравнение идёт по [SyncSnapshot.fieldOf]:
 * напоминания — как МНОЖЕСТВО (порядок минут ничего не значит), описание — без
 * различия null/пусто. Тот же примитив фильтрует спорные поля при слиянии.
 */
fun diffFields(base: SyncSnapshot, other: SyncSnapshot): Set<SyncField> =
    SyncField.entries.filter { base.fieldOf(it) != other.fieldOf(it) }.toSet()
```

- [ ] **Step 4: Прогн тесты**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — все прежние 22 теста + 9 новых зелёные (падение отчёта смотреть в `app/build/test-results/testDebugUnitTest/`).

- [ ] **Step 5: Коммит (по согласию пользователя)**

```bash
git add app/src/main/java/com/example/mycalendar/domain/model/SyncSnapshot.kt app/src/test/java/com/example/mycalendar/domain/model/SyncSnapshotTest.kt
git commit -m "feat(sync): снимок-предок SyncSnapshot для трёхстороннего слияния"
```

---

### Task 2: Room v3 — колонка syncBase, таблица sync_conflicts, миграция

**Files:**
- Create: `app/src/main/java/com/example/mycalendar/data/local/SyncConflictEntity.kt`
- Create: `app/src/main/java/com/example/mycalendar/data/local/SyncConflictDao.kt`
- Modify: `app/src/main/java/com/example/mycalendar/data/local/EventEntity.kt` (74-94 — `toEntity`)
- Modify: `app/src/main/java/com/example/mycalendar/data/local/EventDao.kt` (56-77)
- Modify: `app/src/main/java/com/example/mycalendar/data/local/MyCalendarDatabase.kt` (11-27)
- Modify: `app/src/main/java/com/example/mycalendar/di/DatabaseModule.kt` (19-24)
- Modify: `app/build.gradle.kts` (62-68 `ksp`,.dependencies)
- Modify: `gradle/libs.versions.toml` (56-59 блок Room)
- Test: `app/src/androidTest/java/com/example/mycalendar/Migration2to3Test.kt`

**Interfaces:**
- Consumes: колонки таблицы `events` версии 2 (см. `app/schemas/.../2.json`).
- Produces: `EventEntity.syncBase: String?`; `EventDao.getSyncState(id): EventSyncState?`,
  `EventDao.markSynced(id: String, syncBase: String?)`,
  `EventDao.attachToProvider(id: String, remoteId: String, provider: String, syncBase: String?)`;
  `SyncConflictEntity`, `SyncConflictDao` (методы из шага 3), `MyCalendarDatabase.MIGRATION_2_3`,
  `MyCalendarDatabase.syncConflictDao()`.

- [ ] **Step 1: Колонка предка в EventEntity**

В `EventEntity` после `pendingSync` добавить поле, в `toEntity` — параметр:

```kotlin
    val isDeleted: Boolean = false,
    val pendingSync: Boolean = true,
    /** JSON [com.example.mycalendar.domain.model.SyncSnapshot]: общее состояние с облаком. */
    val syncBase: String? = null,
)
```

```kotlin
fun Event.toEntity(
    updatedAt: Long,
    pendingSync: Boolean,
    isDeleted: Boolean = false,
    syncBase: String? = null,
): EventEntity = EventEntity(
    /* существующие поля без изменений */
    isDeleted = isDeleted,
    pendingSync = pendingSync,
    syncBase = syncBase,
)
```

- [ ] **Step 2: Таблица конфликтов**

`app/src/main/java/com/example/mycalendar/data/local/SyncConflictEntity.kt`:

```kotlin
package com.example.mycalendar.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot

/**
 * Открытый конфликт синхронизации: одна строка на событие (уникальный индекс по eventId).
 *
 * Локальную версию НЕ храним — разбираем конфликт против текущего значения строки,
 * иначе правка события во время открытого конфликта осталась бы «проигравшей».
 */
@Entity(
    tableName = "sync_conflicts",
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["eventId"], unique = true)],
)
data class SyncConflictEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    /** Имя [CalendarProvider]: google / yandex. */
    val provider: String,
    val remoteId: String,
    /** Имя [ConflictKind]. */
    val kind: String,
    /** JSON [SyncSnapshot] предка; null — строка до миграции. */
    val base: String?,
    /** JSON [SyncSnapshot] облачной версии. */
    val remote: String,
    /** Имена [SyncField] через запятую; пусто для конфликтов-удалений. */
    val contestedFields: String,
    val detectedAt: Long,
)

private val emptySnapshot = SyncSnapshot(title = "", startTime = 0L, endTime = 0L)

fun SyncConflictEntity.toDomain(): SyncConflict = SyncConflict(
    id = id,
    eventId = eventId,
    provider = runCatching { CalendarProvider.valueOf(provider.uppercase()) }
        .getOrDefault(CalendarProvider.LOCAL),
    remoteId = remoteId,
    kind = runCatching { ConflictKind.valueOf(kind) }.getOrDefault(ConflictKind.CONTENT),
    base = SyncSnapshot.fromJson(base),
    remote = SyncSnapshot.fromJson(remote) ?: emptySnapshot,
    contested = if (contestedFields.isBlank()) emptySet()
    else contestedFields.split(",").mapNotNull { name -> runCatching { SyncField.valueOf(name) }.getOrNull() }.toSet(),
    detectedAt = detectedAt,
)

fun SyncConflict.toEntity(): SyncConflictEntity = SyncConflictEntity(
    id = id,
    eventId = eventId,
    provider = provider.name.lowercase(),
    remoteId = remoteId,
    kind = kind.name,
    base = base?.toJson(),
    remote = remote.toJson(),
    contestedFields = contested.joinToString(","),
    detectedAt = detectedAt,
)
```

`SyncConflict`/`ConflictKind` создаём в этом же коммите — файл `domain/model/SyncConflict.kt` (полный код в шаге 1 Task 4; здесь только enum + data class без маппинга, чтобы Room скомпилировался).

- [ ] **Step 3: DAO конфликтов**

`app/src/main/java/com/example/mycalendar/data/local/SyncConflictDao.kt`:

```kotlin
package com.example.mycalendar.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncConflictDao {

    @Query("SELECT * FROM sync_conflicts ORDER BY detectedAt ASC")
    fun observeAll(): Flow<List<SyncConflictEntity>>

    @Query("SELECT COUNT(*) FROM sync_conflicts")
    fun observeCount(): Flow<Int>

    /** События с открытым конфликтом — их нельзя отправлять в облако до разбора. */
    @Query("SELECT eventId FROM sync_conflicts")
    suspend fun openEventIds(): List<String>

    @Query("SELECT * FROM sync_conflicts WHERE id = :id")
    suspend fun getById(id: String): SyncConflictEntity?

    @Query("SELECT id FROM sync_conflicts WHERE eventId = :eventId LIMIT 1")
    suspend fun idByEvent(eventId: String): String?

    @Upsert
    suspend fun upsert(conflict: SyncConflictEntity)

    @Query("DELETE FROM sync_conflicts WHERE id = :id")
    suspend fun deleteById(id: String)
}
```

- [ ] **Step 4: База данных версия 3 + миграция**

`MyCalendarDatabase.kt` целиком:

```kotlin
@Database(
    entities = [EventEntity::class, SyncConflictEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class MyCalendarDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun syncConflictDao(): SyncConflictDao

    companion object {
        const val DB_NAME = "my_calendar.db"

        /** v2: добавлена колонка tags (метки события: День рождения/Свидание/Кино). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v3: сохранность данных при конфликтах — снимок-предок syncBase и очередь
         * открытых конфликтов. SQL должен совпадать с app/schemas/.../3.json.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN syncBase TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_conflicts (" +
                        "id TEXT NOT NULL, " +
                        "eventId TEXT NOT NULL, " +
                        "provider TEXT NOT NULL, " +
                        "remoteId TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "base TEXT, " +
                        "remote TEXT NOT NULL, " +
                        "contestedFields TEXT NOT NULL, " +
                        "detectedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id), " +
                        "FOREIGN KEY(eventId) REFERENCES events(id) ON UPDATE NO ACTION ON DELETE CASCADE" +
                        ")"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_sync_conflicts_eventId " +
                        "ON sync_conflicts(eventId)"
                )
            }
        }
    }
}
```

`di/DatabaseModule.kt` — провайдер DAO и список миграций:

```kotlin
    @Provides
    fun provideDatabase(@ApplicationContext context: Context): MyCalendarDatabase =
        Room.databaseBuilder(context, MyCalendarDatabase::class.java, MyCalendarDatabase.DB_NAME)
            .addMigrations(MyCalendarDatabase.MIGRATION_1_2, MyCalendarDatabase.MIGRATION_2_3)
            .build()

    @Provides
    fun provideEventDao(database: MyCalendarDatabase): EventDao = database.eventDao()

    @Provides
    fun provideSyncConflictDao(database: MyCalendarDatabase): SyncConflictDao = database.syncConflictDao()
```

- [ ] **Step 5: Запросы предка в EventDao**

Заменить `markSynced(ids)` и `attachToProvider(...)` (там же, строки 59-74) и добавить чтение состояния:

```kotlin
    /** Служебное состояние строки: предок и время изменения — чтобы не вычитывать весь контент. */
    @Query("SELECT syncBase, updatedAt FROM events WHERE id = :id")
    suspend fun getSyncState(id: String): EventSyncState?

    /**
     * Успешный push: предок догоняет отправленное состояние.
     * Больше никаких мест, где syncBase двигает синхронизация, нет (разбор — Task 7).
     */
    @Query("UPDATE events SET pendingSync = 0, syncBase = :syncBase WHERE id = :id")
    suspend fun markSynced(id: String, syncBase: String?)

    @Query(
        "UPDATE events SET remoteId = :remoteId, calendarProvider = :provider, " +
            "pendingSync = 0, syncBase = :syncBase WHERE id = :id"
    )
    suspend fun attachToProvider(id: String, remoteId: String, provider: String, syncBase: String?)
```

```kotlin
/** Две служебные колонки одной строки (запрос [EventDao.getSyncState]). */
data class EventSyncState(val syncBase: String?, val updatedAt: Long)
```

`markPendingSync` пока остаётся (удаляется в Task 3, когда исчезнет единственный вызов).

- [ ] **Step 6: Зависимость и ассеты схемы для теста миграции**

`gradle/libs.versions.toml`, блок Room:

```toml
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
```

`app/build.gradle.kts` — в `android { }` добавить `sourceSets`, в `dependencies { }` — тестовую зависимость:

```kotlin
    sourceSets {
        // MigrationTestHelper читает экспортированные схемы из ассетов теста
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
```

```kotlin
    androidTestImplementation(libs.androidx.room.testing)
```

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; появляется `app/schemas/com.example.mycalendar.data.local.MyCalendarDatabase/3.json`.

- [ ] **Step 7: Тест миграции**

`app/src/androidTest/java/com/example/mycalendar/Migration2to3Test.kt`:

```kotlin
package com.example.mycalendar

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.data.local.MyCalendarDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Миграция 2 -> 3 не должна терять события: старые строки целы, syncBase у них null,
 * таблица конфликтов создана и совпадает со схемой 3.json.
 */
@RunWith(AndroidJUnit4::class)
class Migration2to3Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MyCalendarDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun `migration keeps events and adds sync state`() {
        val dbName = "migration-2-to-3"
        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO events (id, title, description, startTime, endTime, reminderMinutes, " +
                    "repeatRule, textNote, tags, voiceNotePath, calendarProvider, remoteId, updatedAt, " +
                    "isDeleted, pendingSync) " +
                    "VALUES ('e1', 'Встреча с мамой', 'Описание', 1000, 2000, '5,15', " +
                    "NULL, 'Заметка', 'BIRTHDAY', NULL, 'google', 'r1', 100, 0, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 3, true, MyCalendarDatabase.MIGRATION_2_3)

        db.query("SELECT title, textNote, syncBase FROM events WHERE id = 'e1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Встреча с мамой", cursor.getString(0))
            assertEquals("Заметка", cursor.getString(1))
            assertTrue(cursor.isNull(2))
        }
        db.query("SELECT COUNT(*) FROM sync_conflicts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }
}
```

- [ ] **Step 8: Прогн тест миграции (нужен эмулятор)**

Run: `~/AppData/Local/Android/Sdk/platform-tools/adb devices` — должен быть хотя бы один device; затем
`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.Migration2to3Test`
Expected: PASS. Если устройства нет — зафиксировать «Migration2to3Test не запущен (нет эмулятора)» и продолжить; валидация SQL-совпадения со `3.json` в этом случае не выполнена.

- [ ] **Step 9: Коммит**

```bash
git add app/src/main/java/com/example/mycalendar/data/local app/src/main/java/com/example/mycalendar/domain/model/SyncConflict.kt app/src/main/java/com/example/mycalendar/data/local/MyCalendarDatabase.kt app/src/main/java/com/example/mycalendar/di/DatabaseModule.kt app/build.gradle.kts gradle/libs.versions.toml app/schemas app/src/androidTest/java/com/example/mycalendar/Migration2to3Test.kt
git commit -m "feat(db): Room v3 — снимок-предок syncBase и таблица sync_conflicts"
```

---

### Task 3: EventRepository: предок в контракте и записи

**Files:**
- Create: `app/src/main/java/com/example/mycalendar/domain/model/SyncedRecord.kt`
- Create: `app/src/main/java/com/example/mycalendar/domain/repository/VoiceNoteCleanup.kt`
- Modify: `app/src/main/java/com/example/mycalendar/domain/repository/EventRepository.kt` (19-69)
- Modify: `app/src/main/java/com/example/mycalendar/data/repository/EventRepositoryImpl.kt` (40-80)
- Modify: `app/src/main/java/com/example/mycalendar/data/local/EventDao.kt` (удалить `markPendingSync`)
- Test: `app/src/test/java/com/example/mycalendar/data/repository/EventRepositorySyncStateTest.kt`

**Interfaces:**
- Consumes: `EventSyncState`, `EventDao.getSyncState/markSynced(id, syncBase)/attachToProvider(..., syncBase)` (Task 2), `SyncSnapshot` (Task 1).
- Produces:
  `data class SyncedRecord(val event: Event, val isDeleted: Boolean, val pendingSync: Boolean, val base: SyncSnapshot?)`;
  `EventRepository.findSyncedRecord(provider: CalendarProvider, remoteId: String): SyncedRecord?`;
  `saveFromRemote(event: Event, syncBase: SyncSnapshot?)`;
  `markSynced(id: String, syncBase: SyncSnapshot)`;
  `attachToProvider(id: String, remoteId: String, provider: CalendarProvider, syncBase: SyncSnapshot?)`;
  `saveResolved(event: Event, syncBase: SyncSnapshot)` (разбор конфликта: контент + предок + `pendingSync = true`);
  `purge(id: String)` (строка + файл заметки).
  Методы `findByRemoteId` и `markPendingSync` из контракта УДАЛЯЮТСЯ (единственный потребитель — синхронизация, она переходит на `findSyncedRecord`).

- [ ] **Step 1: Падающий тест**

`app/src/test/java/com/example/mycalendar/data/repository/EventRepositorySyncStateTest.kt`:

```kotlin
package com.example.mycalendar.data.repository

import com.example.mycalendar.data.local.EventDao
import com.example.mycalendar.data.local.EventEntity
import com.example.mycalendar.data.local.EventSyncState
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.model.SyncSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Служебные колонки строки (предок, pendingSync) переживают правки контента:
 * saveFromUser не должен стирать syncBase, а запись из облака — обязан его проставлять.
 */
class EventRepositorySyncStateTest {

    private val dao = FakeEventDao()
    private val voiceNotes = FakeVoiceNoteStorage()
    private val repository = EventRepositoryImpl(dao, voiceNotes)

    private val base = SyncSnapshot(
        title = "Встреча",
        description = null,
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(5),
        repeatRule = RepeatRule.WEEKLY,
    )

    private val event = Event(
        id = "e1",
        title = "Встреча",
        description = null,
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(5),
        repeatRule = RepeatRule.WEEKLY,
        calendarProvider = CalendarProvider.GOOGLE,
        remoteId = "r1",
    )

    @Test
    fun `saveFromUser keeps predecessor`() = kotlinx.coroutines.runBlocking {
        repository.saveFromRemote(event, base)

        repository.saveFromUser(event.copy(title = "Встреча перенесена"))

        assertEquals(base, SyncSnapshot.fromJson(dao.rows.getValue("e1").syncBase))
        assertTrue(dao.rows.getValue("e1").pendingSync)
    }

    @Test
    fun `saveFromRemote writes predecessor and clears pending flag`() = kotlinx.coroutines.runBlocking {
        repository.saveFromRemote(event, base)

        val stored = dao.rows.getValue("e1")
        assertEquals(base.toJson(), stored.syncBase)
        assertTrue(!stored.pendingSync)
    }

    @Test
    fun `findSyncedRecord exposes sync state of the row`() = kotlinx.coroutines.runBlocking {
        repository.saveFromRemote(event, base)
        dao.rows.getValue("e1").let { dao.rows["e1"] = it.copy(isDeleted = true) }

        val record = repository.findSyncedRecord(CalendarProvider.GOOGLE, "r1")

        assertEquals("e1", record?.event?.id)
        assertTrue(record?.isDeleted == true)
        assertEquals(base, record?.base)
    }

    @Test
    fun `findSyncedRecord returns null for unknown remote id`() = kotlinx.coroutines.runBlocking {
        assertNull(repository.findSyncedRecord(CalendarProvider.YANDEX, "нет-такого"))
    }

    @Test
    fun `markSynced moves predecessor and clears pending flag`() = kotlinx.coroutines.runBlocking {
        repository.saveFromRemote(event, base)
        val pushed = base.copy(title = "Встреча перенесена")

        repository.markSynced("e1", pushed)

        val stored = dao.rows.getValue("e1")
        assertTrue(!stored.pendingSync)
        assertEquals(pushed, SyncSnapshot.fromJson(stored.syncBase))
    }

    @Test
    fun `saveResolved stores the user choice as the new predecessor`() = kotlinx.coroutines.runBlocking {
        repository.saveFromRemote(event, base)
        val resolved = event.copy(title = "Встреча — выбор пользователя")
        val newBase = SyncSnapshot.of(resolved)

        repository.saveResolved(resolved, newBase)

        val stored = dao.rows.getValue("e1")
        assertEquals("Встреча — выбор пользователя", stored.title)
        assertTrue(stored.pendingSync)
        assertEquals(newBase, SyncSnapshot.fromJson(stored.syncBase))
    }

    @Test
    fun `purge drops the row`() = kotlinx.coroutines.runBlocking {
        repository.saveFromRemote(event, base)

        repository.purge("e1")

        assertTrue(dao.rows.isEmpty())
    }
}

/** Room-DAO в памяти: достаточно для проверки служебных колонок, без Android. */
private class FakeEventDao : EventDao {
    val rows = mutableMapOf<String, EventEntity>()

    override fun observeEventsBetween(from: Long, to: Long): Flow<List<EventEntity>> = kotlinx.coroutines.flow.flowOf(rows.values.toList())
    override fun observeEvent(id: String): Flow<EventEntity?> = kotlinx.coroutines.flow.flowOf(rows[id])
    override suspend fun getEvent(id: String): EventEntity? = rows[id]
    override suspend fun getUpcoming(now: Long, limit: Int): List<EventEntity> = rows.values.toList()
    override suspend fun upsert(event: EventEntity) { rows[event.id] = event }
    override suspend fun softDelete(id: String, updatedAt: Long) {
        rows[id] = rows.getValue(id).copy(isDeleted = true, pendingSync = true, updatedAt = updatedAt)
    }
    override suspend fun hardDelete(id: String) { rows.remove(id) }
    override suspend fun getPendingSync(): List<EventEntity> = rows.values.filter { it.pendingSync }
    override suspend fun markSynced(id: String, syncBase: String?) {
        rows[id] = rows.getValue(id).copy(pendingSync = false, syncBase = syncBase)
    }
    override suspend fun setRemoteId(id: String, remoteId: String) {
        rows[id] = rows.getValue(id).copy(remoteId = remoteId, pendingSync = false)
    }
    override suspend fun attachToProvider(id: String, remoteId: String, provider: String, syncBase: String?) {
        rows[id] = rows.getValue(id).copy(remoteId = remoteId, calendarProvider = provider, pendingSync = false, syncBase = syncBase)
    }
    override suspend fun findByRemoteId(provider: String, remoteId: String): EventEntity? =
        rows.values.firstOrNull { it.calendarProvider == provider && it.remoteId == remoteId }
    override suspend fun getSyncState(id: String): EventSyncState? =
        rows[id]?.let { EventSyncState(it.syncBase, it.updatedAt) }
}
```

`FakeVoiceNoteStorage` — подмена интерфейса `VoiceNoteCleanup` из шага 3; её код и тест
инварианта «нет строки — нет файла» добавляются в тот же файл:

```kotlin
    @Test
    fun `purge removes the voice file together with the row`() = kotlinx.coroutines.runBlocking {
        repository.saveFromRemote(event.copy(voiceNotePath = VOICE_PATH), base)

        repository.purge("e1")

        assertTrue(dao.rows.isEmpty())
        assertEquals(listOf(VOICE_PATH), voiceNotes.deletedPaths)
    }
```

```kotlin
/** Фикс чистки заметок: записывает удалённые пути вместо работы с файловой системой. */
private class FakeVoiceNoteStorage : VoiceNoteCleanup {
    val deletedPaths = mutableListOf<String>()

    override fun deleteFile(absolutePath: String) {
        deletedPaths += absolutePath
    }
}
```

В начале файла теста — импорт `com.example.mycalendar.domain.repository.VoiceNoteCleanup` и
константа `private const val VOICE_PATH = "/data/user/0/com.example.mycalendar/files/voice_notes/e1.m4a"`
(на уровне файла).

- [ ] **Step 2: Проверить, что падает**

Run: `./gradlew :app:testDebugUnitTest --tests "*EventRepositorySyncStateTest*"`
Expected: FAILURE — `Unresolved reference: findSyncedRecord`, `Class 'FakeEventDao' is not abstract and does not implement getSyncState`, `Too many arguments for saveFromRemote`, `Unresolved reference: saveResolved`.

- [ ] **Step 3: Интерфейс чистки файла + реализация**

`app/src/main/java/com/example/mycalendar/domain/repository/EventRepository.kt` — добавить контракт, не тащящий Android:

```kotlin
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
```

(файл `domain/model/SyncedRecord.kt`, пакет `com.example.mycalendar.domain.model`)

В `interface EventRepository` заменить/добавить методы:

```kotlin
    /**
     * Запись, приехавшая ИЗ облака: pendingSync не выставляется (иначе цикл синхронизации),
     * а [syncBase] становится новым снимком-предком этой строки.
     */
    suspend fun saveFromRemote(event: Event, syncBase: SyncSnapshot?)

    /** Строка с её служебным состоянием (для решения о слиянии) или null, если пары нет. */
    suspend fun findSyncedRecord(provider: CalendarProvider, remoteId: String): SyncedRecord?

    /** Успешный push: снять pendingSync и догнать предка отправленным состоянием. */
    suspend fun markSynced(id: String, syncBase: SyncSnapshot)

    /** Привязка созданного в облаке события + предок на момент отправки. */
    suspend fun attachToProvider(id: String, remoteId: String, provider: CalendarProvider, syncBase: SyncSnapshot?)

    /**
     * Разбор конфликта: [event] с выбранными значениями становится локальной записью,
     * а [syncBase] — новым предком. Разобранное состояние ещё не в облаке,
     * поэтому флаг неотправленного включается обязательно.
     */
    suspend fun saveResolved(event: Event, syncBase: SyncSnapshot)

    /**
     * Полное удаление: строка БД И файл голосовой заметки.
     * Инвариант: файла нет ровно тогда, когда нет строки.
     */
    suspend fun purge(id: String)
```

Удалить из интерфейса `findByRemoteId` и `markPendingSync`, а `markSynced(ids: List<String>)` заменить на вариант выше.

Контракт — новый файл `domain/repository/VoiceNoteCleanup.kt` (пакет
`com.example.mycalendar.domain.repository`, чтобы `EventRepositoryImpl` не тянул Android);
реализацию `data/audio/VoiceNoteStorage.kt` вписываем в него же через интерфейс:

```kotlin
/** Удаление файла голосовой заметки события (реализация — VoiceNoteStorage). */
interface VoiceNoteCleanup {
    fun deleteFile(absolutePath: String)
}
```

```kotlin
@Singleton
class VoiceNoteStorage @Inject constructor(
    @ApplicationContext context: Context,
) : VoiceNoteCleanup {
    /* существующее тело без изменений */
    override fun deleteFile(absolutePath: String) {
        File(absolutePath).delete()
    }
}
```

`data/repository/EventRepositoryImpl.kt`:

```kotlin
@Singleton
class EventRepositoryImpl @Inject constructor(
    private val eventDao: EventDao,
    private val voiceNotes: VoiceNoteCleanup,
) : EventRepository {

    override suspend fun saveFromUser(event: Event) {
        // Правка контента не трогает предка: он сдвигается только за облаком или после push.
        eventDao.upsert(
            event.toEntity(
                updatedAt = System.currentTimeMillis(),
                pendingSync = true,
                syncBase = eventDao.getSyncState(event.id)?.syncBase,
            )
        )
    }

    override suspend fun saveFromRemote(event: Event, syncBase: SyncSnapshot?) {
        eventDao.upsert(
            event.toEntity(
                updatedAt = event.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                pendingSync = false,
                syncBase = syncBase?.toJson(),
            )
        )
    }

    override suspend fun findSyncedRecord(provider: CalendarProvider, remoteId: String): SyncedRecord? =
        eventDao.findByRemoteId(provider.name.lowercase(), remoteId)?.let {
            SyncedRecord(
                event = it.toDomain(),
                isDeleted = it.isDeleted,
                pendingSync = it.pendingSync,
                base = SyncSnapshot.fromJson(it.syncBase),
            )
        }

    override suspend fun markSynced(id: String, syncBase: SyncSnapshot) =
        eventDao.markSynced(id, syncBase.toJson())

    override suspend fun attachToProvider(
        id: String,
        remoteId: String,
        provider: CalendarProvider,
        syncBase: SyncSnapshot?,
    ) = eventDao.attachToProvider(id, remoteId, provider.name.lowercase(), syncBase?.toJson())

    override suspend fun saveResolved(event: Event, syncBase: SyncSnapshot) {
        // Выбранное пользователем состояние и есть новый предок: следующий pull
        // обязан считать локальными изменениями только то, что появится после разбора.
        eventDao.upsert(
            event.toEntity(
                updatedAt = System.currentTimeMillis(),
                pendingSync = true,
                syncBase = syncBase.toJson(),
            )
        )
    }

    override suspend fun purge(id: String) {
        eventDao.getEvent(id)?.voiceNotePath?.let(voiceNotes::deleteFile)
        eventDao.hardDelete(id)
    }
```

Остальные методы (`getEvent`, `getUpcomingEvents`, `markDeleted`, `hardDelete`, `getPendingSyncEvents`,
`setRemoteId`) остаются; `getPendingSyncEvents` возвращает `PendingSyncEvent` как сейчас.
В `EventDao` удалить запрос `markPendingSync`; `hardDelete` остаётся (используется тестами androidTest),
но продакшн-пути удаления строки идут через `purge`.

- [ ] **Step 4: Прогн**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS + BUILD SUCCESSFUL. Правки в `SyncRepositoryImpl` (вызовы удалённых методов) дают ошибки компиляции — поэтому в этом же шаге минимально поправить вызовы: `saveFromRemote(incoming)` → `saveFromRemote(incoming, SyncSnapshot.of(incoming))`, `saveFromRemote(x)` в ветках перезаписи → `saveFromRemote(x, null)` временно, `markPendingSync(local.id)` → `saveFromUser(local)`, `markSynced(listOf(id))` → `markSynced(id, SyncSnapshot.of(event))`, `attachToProvider(id, rid, provider)` → `attachToProvider(id, rid, provider, SyncSnapshot.of(event))`, `findByRemoteId(...)` → `findSyncedRecord(...)?.event`. Пол алгоритма перепишется в Task 6.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/java/com/example/mycalendar/domain/model/SyncedRecord.kt app/src/main/java/com/example/mycalendar/domain/repository/VoiceNoteCleanup.kt app/src/main/java/com/example/mycalendar/domain/repository/EventRepository.kt app/src/main/java/com/example/mycalendar/data/repository/EventRepositoryImpl.kt app/src/main/java/com/example/mycalendar/data/local/EventDao.kt app/src/main/java/com/example/mycalendar/data/audio/VoiceNoteStorage.kt app/src/test/java/com/example/mycalendar/data/repository/EventRepositorySyncStateTest.kt
git commit -m "feat(sync): снимок-предок в контракте EventRepository и purge строки вместе с файлом"
```

---

### Task 4: Очередь конфликтов: модель, репозиторий, DI

**Files:**
- Create: `app/src/main/java/com/example/mycalendar/domain/model/SyncConflict.kt` (в Task 2 был каркас — здесь доводится до полного вида)
- Create: `app/src/main/java/com/example/mycalendar/domain/repository/ConflictRepository.kt`
- Create: `app/src/main/java/com/example/mycalendar/data/repository/ConflictRepositoryImpl.kt`
- Modify: `app/src/main/java/com/example/mycalendar/di/RepositoryModule.kt`
- Test: `app/src/test/java/com/example/mycalendar/data/repository/ConflictRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `SyncConflictDao` (Task 2), `SyncSnapshot`/`SyncField` (Task 1), `CalendarProvider`/`ConflictKind`.
- Produces:
  `data class SyncConflict(id, eventId, provider: CalendarProvider, remoteId: String, kind: ConflictKind, base: SyncSnapshot?, remote: SyncSnapshot, contested: Set<SyncField>, detectedAt: Long)` с `val isDeletion: Boolean`;
  `enum class ConflictKind { CONTENT, REMOTE_DELETED, LOCAL_DELETED }`;
  `interface ConflictRepository { fun observeOpenCount(): Flow<Int>; fun observeConflicts(): Flow<List<SyncConflict>>; suspend fun get(id: String): SyncConflict?; suspend fun save(conflict: SyncConflict); suspend fun delete(id: String); suspend fun openEventIds(): List<String> }`.

- [ ] **Step 1: Модель конфликта**

`domain/model/SyncConflict.kt` — файл уже существует (каркас из Task 2, нужный для компиляции
Room-мапперов): заменяем его содержимое целиком приведённым ниже кодом, `enum` и `data class`
в нем те же, добавлены лишь маппинг-независимые детали.

```kotlin
package com.example.mycalendar.domain.model

/** Чем именно столкнулись локальная запись и облако. */
enum class ConflictKind {
    /** Разные значения в одном и том же поле — разбирается по полям. */
    CONTENT,
    /** Облако удалило событие, а локально есть неотправленные правки. */
    REMOTE_DELETED,
    /** Локальный tombstone, а в облаке событие правили. */
    LOCAL_DELETED,
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
    val isDeletion: Boolean get() = kind != ConflictKind.CONTENT
}
```

- [ ] **Step 2: Падающий тест репозитория**

`app/src/test/java/com/example/mycalendar/data/repository/ConflictRepositoryImplTest.kt`:

```kotlin
package com.example.mycalendar.data.repository

import com.example.mycalendar.data.local.SyncConflictDao
import com.example.mycalendar.data.local.SyncConflictEntity
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * На событии держится ровно один открытый конфликт: повторное обнаружение
 * перезаписывает существующую строку, не меняя её id (маршрут разбора остаётся валидным).
 */
class ConflictRepositoryImplTest {

    private val dao = FakeSyncConflictDao()
    private val repository = ConflictRepositoryImpl(dao)

    private val remote = SyncSnapshot(
        title = "Название из облака",
        description = "Описание из облака",
        startTime = 5_000L,
        endTime = 6_000L,
        reminderMinutes = listOf(30),
        repeatRule = null,
    )

    private fun conflict(id: String = "c1", eventId: String = "e1") = SyncConflict(
        id = id,
        eventId = eventId,
        provider = CalendarProvider.GOOGLE,
        remoteId = "r1",
        kind = ConflictKind.CONTENT,
        base = remote.copy(title = "Общее название"),
        remote = remote,
        contested = setOf(SyncField.TITLE, SyncField.DESCRIPTION),
        detectedAt = 1_000L,
    )

    @Test
    fun `save keeps contested fields and provider round trip`() = runBlocking {
        repository.save(conflict())

        val stored = repository.get("c1")!!
        assertEquals(CalendarProvider.GOOGLE, stored.provider)
        assertEquals(ConflictKind.CONTENT, stored.kind)
        assertEquals(setOf(SyncField.TITLE, SyncField.DESCRIPTION), stored.contested)
        assertEquals(remote, stored.remote)
    }

    @Test
    fun `second save on same event reuses id and does not add a row`() = runBlocking {
        repository.save(conflict(id = "c1"))

        repository.save(conflict(id = "c-new", eventId = "e1").copy(remote = remote.copy(title = "Ещё правка")))

        assertEquals(1, dao.rows.size)
        assertEquals("c1", repository.get("c1")?.id)
        assertEquals("Ещё правка", repository.get("c1")?.remote?.title)
    }

    @Test
    fun `open event ids feed the push gate`() = runBlocking {
        repository.save(conflict(eventId = "e1"))
        repository.save(conflict(id = "c2", eventId = "e2"))

        assertEquals(setOf("e1", "e2"), repository.openEventIds().toSet())
    }

    @Test
    fun `delete removes the conflict from the queue`() = runBlocking {
        repository.save(conflict())

        repository.delete("c1")

        assertTrue(dao.rows.isEmpty())
        assertEquals(0, repository.observeOpenCount().first())
    }

    @Test
    fun `deletion conflict survives empty contested set`() = runBlocking {
        repository.save(conflict().copy(kind = ConflictKind.REMOTE_DELETED, contested = emptySet()))

        val stored = repository.get("c1")!!
        assertEquals(ConflictKind.REMOTE_DELETED, stored.kind)
        assertTrue(stored.isDeletion)
        assertTrue(stored.contested.isEmpty())
    }
}

/** Таблица конфликтов в памяти — проверка маппинга и «один конфликт на событие» без Android. */
private class FakeSyncConflictDao : SyncConflictDao {
    val rows = mutableMapOf<String, SyncConflictEntity>()

    override fun observeAll(): Flow<List<SyncConflictEntity>> = flowOf(rows.values.sortedBy { it.detectedAt })
    override fun observeCount(): Flow<Int> = flowOf(rows.size)
    override suspend fun openEventIds(): List<String> = rows.values.map { it.eventId }
    override suspend fun getById(id: String): SyncConflictEntity? = rows[id]
    override suspend fun idByEvent(eventId: String): String? = rows.values.firstOrNull { it.eventId == eventId }?.id
    override suspend fun upsert(conflict: SyncConflictEntity) { rows[conflict.id] = conflict }
    override suspend fun deleteById(id: String) { rows.remove(id) }
}
```

- [ ] **Step 3: Проверить падение**

Run: `./gradlew :app:testDebugUnitTest --tests "*ConflictRepositoryImplTest*"`
Expected: FAILURE — `Unresolved reference: ConflictRepositoryImpl`, `ConflictRepositoryImplTest`.

- [ ] **Step 4: Реализация**

`domain/repository/ConflictRepository.kt`:

```kotlin
package com.example.mycalendar.domain.repository

import com.example.mycalendar.domain.model.SyncConflict
import kotlinx.coroutines.flow.Flow

/**
 * Очередь открытых конфликтов синхронизации (§7 ТЗ «не перезаписывай молча»).
 * Не больше одного конфликта на событие; разбирается на экране конфликтов.
 */
interface ConflictRepository {

    fun observeOpenCount(): Flow<Int>

    fun observeConflicts(): Flow<List<SyncConflict>>

    suspend fun get(id: String): SyncConflict?

    /** Сохранить обнаруженный конфликт: для уже открытого события перезаписать существующий. */
    suspend fun save(conflict: SyncConflict)

    suspend fun delete(id: String)

    /** События с открытым конфликтом — их не отправляем в облако до разбора. */
    suspend fun openEventIds(): List<String>
}
```

`data/repository/ConflictRepositoryImpl.kt`:

```kotlin
package com.example.mycalendar.data.repository

import com.example.mycalendar.data.local.SyncConflictDao
import com.example.mycalendar.data.local.toDomain
import com.example.mycalendar.data.local.toEntity
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.repository.ConflictRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** [ConflictRepository] поверх Room; один открытый конфликт на событие обеспечивает уникальный индекс. */
@Singleton
class ConflictRepositoryImpl @Inject constructor(
    private val conflictDao: SyncConflictDao,
) : ConflictRepository {

    override fun observeOpenCount(): Flow<Int> = conflictDao.observeCount()

    override fun observeConflicts(): Flow<List<SyncConflict>> =
        conflictDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): SyncConflict? = conflictDao.getById(id)?.toDomain()

    override suspend fun save(conflict: SyncConflict) {
        // Id существующего конфликта сохраняем: на него уже может указывать открытый экран разбора.
        val existingId = conflictDao.idByEvent(conflict.eventId)
        conflictDao.upsert(conflict.copy(id = existingId ?: conflict.id).toEntity())
    }

    override suspend fun delete(id: String) = conflictDao.deleteById(id)

    override suspend fun openEventIds(): List<String> = conflictDao.openEventIds()
}
```

`di/RepositoryModule.kt` — биндинг:

```kotlin
    @Binds
    @Singleton
    abstract fun bindConflictRepository(impl: ConflictRepositoryImpl): ConflictRepository
```

- [ ] **Step 5: Прогн**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS, BUILD SUCCESSFUL.

- [ ] **Step 6: Коммит**

```bash
git add app/src/main/java/com/example/mycalendar/domain/model/SyncConflict.kt app/src/main/java/com/example/mycalendar/domain/repository/ConflictRepository.kt app/src/main/java/com/example/mycalendar/data/repository/ConflictRepositoryImpl.kt app/src/main/java/com/example/mycalendar/di/RepositoryModule.kt app/src/test/java/com/example/mycalendar/data/repository/ConflictRepositoryImplTest.kt
git commit -m "feat(sync): очередь открытых конфликтов и её репозиторий"
```

---

### Task 5: Тристороннее слияние в SyncConflictResolver

**Files:**
- Modify: `app/src/main/java/com/example/mycalendar/data/repository/SyncConflictResolver.kt` (полностью, сейчас 26 строк)
- Modify (тест): `app/src/test/java/com/example/mycalendar/SyncConflictResolverTest.kt` (переписать)

**Interfaces:**
- Consumes: `SyncedRecord` (Task 3), `SyncSnapshot`/`diffFields`/`SyncField` (Task 1), `RemoteEvent`/`toEvent` (`data/remote/RemoteCalendarDataSource.kt`), `Event.appliedTo` (`SyncRepositoryImpl.kt:298`), `ConflictKind` (Task 4).
- Produces (использует Task 6):
  `sealed interface PullDecision { data object Skip; data class Insert(event, base); data class ApplyRemote(event, base); data class AutoMerge(event); data class Conflict(eventId, kind, base, remote, contested, mergedLocal); data object Purge; data object PushDelete }`;
  `SyncConflictResolver.decide(record: SyncedRecord?, remote: RemoteEvent, provider: CalendarProvider): PullDecision`.
  Старые `resolve(local, remote)` и `enum Resolution` УДАЛЯЮТСЯ (LWW больше нет).

- [ ] **Step 1: Переписать тест на таблицу дизайна**

`app/src/test/java/com/example/mycalendar/SyncConflictResolverTest.kt`:

```kotlin
package com.example.mycalendar

import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.data.repository.PullDecision
import com.example.mycalendar.data.repository.SyncConflictResolver
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot
import com.example.mycalendar.domain.model.SyncedRecord
import com.example.mycalendar.domain.model.diffFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Матрица решений pull (раздел 2 дизайна): кто изменился относительно предка —
 * облако, локально, оба или никто. Время изменения в расчёте не участвует.
 */
class SyncConflictResolverTest {

    private val resolver = SyncConflictResolver()

    private val base = SyncSnapshot(
        title = "Общее название",
        description = "Общее описание",
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(5, 15),
        repeatRule = RepeatRule.WEEKLY,
    )

    private fun localEvent(fields: SyncSnapshot.() -> SyncSnapshot = { this }) =
        base.fields().let {
            Event(
                id = "e1",
                title = it.title,
                description = it.description,
                startTime = it.startTime,
                endTime = it.endTime,
                reminderMinutes = it.reminderMinutes,
                repeatRule = it.repeatRule,
                textNote = "Локальная заметка",
                calendarProvider = CalendarProvider.GOOGLE,
                remoteId = "r1",
                updatedAt = 100L,
            )
        }

    private fun record(
        event: Event = localEvent(),
        isDeleted: Boolean = false,
        pendingSync: Boolean = false,
        base: SyncSnapshot? = this.base,
    ) = SyncedRecord(event = event, isDeleted = isDeleted, pendingSync = pendingSync, base = base)

    private fun remoteEvent(fields: SyncSnapshot.() -> SyncSnapshot = { this }): RemoteEvent =
        base.fields().let {
            RemoteEvent(
                remoteId = "r1",
                title = it.title,
                description = it.description,
                startTime = it.startTime,
                endTime = it.endTime,
                reminderMinutes = it.reminderMinutes,
                repeatRule = it.repeatRule,
                updatedAt = 900L,
            )
        }

    private fun deletedRemote() = remoteEvent().copy(deleted = true)

    private fun decide(record: SyncedRecord?, remote: RemoteEvent) =
        resolver.decide(record, remote, CalendarProvider.GOOGLE)

    @Test
    fun `no local pair inserts cloud version as its own predecessor`() {
        val changed = remoteEvent { copy(title = "Название из облака") }

        val decision = decide(record = null, remote = changed)

        assertTrue(decision is PullDecision.Insert)
        decision as PullDecision.Insert
        assertEquals("Название из облака", decision.event.title)
        assertEquals(SyncSnapshot.of(changed.toEvent(CalendarProvider.GOOGLE)), decision.base)
    }

    @Test
    fun `cloud unchanged relative to predecessor skips the row`() {
        val decision = decide(record(), remoteEvent())

        assertEquals(PullDecision.Skip, decision)
    }

    @Test
    fun `only cloud changed applies silently and moves predecessor`() {
        val decision = decide(record(), remoteEvent { copy(title = "Название из облака") })

        decision as PullDecision.ApplyRemote
        assertEquals("Название из облака", decision.event.title)
        assertEquals("Локальная заметка", decision.event.textNote)
        assertEquals(setOf(SyncField.TITLE), diffFields(base, decision.base))
    }

    @Test
    fun `only local changed keeps local and queues nothing`() {
        val decision = decide(
            record(event = localEvent { copy(title = "Моя правка") }, pendingSync = true),
            remoteEvent(),
        )

        assertEquals(PullDecision.Skip, decision)
    }

    @Test
    fun `disjoint changes merge automatically without moving predecessor`() {
        val decision = decide(
            record(event = localEvent { copy(title = "Моя правка") }, pendingSync = true),
            remoteEvent { copy(startTime = 7_000L, endTime = 8_000L) },
        )

        decision as PullDecision.AutoMerge
        assertEquals("Моя правка", decision.event.title)
        assertEquals(7_000L, decision.event.startTime)
        assertEquals(8_000L, decision.event.endTime)
        assertEquals("Локальная заметка", decision.event.textNote)
    }

    @Test
    fun `unpushed state equal to predecessor is not overwritten`() {
        val decision = decide(
            record(event = localEvent(), pendingSync = true),
            remoteEvent { copy(title = "Название из облака") },
        )

        assertEquals(PullDecision.Skip, decision)
    }

    @Test
    fun `both sides reaching the same value contest nothing`() {
        val decision = decide(
            record(
                event = localEvent { copy(title = "Финальное название", description = "Локальное описание") },
                pendingSync = true,
            ),
            remoteEvent { copy(title = "Финальное название") },
        )

        assertEquals(PullDecision.Skip, decision)
    }

    @Test
    fun `repeated pull of an already merged row skips`() {
        val decision = decide(
            record(
                event = localEvent { copy(title = "Моя правка", startTime = 7_000L, endTime = 8_000L) },
                pendingSync = true,
            ),
            remoteEvent { copy(startTime = 7_000L, endTime = 8_000L) },
        )

        assertEquals(PullDecision.Skip, decision)
    }

    @Test
    fun `overlapping field goes to the queue and local value survives`() {
        val decision = decide(
            record(event = localEvent { copy(title = "Моя правка") }, pendingSync = true),
            remoteEvent { copy(title = "Правка из облака", startTime = 7_000L) },
        )

        decision as PullDecision.Conflict
        assertEquals(ConflictKind.CONTENT, decision.kind)
        assertEquals(setOf(SyncField.TITLE), decision.contested)
        assertEquals("Моя правка", decision.mergedLocal.title)
        assertEquals(7_000L, decision.mergedLocal.startTime)
        assertEquals("Правка из облака", decision.remote.title)
    }

    @Test
    fun `reminder order difference is not a conflict`() {
        val decision = decide(
            record(event = localEvent { copy(reminderMinutes = listOf(15, 5)) }, pendingSync = true),
            remoteEvent { copy(reminderMinutes = listOf(5, 15)) },
        )

        assertEquals(PullDecision.Skip, decision)
    }

    @Test
    fun `legacy row without predecessor and without local edits adopts cloud`() {
        val decision = decide(
            record(event = localEvent(), base = null, pendingSync = false),
            remoteEvent { copy(title = "Название из облака") },
        )

        assertTrue(decision is PullDecision.ApplyRemote)
    }

    @Test
    fun `legacy row without predecessor and with local edits contests every difference`() {
        val decision = decide(
            record(event = localEvent { copy(title = "Моя правка", startTime = 3_000L) }, base = null, pendingSync = true),
            remoteEvent { copy(title = "Правка из облака") },
        )

        decision as PullDecision.Conflict
        assertEquals(setOf(SyncField.TITLE), decision.contested)
        assertEquals("Моя правка", decision.mergedLocal.title)
        assertEquals(3_000L, decision.mergedLocal.startTime)
    }

    @Test
    fun `legacy row identical to cloud does nothing`() {
        val decision = decide(
            record(event = localEvent(), base = null, pendingSync = true),
            remoteEvent(),
        )

        assertEquals(PullDecision.Skip, decision)
    }

    @Test
    fun `cloud deletion of untouched row purges local`() {
        assertEquals(PullDecision.Purge, decide(record(), deletedRemote()))
    }

    @Test
    fun `cloud deletion of locally edited row queues conflict`() {
        val decision = decide(
            record(event = localEvent { copy(title = "Моя правка") }, pendingSync = true),
            deletedRemote(),
        )

        decision as PullDecision.Conflict
        assertEquals(ConflictKind.REMOTE_DELETED, decision.kind)
        assertEquals("e1", decision.eventId)
        assertTrue(decision.contested.isEmpty())
        assertEquals("Моя правка", decision.mergedLocal.title)
    }

    @Test
    fun `cloud deletion of local tombstone purges`() {
        val decision = decide(record(isDeleted = true, pendingSync = true), deletedRemote())

        assertEquals(PullDecision.Purge, decision)
    }

    @Test
    fun `local tombstone with unchanged cloud is pushed as delete`() {
        assertEquals(PullDecision.PushDelete, decide(record(isDeleted = true, pendingSync = true), remoteEvent()))
    }

    @Test
    fun `local tombstone with edited cloud queues conflict`() {
        val decision = decide(
            record(isDeleted = true, pendingSync = true),
            remoteEvent { copy(title = "Правка из облака") },
        )

        decision as PullDecision.Conflict
        assertEquals(ConflictKind.LOCAL_DELETED, decision.kind)
        assertEquals(setOf(SyncField.TITLE), decision.contested)
    }

    @Test
    fun `tombstone without predecessor deletes silently`() {
        val decision = decide(
            record(isDeleted = true, pendingSync = true, base = null),
            remoteEvent { copy(title = "Правка из облака") },
        )

        assertEquals(PullDecision.PushDelete, decision)
    }
}
```

- [ ] **Step 2: Убедиться, что падает**

Run: `./gradlew :app:testDebugUnitTest --tests "*SyncConflictResolverTest*"`
Expected: FAILURE — `Unresolved reference: decide`, `Unresolved reference: PullDecision` (старых тестов LWW в файле больше нет).

- [ ] **Step 3: Реализация**

`data/repository/SyncConflictResolver.kt` целиком:

```kotlin
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
```

- [ ] **Step 4: Прогн все JVM-тесты**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (старые три теста LWW заменены матрицей; `SyncPullLocalFieldsTest` не затронут).

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/java/com/example/mycalendar/data/repository/SyncConflictResolver.kt app/src/test/java/com/example/mycalendar/SyncConflictResolverTest.kt
git commit -m "feat(sync): трёхстороннее слияние вместо last-write-wins"
```

---

### Task 6: Встройка решений в SyncRepositoryImpl и целостность файла заметки

**Files:**
- Create: `app/src/main/java/com/example/mycalendar/data/remote/RemoteDataSourceProvider.kt`
- Create: `app/src/main/java/com/example/mycalendar/data/remote/CalendarDataSourceProvider.kt`
- Modify: `app/src/main/java/com/example/mycalendar/di/RepositoryModule.kt`
- Modify: `app/src/main/java/com/example/mycalendar/data/repository/SyncRepositoryImpl.kt` (20-51 KDoc, 52-61 ctor, 105-289 тело)
- Modify: `app/src/main/java/com/example/mycalendar/domain/usecase/EventUseCases.kt` (91-105)

**Interfaces:**
- Consumes: `PullDecision` и `SyncConflictResolver.decide` (Task 5), `ConflictRepository` (Task 4), `EventRepository.purge/saveFromRemote(event, base)/findSyncedRecord/markSynced(id, base)/attachToProvider(..., base)` (Task 3), `SyncConflict` (Task 4).
- Produces: `fun interface RemoteDataSourceProvider { fun forProvider(provider: CalendarProvider): RemoteCalendarDataSource? }`;
  конструктор `SyncRepositoryImpl(eventRepository, authRepository, settingsRepository, conflictResolver, conflictRepository, dataSourceProvider, reminderScheduler)`.

- [ ] **Step 1: Точка подмены источника**

`data/remote/RemoteDataSourceProvider.kt`:

```kotlin
package com.example.mycalendar.data.remote

import com.example.mycalendar.domain.model.CalendarProvider

/**
 * Удалённый источник событий провайдера. Отдельный интерфейс потому, что
 * сквозной тест синхронизации должен подменять облако фиксом, не трогая Room.
 * `fun interface` — чтобы подмена в тесте писалась лямбдой.
 */
fun interface RemoteDataSourceProvider {
    /** null — провайдер не обслуживается (LOCAL либо неподключённый). */
    fun forProvider(provider: CalendarProvider): RemoteCalendarDataSource?
}
```

`data/remote/CalendarDataSourceProvider.kt`:

```kotlin
package com.example.mycalendar.data.remote

import com.example.mycalendar.data.system.SystemCalendarDataSource
import com.example.mycalendar.data.remote.yandex.YandexCalendarDataSource
import com.example.mycalendar.domain.model.CalendarProvider
import javax.inject.Inject
import javax.inject.Singleton

/** Google — через системный календарь Android, Яндекс — через CalDAV. */
@Singleton
class CalendarDataSourceProvider @Inject constructor(
    private val systemCalendarDataSource: SystemCalendarDataSource,
    private val yandexCalendarDataSource: YandexCalendarDataSource,
) : RemoteDataSourceProvider {

    override fun forProvider(provider: CalendarProvider): RemoteCalendarDataSource? = when (provider) {
        CalendarProvider.GOOGLE -> systemCalendarDataSource
        CalendarProvider.YANDEX -> yandexCalendarDataSource
        CalendarProvider.LOCAL -> null
    }
}
```

`di/RepositoryModule.kt`:

```kotlin
    @Binds
    @Singleton
    abstract fun bindRemoteDataSourceProvider(impl: CalendarDataSourceProvider): RemoteDataSourceProvider
```

- [ ] **Step 2: Новый pull**

В `SyncRepositoryImpl` ctor заменяется на `Produces` выше; `syncProvider` берёт источник так:

```kotlin
        val remote = dataSourceProvider.forProvider(provider)
            ?: return SyncResult.failure(provider, "Провайдер ${provider.name} не обслуживается")
```

`doSync` целиком:

```kotlin
    private suspend fun doSync(
        remote: RemoteCalendarDataSource,
        provider: CalendarProvider,
    ): SyncResult {
        var pulled = 0
        var conflicts = 0
        val pulledIds = mutableListOf<String>()

        // Время последней синхронизации ИМЕННО этого провайдера
        val since = settingsRepository.lastSyncTime(provider).first()

        for (remoteEvent in remote.fetchUpdatedSince(if (since > 0) since else 0L)) {
            val record = eventRepository.findSyncedRecord(provider, remoteEvent.remoteId)
            when (val decision = conflictResolver.decide(record, remoteEvent, provider)) {
                PullDecision.Skip, PullDecision.PushDelete -> Unit

                is PullDecision.Insert -> {
                    eventRepository.saveFromRemote(decision.event, decision.base)
                    pulled++
                    pulledIds += decision.event.id
                }

                is PullDecision.ApplyRemote -> {
                    eventRepository.saveFromRemote(decision.event, decision.base)
                    pulled++
                    pulledIds += decision.event.id
                }

                // Объединение непересекаемых полей: результат неотправлен, предок НЕ двигаем.
                is PullDecision.AutoMerge -> {
                    eventRepository.saveFromUser(decision.event)
                    pulled++
                    pulledIds += decision.event.id
                }

                is PullDecision.Conflict -> {
                    // Для спорных полей держим локальное значение; молча перезаписывать нельзя (§7).
                    if (!decision.kind.isDeletion) eventRepository.saveFromUser(decision.mergedLocal)
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

        val pushed = push(remote, provider)

        // Напоминания для событий, приехавших из облака
        pulledIds.distinct().forEach { id ->
            eventRepository.getEvent(id)?.let { reminderScheduler.schedule(it) }
        }

        return SyncResult(
            success = true, pushed = pushed, pulled = pulled,
            conflicts = conflicts, provider = provider,
        )
    }

    private fun PullDecision.Conflict.toConflict(
        provider: CalendarProvider,
        remoteId: String,
    ): SyncConflict = SyncConflict(
        id = java.util.UUID.randomUUID().toString(),
        eventId = eventId,
        provider = provider,
        remoteId = remoteId,
        kind = kind,
        base = base,
        remote = remote,
        contested = contested,
        detectedAt = System.currentTimeMillis(),
    )
```

Импорт `com.example.mycalendar.domain.model.SyncConflict`, `com.example.mycalendar.domain.model.SyncSnapshot`,
`com.example.mycalendar.domain.repository.ConflictRepository`; `RemoteEvent`/`toEvent`/`appliedTo`
остались нужны только в резолвере — удалить из `SyncRepositoryImpl` неиспользуемые импорты
(`CalendarProvider` нужен, `systemCalendarDataSource`/`yandexCalendarDataSource` — нет).

- [ ] **Step 3: Push: блок спорных + предок после отправки**

```kotlin
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
```

Удалить `sameContent`, ветку `pendingIds` и `hasReliableUpdatedAt` из `doSync`.

- [ ] **Step 4: KDoc класса**

Заменить описание алгоритма (строки 20-51) на:

```kotlin
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
 *  PUSH: локальные записи с pendingSync = этого провайдера; события с открытым
 *  конфликтом пропускаются. После успешной отправки syncBase догоняет отправленное.
 *
 *  Метки, текстовая и голосовая заметки есть только локально: при перезаписи события
 *  удалённой версией они переносятся из локальной записи (см. [appliedTo]) и потому
 *  не могут быть спорными. Файл голосовой заметки удаляется вместе со строкой БД
 *  ([EventRepository.purge]).
 */
```

- [ ] **Step 5: Файл больше не удаляется раньше строки**

`domain/usecase/EventUseCases.kt` — `DeleteEventUseCase` (91-105):

```kotlin
/**
 * Удаление события: отмена напоминаний и tombstone.
 *
 * Файл голосовой заметки здесь НЕ удаляется: облако могло не принять удаление,
 * а потеря записи хуже отложенного файла. Файл снимается вместе со строкой БД
 * в [com.example.mycalendar.domain.repository.EventRepository.purge].
 */
class DeleteEventUseCase @Inject constructor(
    private val eventRepository: EventRepository,
    private val reminderScheduler: ReminderScheduler,
) {
    suspend operator fun invoke(id: String) {
        val event = eventRepository.getEvent(id) ?: return
        reminderScheduler.cancel(event)
        eventRepository.markDeleted(id)
    }
}
```

- [ ] **Step 6: Прогн**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS + BUILD SUCCESSFUL. Поведение pull/push проверяется сквозным тестом в Task 8.

- [ ] **Step 7: Коммит**

```bash
git add app/src/main/java/com/example/mycalendar/data/remote/RemoteDataSourceProvider.kt app/src/main/java/com/example/mycalendar/data/remote/CalendarDataSourceProvider.kt app/src/main/java/com/example/mycalendar/di/RepositoryModule.kt app/src/main/java/com/example/mycalendar/data/repository/SyncRepositoryImpl.kt app/src/main/java/com/example/mycalendar/domain/usecase/EventUseCases.kt
git commit -m "feat(sync): pull/push на трёхстороннем слиянии, очередь конфликтов и целостность файла заметки"
```

---

### Task 7: Разбор конфликта пользователем

**Files:**
- Create: `app/src/main/java/com/example/mycalendar/domain/usecase/ResolveConflictUseCase.kt`
- Test: `app/src/test/java/com/example/mycalendar/domain/usecase/ResolveConflictRuleTest.kt`

**Interfaces:**
- Consumes: `ConflictRepository`, `EventRepository.saveResolved`/`purge`/`getEvent` (Task 3), `SyncConflict`/`ConflictKind` (Task 4), `SyncSnapshot.appliedTo`/`SyncField` (Task 1), `ReminderScheduler`.
- Produces (использует Task 10):
  `enum class DeletionChoice { DELETE_EVERYWHERE, RESTORE_FROM_CLOUD }`;
  `enum class ResolutionAction { PurgeLocal, TombstoneAndPush, RestoreFromCloud }`;
  `fun planResolution(kind: ConflictKind, choice: DeletionChoice): ResolutionAction`;
  `ResolveConflictUseCase.invoke(conflict: SyncConflict, remoteChosen: Set<SyncField>)` и
  `ResolveConflictUseCase.resolveDeletion(conflict: SyncConflict, choice: DeletionChoice)`.

- [ ] **Step 1: Падающий тест чистого правила**

`app/src/test/java/com/example/mycalendar/domain/usecase/ResolveConflictRuleTest.kt`:

```kotlin
package com.example.mycalendar.domain.usecase

import com.example.mycalendar.domain.model.ConflictKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Исходы конфликта-удаления. Удаление не побеждает молча: оба варианта —
 * решение пользователя, и каждое из них видно в списке конфликтов до разбора.
 */
class ResolveConflictRuleTest {

    @Test
    fun `local tombstone deleted everywhere keeps tombstone for push`() {
        assertEquals(
            ResolutionAction.TombstoneAndPush,
            planResolution(ConflictKind.LOCAL_DELETED, DeletionChoice.DELETE_EVERYWHERE),
        )
    }

    @Test
    fun `cloud already gone means purge without push`() {
        assertEquals(
            ResolutionAction.PurgeLocal,
            planResolution(ConflictKind.REMOTE_DELETED, DeletionChoice.DELETE_EVERYWHERE),
        )
    }

    @Test
    fun `restoring either kind keeps the local row`() {
        assertEquals(
            ResolutionAction.RestoreFromCloud,
            planResolution(ConflictKind.LOCAL_DELETED, DeletionChoice.RESTORE_FROM_CLOUD),
        )
        assertEquals(
            ResolutionAction.RestoreFromCloud,
            planResolution(ConflictKind.REMOTE_DELETED, DeletionChoice.RESTORE_FROM_CLOUD),
        )
    }
}
```

- [ ] **Step 2: Проверить падение**

Run: `./gradlew :app:testDebugUnitTest --tests "*ResolveConflictRuleTest*"`
Expected: FAILURE — `Unresolved reference: planResolution`.

- [ ] **Step 3: Реализация**

`domain/usecase/ResolveConflictUseCase.kt`:

```kotlin
package com.example.mycalendar.domain.usecase

import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.repository.ConflictRepository
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.work.ReminderScheduler
import java.io.File
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
    /** Локальная запись выживает с полями из облака. */
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
 * Разбор открытого конфликта (экран «conflict/{id}»).
 *
 * Разобранное состояние становится НОВЫМ ПРЕДКОМ (EventRepository.saveResolved):
 * выбранное пользователем значение и есть согласованная точка, от которой дальше
 * считаются локальные изменения. Оставить предка на месте нельзя: при порядке
 * «pull раньше push» следующий pull снова увидел бы пересечение и воскресил бы
 * тот же конфликт, а выбор пользователя так и не ушёл бы в облако.
 * Флаг pendingSync при этом включается — в облаке разобранного состояния ещё нет.
 */
class ResolveConflictUseCase @Inject constructor(
    private val conflictRepository: ConflictRepository,
    private val eventRepository: EventRepository,
    private val reminderScheduler: ReminderScheduler,
) {

    /** Разбор по полям: в [remoteChosen] выбрано облачное значение, остальные поля остаются локальными. */
    suspend operator fun invoke(conflict: SyncConflict, remoteChosen: Set<SyncField>) {
        val local = eventRepository.getEvent(conflict.eventId)
            ?: return conflictRepository.delete(conflict.id)
        val resolved = conflict.remote.appliedTo(local, remoteChosen)
        reminderScheduler.cancel(local)
        eventRepository.saveResolved(resolved, SyncSnapshot.of(resolved))
        reminderScheduler.schedule(resolved)
        conflictRepository.delete(conflict.id)
    }

    suspend fun resolveDeletion(conflict: SyncConflict, choice: DeletionChoice) {
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
                    .copy(remoteId = if (conflict.kind == ConflictKind.REMOTE_DELETED) null else local.remoteId)
                // Предок = снимок облака: локальная запись после возврата равна ему,
                // и следующий pull обязан встать в очередь на push, а не повторить конфликт.
                eventRepository.saveResolved(restored, conflict.remote)
                reminderScheduler.schedule(restored)
            }
        }
        conflictRepository.delete(conflict.id)
    }
}
```

`java.io.File` из импортов не нужен (`purge` чистит файл в репозитории) — удалить строку импорта, если IDE не уберёт. Нужен импорт `com.example.mycalendar.domain.model.SyncSnapshot`.

- [ ] **Step 4: Прогн**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS + BUILD SUCCESSFUL (исход конфликтов — в `ConflictRepository.save` при повторном pull: `save` переиспользует id существующего конфликта).

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/java/com/example/mycalendar/domain/usecase/ResolveConflictUseCase.kt app/src/test/java/com/example/mycalendar/domain/usecase/ResolveConflictRuleTest.kt
git commit -m "feat(sync): usecase разбора конфликта по полям и по удалениям"
```

---

### Task 8: Сквозной тест синхронизации на настоящей Room

**Files:**
- Create: `app/src/androidTest/java/com/example/mycalendar/SyncConflictEndToEndTest.kt`

**Interfaces:**
- Consumes: `SyncRepositoryImpl` + `RemoteDataSourceProvider` (Task 6), `ResolveConflictUseCase` (Task 7), реальные `EventRepositoryImpl`/`ConflictRepositoryImpl`, `MyCalendarDatabase`.
- Produces: ничего (тест).

- [ ] **Step 1: Тест**

`app/src/androidTest/java/com/example/mycalendar/SyncConflictEndToEndTest.kt`:

```kotlin
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Сквозной сценарий ТЗ §21.9: пересечение правок не приводит к незаметной потере.
 * Комнаты: фиксированный RemoteCalendarDataSource + настоящая Room.
 */
@RunWith(AndroidJUnit4::class)
class SyncConflictEndToEndTest {

    private lateinit var db: MyCalendarDatabase
    private lateinit var events: EventRepository
    private lateinit var conflicts: ConflictRepository
    private lateinit var sync: SyncRepositoryImpl
    private lateinit var resolve: ResolveConflictUseCase
    private val cloud = FakeRemote()

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
        events = EventRepositoryImpl(db.eventDao(), VoiceNoteStorage(context))
        conflicts = ConflictRepositoryImpl(db.syncConflictDao())
        val scheduler = ReminderScheduler(context)
        sync = SyncRepositoryImpl(
            eventRepository = events,
            authRepository = FakeAuth(),
            settingsRepository = FakeSettings(),
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
        db.close()
    }

    /** Синхронизированное событие с предком + локальная правка названия. */
    private suspend fun seedLocallyEdited(title: String) {
        events.saveFromRemote(
            Event(
                id = "e1", title = base.title, description = base.description,
                startTime = base.startTime, endTime = base.endTime,
                reminderMinutes = base.reminderMinutes, repeatRule = base.repeatRule,
                calendarProvider = CalendarProvider.GOOGLE, remoteId = "r1", updatedAt = 10L,
            ),
            syncBase = base,
        )
        events.saveFromUser(
            events.getEvent("e1")!!.copy(title = title)
        )
    }

    @Test
    fun intersection_keeps_local_value_and_creates_one_conflict() = runBlocking {
        seedLocallyEdited("Моя правка")
        cloud.events += remoteSnapshot(base.copy(title = "Правка из облака"))

        val result = sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(1, result.conflicts)
        assertEquals("Моя правка", events.getEvent("e1")?.title)
        assertEquals(1, conflicts.observeOpenCount().first())
        val open = conflicts.observeConflicts().first().single()
        assertEquals(ConflictKind.CONTENT, open.kind)
        assertEquals(setOf(SyncField.TITLE), open.contested)
        // Спорное поле не уехало в облако, пока конфликт открыт
        assertEquals(0, cloud.updateCount)
    }

    @Test
    fun second_pull_of_same_cloud_does_not_duplicate_conflict() = runBlocking {
        seedLocallyEdited("Моя правка")
        cloud.events += remoteSnapshot(base.copy(title = "Правка из облака"))

        sync.syncProvider(CalendarProvider.GOOGLE)
        sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(1, conflicts.observeOpenCount().first())
    }

    @Test
    fun resolving_pushes_choice_and_moves_predecessor() = runBlocking {
        seedLocallyEdited("Моя правка")
        cloud.events += remoteSnapshot(base.copy(title = "Правка из облака"))
        sync.syncProvider(CalendarProvider.GOOGLE)
        val open = conflicts.observeConflicts().first().single()

        resolve(open, remoteChosen = setOf(SyncField.TITLE))

        assertEquals(0, conflicts.observeOpenCount().first())
        assertEquals("Правка из облака", events.getEvent("e1")?.title)
        // Разобранное состояние ещё не в облаке — оно в очереди на отправку.
        assertTrue(events.getPendingSyncEvents().any { it.event.id == "e1" })
        // Разбор передвигает предка: выбранное значение и есть новая согласованная точка.
        val storedBase = SyncSnapshot.fromJson(db.eventDao().getSyncState("e1")?.syncBase)
        assertEquals(SyncSnapshot.of(events.getEvent("e1")!!), storedBase)

        sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(1, cloud.updateCount)
        assertTrue(!events.getPendingSyncEvents().any { it.event.id == "e1" })
        // Тот же облачный снимок после разбора не воскрешает конфликт.
        assertEquals(0, conflicts.observeOpenCount().first())
    }

    @Test
    fun disjoint_changes_merge_without_touching_the_conflict_queue() = runBlocking {
        seedLocallyEdited("Моя правка")
        cloud.events += remoteSnapshot(base.copy(description = "Облачное описание", title = base.title))

        val result = sync.syncProvider(CalendarProvider.GOOGLE)

        assertEquals(0, result.conflicts)
        assertEquals("Моя правка", events.getEvent("e1")?.title)
        assertEquals("Облачное описание", events.getEvent("e1")?.description)
        assertEquals(0, conflicts.observeOpenCount().first())
    }

    @Test
    fun cloud_deletion_with_local_edits_keeps_row_and_file() = runBlocking {
        seedLocallyEdited("Моя правка")
        cloud.events += remoteSnapshot(base).copy(deleted = true)

        sync.syncProvider(CalendarProvider.GOOGLE)

        assertNotNull(events.getEvent("e1"))
        val open = conflicts.observeConflicts().first().single()
        assertEquals(ConflictKind.REMOTE_DELETED, open.kind)

        resolve.resolveDeletion(open, DeletionChoice.DELETE_EVERYWHERE)

        assertNull(events.getEvent("e1"))
        assertEquals(0, conflicts.observeOpenCount().first())
    }

    private fun remoteSnapshot(snapshot: SyncSnapshot) = RemoteEvent(
        remoteId = "r1",
        title = snapshot.title,
        description = snapshot.description,
        startTime = snapshot.startTime,
        endTime = snapshot.endTime,
        reminderMinutes = snapshot.reminderMinutes,
        repeatRule = snapshot.repeatRule,
        updatedAt = 900L,
    )
}

/** Облако-фикс: список событий и счётчик push-вызовов — без сети и CalendarContract. */
private class FakeRemote : RemoteCalendarDataSource {
    val events = mutableListOf<RemoteEvent>()
    var updateCount = 0
    var deletedRemoteId: String? = null
    override val hasReliableUpdatedAt: Boolean get() = false
    override suspend fun fetchUpdatedSince(sinceMillis: Long): List<RemoteEvent> = events.toList()
    override suspend fun insert(event: Event): String = "r-new"
    override suspend fun update(remoteId: String, event: Event) { updateCount++ }
    override suspend fun delete(remoteId: String) { deletedRemoteId = remoteId }
}

private class FakeAuth : AuthRepository {
    // syncProvider проверяет наличие аккаунта ДО doSync: без него тест
    // завершился бы «Календарь GOOGLE не подключён» и до слияния не дошёл.
    private val account = UserAccount(CalendarProvider.GOOGLE, "test@example.com")

    override val connections: Flow<List<CalendarConnection>> = flowOf(
        listOf(CalendarConnection(CalendarProvider.GOOGLE, account))
    )
    override suspend fun accountFor(provider: CalendarProvider): UserAccount? =
        account.takeIf { provider == CalendarProvider.GOOGLE }
    override suspend fun connectedProviders() = listOf(CalendarProvider.GOOGLE)
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
}
```

Важные детали, которые тест закрепляет:
- `hasReliableUpdatedAt = false` — системный календарь; после Task 6 на него никто не смотрится, проверка работает так же.
- assert строки `assertEquals(base, SyncSnapshot.fromJson(...))` в третьем тесте: разбор конфликта НЕ двигает предка (инвариант раздела 2 дизайна).
- `DeletionChoice` импортируется из `com.example.mycalendar.domain.usecase`.

- [ ] **Step 2: Прогн (нужен эмулятор)**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.SyncConflictEndToEndTest`
Expected: PASS (5 тестов). Если устройства нет — написать в докладе задачи «SyncConflictEndToEndTest не запущен: нет эмулятора», сборка `assembleDebug` обязательна.

- [ ] **Step 3: Коммит**

```bash
git add app/src/androidTest/java/com/example/mycalendar/SyncConflictEndToEndTest.kt
git commit -m "test(sync): сквозная проверка очереда конфликтов на Room и фиксе облака"
```

---

### Task 9: Счётчик конфликтов и список

**Files:**
- Create: `app/src/main/java/com/example/mycalendar/presentation/conflict/ConflictListViewModel.kt`
- Create: `app/src/main/java/com/example/mycalendar/presentation/conflict/ConflictListScreen.kt`
- Modify: `presentation/navigation/Screen.kt` (6-24)
- Modify: `presentation/navigation/AppNavGraph.kt` (84-140)
- Modify: `presentation/settings/SettingsViewModel.kt` (61-134)
- Modify: `presentation/settings/SettingsScreen.kt` (79-241)
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `ConflictRepository.observeOpenCount/observeConflicts` (Task 4), `SyncConflict` (Task 4).
- Produces: `Screen.Conflicts` (route `"conflicts"`), `Screen.ConflictDetail` (route `"conflict/{conflictId}"`, `fun create(id: String)`), `SettingsUiState.conflictCount: Int`, `ConflictListViewModel.uiState: StateFlow<List<SyncConflict>>`, `ConflictListScreen(onBack, onOpenConflict: (String) -> Unit, viewModel)` (параметр `onOpenConflict` использует id конфликта).

- [ ] **Step 1: Маршруты**

`Screen.kt`:

```kotlin
    /** Очередь открытых конфликтов синхронизации. */
    data object Conflicts : Screen("conflicts")

    /** Разбор одного конфликта. */
    data object ConflictDetail : Screen("conflict/{conflictId}") {
        fun create(conflictId: String): String = "conflict/$conflictId"
    }
```

- [ ] **Step 2: Счётчик в настройках**

`SettingsViewModel.kt` — состояние и combine:

```kotlin
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Всегда две карточки — подключённые и нет. */
    val calendars: List<CalendarConnectionState> = emptyList(),
    /** Открытые конфликты синхронизации; 0 — строку не показываем. */
    val conflictCount: Int = 0,
)
```

```kotlin
    /** Результат последней операции по провайдеру. */
    private val results = MutableStateFlow<Map<CalendarProvider, SyncResult>>(emptyMap())

    /** Открытые конфликты — из таблицы sync_conflicts. */
    private val conflictCount: Flow<Int> = conflictRepository.observeOpenCount()

    /** Флаги загрузки и результаты по провайдерам одним снимком. */
    private data class SyncFlags(
        val connecting: Set<CalendarProvider>,
        val syncing: Set<CalendarProvider>,
        val results: Map<CalendarProvider, SyncResult>,
        val conflicts: Int,
    )
```

`combine(...)` в `uiState` (113-134) дополняется:

```kotlin
    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.themeMode,
        settingsRepository.dynamicColor,
        authRepository.connections,
        lastSyncTimes,
        combine(connecting, syncing, results, conflictCount, ::SyncFlags),
    ) { themeMode, dynamicColor, connections, syncTimes, flags ->
        SettingsUiState(
            themeMode = themeMode,
            dynamicColor = dynamicColor,
            calendars = connections.map { connection ->
                CalendarConnectionState(
                    provider = connection.provider,
                    connection = connection,
                    isConnecting = connection.provider in flags.connecting,
                    isSyncing = connection.provider in flags.syncing,
                    lastSyncTime = syncTimes[connection.provider] ?: 0L,
                    result = flags.results[connection.provider],
                )
            },
            conflictCount = flags.conflicts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())
```

Конструктор ViewModel дополняется `private val conflictRepository: ConflictRepository`.

- [ ] **Step 3: Строка в интерфейсе настроек**

`SettingsScreen.kt` — параметр и карточка сразу после кнопки `sync_all` (197-205):

```kotlin
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenConflicts: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
```

```kotlin
            if (state.conflictCount > 0) {
                Card(
                    onClick = onOpenConflicts,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.conflicts_count, state.conflictCount),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
```

`AppNavGraph.kt` — вызов `SettingsScreen` дополняется `onOpenConflicts = { navController.navigate(Screen.Conflicts.route) }`, и рядом с `composable(Screen.Settings.route)` добавляется:

```kotlin
        composable(Screen.Conflicts.route) {
            ConflictListScreen(
                onBack = { navController.popBackStack() },
                onOpenConflict = { id -> navController.navigate(Screen.ConflictDetail.create(id)) },
            )
        }
```

- [ ] **Step 4: Список**

`presentation/conflict/ConflictListViewModel.kt`:

```kotlin
package com.example.mycalendar.presentation.conflict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.repository.ConflictRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ConflictListViewModel @Inject constructor(
    conflictRepository: ConflictRepository,
) : ViewModel() {

    val uiState: StateFlow<List<SyncConflict>> = conflictRepository.observeConflicts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
```

`presentation/conflict/ConflictListScreen.kt`:

```kotlin
package com.example.mycalendar.presentation.conflict

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mycalendar.R
import com.example.mycalendar.domain.model.ConflictKind
import java.text.DateFormat
import java.util.Date

/**
 * Очередь конфликтов синхронизации: что столкнулось и сколько полей надо разобрать.
 * Локальная версия остаётся в силе, пока пользователь не выберет исход.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictListScreen(
    onBack: () -> Unit,
    onOpenConflict: (String) -> Unit,
    viewModel: ConflictListViewModel = hiltViewModel(),
) {
    val conflicts by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflicts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (conflicts.isEmpty()) {
            Text(
                text = stringResource(R.string.conflicts_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(count = conflicts.size, key = { conflicts[it].id }) { index ->
                val conflict = conflicts[index]
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenConflict(conflict.id) },
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(conflict.remote.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = DateFormat.getDateInstance().format(Date(conflict.detectedAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = when (conflict.kind) {
                                ConflictKind.CONTENT -> pluralStringResource(
                                    R.plurals.conflict_contested_fields,
                                    conflict.contested.size,
                                    conflict.contested.size,
                                )
                                ConflictKind.REMOTE_DELETED ->
                                    stringResource(R.string.conflict_remote_deleted)
                                ConflictKind.LOCAL_DELETED ->
                                    stringResource(R.string.conflict_local_deleted)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
```

Тексты (`app/src/main/res/values/strings.xml`) и импорт `androidx.compose.ui.res.pluralStringResource`:

```xml
    <string name="conflicts_title">Конфликты синхронизации</string>
    <string name="conflicts_count">Конфликты: %1$d</string>
    <string name="conflicts_empty">Конфликтов нет — все правки применены автоматически</string>
    <string name="conflict_remote_deleted">Событие удалено в облаке, локально есть ваши правки</string>
    <string name="conflict_local_deleted">Событие удалено локально, в облаке его правили</string>
    <plurals name="conflict_contested_fields">
        <item quantity="one">%1$d спорное поле</item>
        <item quantity="few">%1$d спорных поля</item>
        <item quantity="many">%1$d спорных полей</item>
        <item quantity="other">%1$d спорного поля</item>
    </plurals>
```

- [ ] **Step 5: Сборка и прогон**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Run (при наличии эмулятора): `./gradlew :app:installDebug` и вручную: настройки → «Конфликты: N» → список; при нуле строки нет.
Expected: список показывает заголовок, дату и счётчик спорных полей. Если эмулятора нет — писать «UI не проверен».

- [ ] **Step 6: Коммит**

```bash
git add app/src/main/java/com/example/mycalendar/presentation/conflict app/src/main/java/com/example/mycalendar/presentation/navigation app/src/main/java/com/example/mycalendar/presentation/settings app/src/main/res/values/strings.xml
git commit -m "feat(ui): счётчик конфликтов в настройках и список очереди"
```

---

### Task 10: Экран разбора по полям

**Files:**
- Create: `app/src/main/java/com/example/mycalendar/presentation/conflict/ConflictDetailViewModel.kt`
- Create: `app/src/main/java/com/example/mycalendar/presentation/conflict/ConflictDetailScreen.kt`
- Modify: `presentation/navigation/AppNavGraph.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `ConflictRepository.get` (Task 4), `ResolveConflictUseCase` (Task 7), `SyncConflict`/`SyncField`/`SyncSnapshot`, `EventRepository.getEvent` (Task 3), маршруты Task 9.
- Produces: `ConflictDetailViewModel.uiState: StateFlow<ConflictDetailState>`,
  `ConflictDetailState(conflict: SyncConflict?, local: Event?, remoteChosen: Set<SyncField>, isResolved: Boolean)`,
  `fun onChoose(field: SyncField, fromCloud: Boolean)`, `fun apply()`, `fun chooseDeletion(choice: DeletionChoice)`.

- [ ] **Step 1: ViewModel**

`presentation/conflict/ConflictDetailViewModel.kt`:

```kotlin
package com.example.mycalendar.presentation.conflict

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.repository.ConflictRepository
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.usecase.DeletionChoice
import com.example.mycalendar.domain.usecase.ResolveConflictUseCase
import com.example.mycalendar.presentation.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Разбор одного конфликта: по каждому спорному полю — локальное или облачное значение. */
data class ConflictDetailState(
    val conflict: SyncConflict? = null,
    /** Текущая локальная запись — «локальная карточка» в сравнении. */
    val local: Event? = null,
    /** Поля, где выбрано облачное значение; по умолчанию выбрано локальное. */
    val remoteChosen: Set<SyncField> = emptySet(),
    val isResolved: Boolean = false,
)

@HiltViewModel
class ConflictDetailViewModel @Inject constructor(
    private val conflictRepository: ConflictRepository,
    private val eventRepository: EventRepository,
    private val resolveConflict: ResolveConflictUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val conflictId: String = savedStateHandle.get<String>("conflictId").orEmpty()

    private val _uiState = MutableStateFlow(ConflictDetailState())
    val uiState: StateFlow<ConflictDetailState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val conflict = conflictRepository.get(conflictId)
            val local = conflict?.let { eventRepository.getEvent(it.eventId) }
            // События могли удалить вручную — конфликта больше нет, выходим назад.
            _uiState.update { it.copy(conflict = conflict, local = local, isResolved = conflict == null) }
        }
    }

    fun onChoose(field: SyncField, fromCloud: Boolean) {
        _uiState.update {
            it.copy(remoteChosen = if (fromCloud) it.remoteChosen + field else it.remoteChosen - field)
        }
    }

    fun apply() {
        val state = _uiState.value
        val conflict = state.conflict ?: return
        viewModelScope.launch {
            resolveConflict(conflict, state.remoteChosen)
            _uiState.update { it.copy(isResolved = true) }
        }
    }

    fun chooseDeletion(choice: DeletionChoice) {
        val conflict = _uiState.value.conflict ?: return
        viewModelScope.launch {
            resolveConflict.resolveDeletion(conflict, choice)
            _uiState.update { it.copy(isResolved = true) }
        }
    }
}
```

- [ ] **Step 2: Экран**

`presentation/conflict/ConflictDetailScreen.kt`:

```kotlin
package com.example.mycalendar.presentation.conflict

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mycalendar.R
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.usecase.DeletionChoice

/**
 * Разбор конфликта по полям: локальное значение держится по умолчанию,
 * облачное выбирается чипом. Кнопка «Применить» пишет выбранные значения,
 * делает их новым предком и закрывает конфликт; в облако они уходят при
 * следующей синхронизации.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictDetailScreen(
    onBack: () -> Unit,
    viewModel: ConflictDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.isResolved) {
        if (state.isResolved) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflict_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val conflict = state.conflict
        val local = state.local
        if (conflict == null || local == null) return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeletionHelp(conflict)

            conflict.contested.sorted().forEach { field ->
                FieldRow(
                    field = field,
                    local = local,
                    conflict = conflict,
                    chosenFromCloud = field in state.remoteChosen,
                    onChoose = { fromCloud -> viewModel.onChoose(field, fromCloud) },
                )
            }

            if (conflict.contested.isNotEmpty()) {
                Button(
                    onClick = viewModel::apply,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.conflict_apply)) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.chooseDeletion(DeletionChoice.DELETE_EVERYWHERE) }) {
                        Text(stringResource(R.string.conflict_delete_everywhere))
                    }
                    Button(onClick = { viewModel.chooseDeletion(DeletionChoice.RESTORE_FROM_CLOUD) }) {
                        Text(stringResource(R.string.conflict_restore_from_cloud))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeletionHelp(conflict: SyncConflict) {
    val text = when (conflict.kind) {
        com.example.mycalendar.domain.model.ConflictKind.REMOTE_DELETED ->
            stringResource(R.string.conflict_remote_deleted)
        com.example.mycalendar.domain.model.ConflictKind.LOCAL_DELETED ->
            stringResource(R.string.conflict_local_deleted)
        com.example.mycalendar.domain.model.ConflictKind.CONTENT ->
            stringResource(R.string.conflict_content_help)
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

/** Одна строка сравнения: локальное значение и облачное, выбор чипами. */
@Composable
private fun FieldRow(
    field: SyncField,
    local: Event,
    conflict: SyncConflict,
    chosenFromCloud: Boolean,
    onChoose: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(field.labelRes()), style = MaterialTheme.typography.titleMedium)
            Text(
                text = field.localValue(local),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = field.cloudValue(conflict),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !chosenFromCloud,
                    onClick = { onChoose(false) },
                    label = { Text(stringResource(R.string.conflict_use_local)) },
                )
                FilterChip(
                    selected = chosenFromCloud,
                    onClick = { onChoose(true) },
                    label = { Text(stringResource(R.string.conflict_use_cloud)) },
                )
            }
        }
    }
}

private fun SyncField.labelRes(): Int = when (this) {
    SyncField.TITLE -> R.string.field_title
    SyncField.DESCRIPTION -> R.string.field_description
    SyncField.START_TIME -> R.string.field_start_time
    SyncField.END_TIME -> R.string.field_end_time
    SyncField.REMINDER_MINUTES -> R.string.field_reminders
    SyncField.REPEAT_RULE -> R.string.field_repeat_rule
}

private fun SyncField.localValue(event: Event): String = when (this) {
    SyncField.TITLE -> event.title
    SyncField.DESCRIPTION -> event.description.orEmpty()
    SyncField.START_TIME -> formatTime(event.startTime)
    SyncField.END_TIME -> formatTime(event.endTime)
    SyncField.REMINDER_MINUTES -> formatReminders(event.reminderMinutes)
    SyncField.REPEAT_RULE -> formatRule(event.repeatRule)
}

private fun SyncField.cloudValue(conflict: SyncConflict): String = when (this) {
    SyncField.TITLE -> conflict.remote.title
    SyncField.DESCRIPTION -> conflict.remote.description.orEmpty()
    SyncField.START_TIME -> formatTime(conflict.remote.startTime)
    SyncField.END_TIME -> formatTime(conflict.remote.endTime)
    SyncField.REMINDER_MINUTES -> formatReminders(conflict.remote.reminderMinutes)
    SyncField.REPEAT_RULE -> formatRule(conflict.remote.repeatRule)
}
```

Форматтеры (тот же файл, внизу) — значения должны читаться человеком, поэтому время и напоминания
переиспользуют то, что уже есть в `presentation/event/EventDetailsScreen.kt`; если там приватные
функции, скопировать в этот файл:

```kotlin
private fun formatTime(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(millis))

private fun formatReminders(minutes: List<Int>): String =
    if (minutes.isEmpty()) "—" else minutes.sorted().joinToString(", ") { "$it мин" }

private fun formatRule(rule: com.example.mycalendar.domain.model.RepeatRule?): String =
    rule?.name ?: "—"
```

- [ ] **Step 3: Маршрут и тексты**

`AppNavGraph.kt`:

```kotlin
        composable(
            route = Screen.ConflictDetail.route,
            arguments = listOf(navArgument("conflictId") { type = NavType.StringType }),
        ) {
            ConflictDetailScreen(onBack = { navController.popBackStack() })
        }
```

+ импорт `com.example.mycalendar.presentation.conflict.ConflictDetailScreen`.

`strings.xml`:

```xml
    <string name="conflict_detail_title">Разбор конфликта</string>
    <string name="conflict_content_help">Выберите, какое значение оставить; остальное приложение объединило само</string>
    <string name="conflict_apply">Применить</string>
    <string name="conflict_use_local">Локальное</string>
    <string name="conflict_use_cloud">Из облака</string>
    <string name="conflict_delete_everywhere">Удалить везде</string>
    <string name="conflict_restore_from_cloud">Вернуть из облака</string>
    <string name="field_title">Название</string>
    <string name="field_description">Описание</string>
    <string name="field_start_time">Начало</string>
    <string name="field_end_time">Конец</string>
    <string name="field_reminders">Напоминания</string>
    <string name="field_repeat_rule">Повтор</string>
```

- [ ] **Step 4: Проверка**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, все JVM-тесты зелёные.

Run (эмулятор): `./gradlew :app:installDebug`, затем вручную — создать конфликт (править событие в Google Календаре на вебе и локально), открыть настройки → «Конфликты: 1» → список → разбор: выбрать «Из облака» по названию → «Применить» → запись ушла в облако при следующей синхронизации, счётчик обнулился; проверить, что метки и текстовая заметка остались на месте.
Expected: UI отрабатывает; спорное поле приняло облачное значение, `tags`/`textNote` целы.
Без эмулятора — в докладе прямо указать, что экран не проверен вживую.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/java/com/example/mycalendar/presentation/conflict app/src/main/java/com/example/mycalendar/presentation/navigation/AppNavGraph.kt app/src/main/res/values/strings.xml
git commit -m "feat(ui): экран разбора конфликта по полям"
```

---

## Self-review плана по спецификации

- §1 модель данных → Task 2 (колонка, таблица, FK cascade, миграция) + Task 4 (доменная модель).
- §2 алгоритм → Task 5 (чистые решения) и Task 6 (применение). Проверка по строкам таблицы:
  `remoteChanges` пуст → `Skip`; `localChanges` пуст при `pendingSync == true` → `Skip`
  (тест `unpushed state equal to predecessor is not overwritten`); `localChanges` пуст →
  `ApplyRemote`; дизъюнкт или совпавшие значения → `AutoMerge`/`Skip`
  (тесты `disjoint changes…`, `both sides reaching the same value…`, `repeated pull of an
  already merged row…`); пересечение с разными значениями → `Conflict`
  (`overlapping field goes to the queue…`).
- §2 «предок сдвигается в четырёх местах» → `ApplyRemote`/`Insert` (Task 5 + ветки pull в Task 6),
  `markSynced`/`attachToProvider` (шаг 3 Task 6), разбор — `saveResolved` (Task 3, вызывает
  Task 7). Правило «пока конфликт открыт, push событие не трогает» → шаг 3 Task 6 и тест
  `intersection_keeps_local_value_and_creates_one_conflict` (Task 8).
- §2 «pull не пропускает событие с открытым конфликтом, а обновляет строку» → `ConflictRepositoryImpl.save`
  (Task 4, переиспользование id) и тест `second_pull_of_same_cloud_does_not_duplicate_conflict`
  (Task 8), плюс тест репозитория `second save on same event reuses id…` (Task 4).
- §3 удаления и файл заметки → Task 5 (`Purge`/`PushDelete`/`LOCAL_DELETED`), Task 6 (`purge`,
  `DeleteEventUseCase`), Task 7 (`planResolution` + `saveResolved` с предком облака), тесты Task 8
  и `purge removes the voice file together with the row` (Task 3).
- §4 UI и слои → Task 9 (счётчик, список, маршруты) и Task 10 (разбор по полям, два исхода удаления).
- §5 тесты → JVM: Task 1, 3, 4, 5, 7; androidTest: Task 2 (миграция) и Task 8 (сквозной).
  Существующие `SyncPullLocalFieldsTest` и `SyncConflictResolverTest` учтены: первый не меняется,
  второй переписывается в Task 5 (LWW уходит).
- Сознательные исключения (сервер синхронизации, ETag, слияние внутри поля, массовый разбор,
  история, `values-en`) в задачах не появляются.

**Что остаётся открытым при исполнении (не правим молча):**

- `ResolveConflictUseCase` покрыт только тестом чистого правила `planResolution`: `ReminderScheduler`
  — конкретный класс с `Context`, без интерфейса, поэтому JVM-тест всего usecase требует либо
  нового контракта (вне объёма), либо Robolectric (в проекте нет). Реальное поведение разбора
  проверяет сквозной тест Task 8.
- Task 2 шаг 8, Task 8 и ручные проверки Task 9/10 требуют устройство или эмулятор; в этом
  окружении `adb` не в `PATH` и эмулятор не запущен — выполнять их надо с явной пометкой,
  запущено или нет.
- `androidx.room:room-testing` отсутствует в локальном Gradle-кэше: первый прогон Task 2 скачивает
  артефакт (доступ к `dl.google.com` проверен, HTTP 200).
