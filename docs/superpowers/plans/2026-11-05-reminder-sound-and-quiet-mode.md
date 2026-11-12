# Выбор сигнала напоминания и тихий режим — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Дать пользователю выбор сигнала отдельно для обычного события и для дня рождения (системный пикер) и глобальный «Тихий режим» (молчаливый оверлей с вибрацией), не меняя канал уведомления v2 и схему Room.

**Architecture:** Решение о сигнале принимается до запуска оверлея чистым доменным классом `ReminderSoundPolicy` (тихий режим → `Silent`; день рождения по метке `BIRTHDAY` или имени коллекции → URI дня рождения; иначе → URI будильника; пустой URI → встроенный). `AlarmReceiver` разрешает сигнал и передаёт его и в `NotificationHelper.showEventReminder`, и в фабрику `ReminderActivity.createIntent` — один источник интента на оба пути. Оверлей читает только примитивные экстры (`EXTRA_QUIET`, `EXTRA_SOUND_URI`) и ничего не берёт из DataStore. Тихий режим публикует уведомление на новом канале `IMPORTANCE_HIGH` без звука/вибрации/lights: full-screen intent доставляется только с HIGH и выше, а прямой запуск активности из receiver на API 29+ запрещён.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, Room, DataStore Preferences, WorkManager/AlarmManager, MediaPlayer, Vibrator/VibratorManager, RingtoneManager. minSdk 26, compile/targetSdk 37.

**Spec:** [docs/superpowers/specs/2026-11-05-reminder-sound-and-quiet-mode-design.md](../specs/2026-11-05-reminder-sound-and-quiet-mode-design.md)

## Global Constraints

- Канал `channel_event_reminders_v2` не трогаем: его importance/звук неизменяемы после создания; тихий режим — отдельный новый канал `channel_event_reminders_quiet_v2` (`IMPORTANCE_HIGH`, без звука, без вибрации, без lights); прежний id `channel_event_reminders_quiet` (с `IMPORTANCE_MIN`) удаляется, потому что пересоздание канала с тем же id восстанавливает прежние настройки.
- Выбранный сигнал звучит ТОЛЬКО в `ReminderActivity`; звук heads-up уведомления не меняется.
- Схема Room не меняется: признак дня рождения и тихий режим — настройки и производные от уже хранящихся полей (`Event.tags`, имя коллекции).
- `applicationId` (`com.example.mycalendar`) в этой задаче НЕ менять.
- `AudioAttributes` оверлея: `USAGE_ALARM` + `CONTENT_TYPE_MUSIC`, `isLooping = true` — и для встроенного, и для выбранного URI.
- Вибрация оверлея: пульс 600 мс / пауза 1200 мс (шаг `postDelayed` 1800 мс), пока окно открыто; на устройствах без вибрации (`hasVibrator() == false`) пульс пропускается, оверлей не падает.
- Приоритет тихого режима выше обоих выбранных сигналов; выбор сигнала при включённом тихом режиме сохраняется и возвращается после выключения.
- Пикер: `RingtoneManager.ACTION_RINGTONE_PICKER`, обе строки с `EXTRA_RINGTONE_TYPE = TYPE_ALARM`, `EXTRA_RINGTONE_SHOW_SILENT = false`, `EXTRA_RINGTONE_SHOW_DEFAULT = true`. Пункта «Без звука» нет.
- `READ_EXTERNAL_STORAGE` с `android:maxSdkVersion="28"`; запрос при первом выборе сигнала только на API ≤ 28; отказ не блокирует (откат на встроенный сигнал).
- Индикатор `Icons.Filled.NotificationsOff` в шапке календаря перед «Сегодня»: показывается только при активном тихом режиме, тап ВЫКЛЮЧАЕТ; включается режим только из настроек.
- Всё по-русски (код-идентификаторы на английском, строки UI и комментарии на русском). Эмодзи в новых строках и комментариях не использовать. Секретов и токенов в коде/тестах/коммитах нет.
- Тесты запускать только через `./gradlew`; один прогон Gradle одновременно. JVM-тесты: `kotlinx.coroutines.runBlocking` + `flowOf` (библиотеки `coroutines-test` НЕТ), фейки реализуют интерфейс целиком `private class` внизу файла. Connected-тесты: `@HiltAndroidTest` + `@RunWith(AndroidJUnit4::class)`, `HiltAndroidRule(order = 0)`, `hiltRule.inject()` вручную, `pm grant` через `uiAutomation.executeShellCommand`, импорт compose-rule из `androidx.compose.ui.test.junit4.v2`.
- Счётчики прогонов в README и `docs/how-to-verify.md` обновляются по ФАКТИЧЕСКИМ замерам (Task 8), а не по ожидаемым значениям.

## Поправки к спеке (решения контроллера, pre-flight)

Спека писалась 05.11.2026; после слияния серии тонирования номера строк сместились, а сверка с текущим дерев выявила четыре расхождения. Якоря ниже — по ТЕКУЩЕМУ дереву. Решения обратимы и записаны здесь, чтобы имплементер не спрашивал.

1. **`CalDavClient.kt` НЕ изменяется.** Спека §11 listar его как «не теряем displayName», но `data class Calendar(val url, val displayName, val ctag)` ([CalDavClient.kt:35](../../../app/src/main/java/com/example/mycalendar/data/remote/yandex/CalDavClient.kt#L35)) уже несёт `displayName`, а `discoverAndPickCalendar` возвращает `Calendar` целиком (:60). Реальная потеря — в кэше `YandexCalendarDataSource.calendarUrl()`, который сохраняет только `url`. Task 3 кэширует объект `Calendar` целиком; `CalDavClient` не трогаем.
2. **`BirthdayCalendar`: добавлено вхождение «дни рожд».** Спека §3 называет только «день рожд», но тест §10 требует «Дни рождения» → true, а «дни рождения» НЕ содержит подстроку «день рожд». Матчим «день рожд» ИЛИ «дни рожд» (обе грамматические формы), «birthda», отдельное слово «др». Стем «рожд» НЕ используется: он дал бы ложное срабатывание на «Рождество» и «Возрождение». Цена ошибки: календарь «Дни рождения» не распознан → сигнал дня рождения не применился бы; тест §10 это ловит.
3. **`showEventReminder` получает `sound: ReminderSound = ReminderSound.Builtin`.** Default сохраняет два существующих вызова в `NotificationActionsTest` (:87, :115) без правок. Цена ошибки: сломанный существующий connected-тест.
4. **Фабрика `createIntent(context, event, sound, timeText)`.** Спека §5 упростила сигнатуру до `(context, event, sound)`, но оверлей читает `EXTRA_TIME_TEXT` (строка «Начало в HH:mm …»), поэтому `timeText` — обязательный параметр. Оба вызывающих (`AlarmReceiver`, `NotificationHelper`) уже вычисляют `label` и передают его.
5. **Кнопка «Отложить» в оверлее показывается ВСЕГДА** (не только в тихом режиме). Спека §6 вводит её для тихого режима (там шторка сворачивает действия), но не запрещает показывать в громком. Один и тот же composable без ветвления по режиму проще; в громком режиме это удобное дублирование действия уведомления. `OverlaySnoozeTest` запускает оверлей с `Builtin` и проверяет кнопку.

---

## File Structure

**Создаются (продакшен):**
- `domain/model/ReminderSound.kt` — sealed-модель сигнала (`Silent`/`FromUri`/`Builtin`).
- `domain/reminder/BirthdayCalendar.kt` — распознавание коллекции дней рождения по имени.
- `domain/reminder/ReminderSoundPolicy.kt` — чистый класс разрешения сигнала (`@Inject constructor(SettingsRepository)`).
- `notifications/VibrationLoop.kt` — зацикленный пульс вибрации.
- `presentation/components/ReminderTags.kt` — testTag-константы новых контролов (по образцу `DayMarkUi.kt`).

**Создаются (тесты):**
- `app/src/test/java/com/example/mycalendar/domain/reminder/BirthdayCalendarTest.kt` (JVM, 7).
- `app/src/test/java/com/example/mycalendar/domain/reminder/ReminderSoundPolicyTest.kt` (JVM, 6).
- `app/src/androidTest/java/com/example/mycalendar/ReminderActivityIntentTest.kt` (connected, 3).
- `app/src/androidTest/java/com/example/mycalendar/OverlayQuietModeTest.kt` (connected, 1).
- `app/src/androidTest/java/com/example/mycalendar/OverlaySnoozeTest.kt` (connected, 1).
- `app/src/androidTest/java/com/example/mycalendar/QuietModeChannelTest.kt` (connected, 3).
- `app/src/androidTest/java/com/example/mycalendar/SettingsRemindersCardTest.kt` (connected, 2).
- `app/src/androidTest/java/com/example/mycalendar/QuietModeIndicatorTest.kt` (connected, 1).

**Изменяются:** `domain/repository/SettingsRepository.kt`, `data/preferences/SettingsRepositoryImpl.kt`, `data/remote/RemoteCalendarDataSource.kt`, `data/system/SystemCalendarDataSource.kt`, `data/remote/yandex/YandexCalendarDataSource.kt`, `data/repository/SyncRepositoryImpl.kt`, `notifications/ReminderActivity.kt`, `notifications/AlarmReceiver.kt`, `notifications/NotificationHelper.kt`, `presentation/settings/SettingsViewModel.kt`, `presentation/settings/SettingsScreen.kt`, `presentation/calendar/CalendarViewModel.kt`, `presentation/calendar/CalendarScreen.kt`, `AndroidManifest.xml`, `res/values/strings.xml`, `app/src/test/java/com/example/mycalendar/data/remote/yandex/YandexCalendarDataSourceTest.kt`, `README.md`, `docs/how-to-verify.md`.

**Порядок и зависимости:** Task 1 (домен) → Task 2 (хранилище+политика) → Task 3 (имя коллекции) → Task 4 (оверлей) → Task 5 (wiring receiver/helper) → Task 6 (карточка настроек) → Task 7 (индикатор) → Task 8 (прогоны+счётчики). Tasks 3 и 4 независимы друг от друга (оба потребляют Task 1/2); диспетчер запускает их последовательно во избежание конфликтов в общем дереве.

---

### Task 1: Домен — ReminderSound и BirthdayCalendar

**Files:**
- Create: `app/src/main/java/com/example/mycalendar/domain/model/ReminderSound.kt`
- Create: `app/src/main/java/com/example/mycalendar/domain/reminder/BirthdayCalendar.kt`
- Test: `app/src/test/java/com/example/mycalendar/domain/reminder/BirthdayCalendarTest.kt`

**Interfaces:**
- Consumes: ничего (чистый домен).
- Produces:
  - `sealed interface ReminderSound` с `data object Silent`, `data class FromUri(val uri: String)`, `data object Builtin`.
  - `object BirthdayCalendar { fun matches(calendarName: String?): Boolean }`.

- [ ] **Step 1:	Write the failing test**

Create `app/src/test/java/com/example/mycalendar/domain/reminder/BirthdayCalendarTest.kt`:

```kotlin
package com.example.mycalendar.domain.reminder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Распознавание коллекции дней рождения по имени. Совпадение по нормализованному
 * (lowercase) имени: «день рожд»/«дни рожд», «birthda», отдельное слово «др».
 * «др» проверяется по слову, а не по подстроке: внутри «дровни» оно ложно.
 */
class BirthdayCalendarTest {

    @Test
    fun `plural russian name matches`() {
        assertTrue(BirthdayCalendar.matches("Дни рождения"))
    }

    @Test
    fun `singular russian name with suffix matches`() {
        assertTrue(BirthdayCalendar.matches("День рождения (Яндекс)"))
    }

    @Test
    fun `english name matches`() {
        assertTrue(BirthdayCalendar.matches("Birthdays"))
    }

    @Test
    fun `short abbreviation matches`() {
        assertTrue(BirthdayCalendar.matches("ДР"))
    }

    @Test
    fun `substring inside another word does not match`() {
        assertFalse(BirthdayCalendar.matches("Дровни"))
    }

    @Test
    fun `unrelated name does not match`() {
        assertFalse(BirthdayCalendar.matches("Рабочие встречи"))
    }

    @Test
    fun `null does not match`() {
        assertFalse(BirthdayCalendar.matches(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*BirthdayCalendarTest'`
Expected: FAIL — `BirthdayCalendar` unresolved reference.

- [ ] **Step 3: Write the model and matcher**

Create `app/src/main/java/com/example/mycalendar/domain/model/ReminderSound.kt`:

```kotlin
package com.example.mycalendar.domain.model

/** Что играть в оверлее. Вычисляется до запуска активности, активность не читает настройки. */
sealed interface ReminderSound {
    data object Silent : ReminderSound
    data class FromUri(val uri: String) : ReminderSound
    data object Builtin : ReminderSound
}
```

Create `app/src/main/java/com/example/mycalendar/domain/reminder/BirthdayCalendar.kt`:

```kotlin
package com.example.mycalendar.domain.reminder

/**
 * Коллекция дней рождения по имени. У Google имя календаря — displayName системного
 * календаря, у Яндекса — displayname CalDAV-коллекции; единого поля нет, поэтому
 * распознаём по нормализованному имени. «др» — по слову, а не по подстроке:
 * внутри «дровни» оно давало бы ложное срабатывание. Стем «рожд» не используем —
 * он совпал бы с «Рождество» и «Возрождение».
 */
object BirthdayCalendar {

    fun matches(calendarName: String?): Boolean {
        val name = calendarName?.lowercase()?.trim().orEmpty()
        if (name.isEmpty()) return false
        if ("день рожд" in name || "дни рожд" in name) return true
        if ("birthda" in name) return true
        return "др" in name.split(Regex("[^\\p{L}\\p{N}]+"))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*BirthdayCalendarTest'`
Expected: PASS — 7 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/mycalendar/domain/model/ReminderSound.kt \
        app/src/main/java/com/example/mycalendar/domain/reminder/BirthdayCalendar.kt \
        app/src/test/java/com/example/mycalendar/domain/reminder/BirthdayCalendarTest.kt
git commit -m "feat(reminder): модель сигнала и распознавание коллекции дней рождения"
```

---

### Task 2: Хранилище настроек и политика сигнала

**Files:**
- Modify: `app/src/main/java/com/example/mycalendar/domain/repository/SettingsRepository.kt`
- Modify: `app/src/main/java/com/example/mycalendar/data/preferences/SettingsRepositoryImpl.kt`
- Create: `app/src/main/java/com/example/mycalendar/domain/reminder/ReminderSoundPolicy.kt`
- Test: `app/src/test/java/com/example/mycalendar/domain/reminder/ReminderSoundPolicyTest.kt`

**Interfaces:**
- Consumes: `ReminderSound`, `BirthdayCalendar` (Task 1); `Event`, `EventTag`, `CalendarProvider` ([Event.kt](../../../app/src/main/java/com/example/mycalendar/domain/model/Event.kt)).
- Produces (новые члены `SettingsRepository`):
  - `val alarmSoundUri: Flow<String?>`, `val birthdaySoundUri: Flow<String?>`, `val quietMode: Flow<Boolean>`, `fun calendarName(provider: CalendarProvider): Flow<String?>`
  - `suspend fun setAlarmSoundUri(uri: String?)`, `suspend fun setBirthdaySoundUri(uri: String?)`, `suspend fun setQuietMode(enabled: Boolean)`, `suspend fun setCalendarName(provider: CalendarProvider, name: String)`
  - `class ReminderSoundPolicy @Inject constructor(private val settingsRepository: SettingsRepository) { suspend fun resolve(event: Event, calendarName: String?): ReminderSound }`
  - DI: новой привязки НЕ нужно — конструкторная инъекция (`SettingsRepository` уже связан `@Binds` в [RepositoryModule.kt:48](../../../app/src/main/java/com/example/mycalendar/di/RepositoryModule.kt#L48)).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/mycalendar/domain/reminder/ReminderSoundPolicyTest.kt`:

```kotlin
package com.example.mycalendar.domain.reminder

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.ReminderSound
import com.example.mycalendar.domain.model.ThemeMode
import com.example.mycalendar.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Порядок разрешения сигнала: тихий режим поверх всего; день рождения (метка BIRTHDAY
 * ИЛИ имя коллекции) → URI дня рождения; иначе → URI будильника; пустой/null URI → Builtin.
 */
class ReminderSoundPolicyTest {

    private fun event(tags: List<EventTag> = emptyList()) = Event(
        id = "e1",
        title = "Событие",
        startTime = 1_700_000_000_000L,
        endTime = 1_700_003_600_000L,
        tags = tags,
    )

    private fun policy(
        quiet: Boolean = false,
        alarm: String? = null,
        birthday: String? = null,
    ) = ReminderSoundPolicy(FakeSettingsRepository(quiet, alarm, birthday))

    @Test
    fun `quiet mode wins over chosen uri`() = runBlocking {
        val sound = policy(quiet = true, alarm = "content://alarm").resolve(event(), null)
        assertEquals(ReminderSound.Silent, sound)
    }

    @Test
    fun `birthday tag uses birthday uri`() = runBlocking {
        val sound = policy(birthday = "content://bday", alarm = "content://alarm")
            .resolve(event(tags = listOf(EventTag.BIRTHDAY)), null)
        assertEquals(ReminderSound.FromUri("content://bday"), sound)
    }

    @Test
    fun `birthday collection name uses birthday uri without tag`() = runBlocking {
        val sound = policy(birthday = "content://bday", alarm = "content://alarm")
            .resolve(event(), "Дни рождения")
        assertEquals(ReminderSound.FromUri("content://bday"), sound)
    }

    @Test
    fun `plain event uses alarm uri`() = runBlocking {
        val sound = policy(alarm = "content://alarm").resolve(event(), null)
        assertEquals(ReminderSound.FromUri("content://alarm"), sound)
    }

    @Test
    fun `null uri falls back to builtin`() = runBlocking {
        val sound = policy(alarm = null).resolve(event(), null)
        assertEquals(ReminderSound.Builtin, sound)
    }

    @Test
    fun `empty uri falls back to builtin`() = runBlocking {
        val sound = policy(alarm = "").resolve(event(), null)
        assertEquals(ReminderSound.Builtin, sound)
    }

    /** Фейк: три рабочих потока из памяти, остальные члены интерфейса — заглушки. */
    private class FakeSettingsRepository(
        quiet: Boolean,
        alarm: String?,
        birthday: String?,
    ) : SettingsRepository {
        override val quietMode: Flow<Boolean> = flowOf(quiet)
        override val alarmSoundUri: Flow<String?> = flowOf(alarm)
        override val birthdaySoundUri: Flow<String?> = flowOf(birthday)
        override val themeMode: Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
        override val lastSyncTime: Flow<Long> = flowOf(0L)
        override val notificationsAsked: Flow<Boolean> = flowOf(false)
        override val onboardingCompleted: Flow<Boolean> = flowOf(true)
        override fun lastSyncTime(provider: CalendarProvider): Flow<Long> = flowOf(0L)
        override fun collectionUrl(provider: CalendarProvider): Flow<String?> = flowOf(null)
        override fun calendarName(provider: CalendarProvider): Flow<String?> = flowOf(null)
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setLastSyncTime(timeMillis: Long) = Unit
        override suspend fun setNotificationsAsked() = Unit
        override suspend fun setOnboardingCompleted(completed: Boolean) = Unit
        override suspend fun setLastSyncTime(provider: CalendarProvider, timeMillis: Long) = Unit
        override suspend fun setCollectionUrl(provider: CalendarProvider, url: String) = Unit
        override suspend fun clearCollectionUrl(provider: CalendarProvider) = Unit
        override suspend fun setAlarmSoundUri(uri: String?) = Unit
        override suspend fun setBirthdaySoundUri(uri: String?) = Unit
        override suspend fun setQuietMode(enabled: Boolean) = Unit
        override suspend fun setCalendarName(provider: CalendarProvider, name: String) = Unit
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*ReminderSoundPolicyTest'`
Expected: FAIL — `ReminderSoundPolicy` unresolved; `quietMode`/`alarmSoundUri`/`birthdaySoundUri`/`calendarName` не члены `SettingsRepository`.

- [ ] **Step 3: Extend the repository contract**

In `app/src/main/java/com/example/mycalendar/domain/repository/SettingsRepository.kt`, add the four getters after `onboardingCompleted` (после строки 21) and the four setters + `calendarName` после `clearCollectionUrl` (перед закрывающей `}` интерфейса, строка 52). Точный блок для вставки внутрь интерфейса:

```kotlin
    /** URI выбранного сигнала обычного напоминания (null — встроенный reminder_sound). */
    val alarmSoundUri: Flow<String?>

    /** URI выбранного сигнала дня рождения (null — встроенный reminder_sound). */
    val birthdaySoundUri: Flow<String?>

    /** Глобальный тихий режим: оверлей молчит и вибрирует, сигнал не звенит. */
    val quietMode: Flow<Boolean>

    /**
     * Имя коллекции провайдера, из которой прошла последняя синхронизация (null — ещё
     * не запоминали). Нужно для распознавания календаря дней рождения: у Google это
     * displayName календаря, у Яндекса — displayname CalDAV-коллекции.
     */
    fun calendarName(provider: CalendarProvider): Flow<String?>

    suspend fun setAlarmSoundUri(uri: String?)
    suspend fun setBirthdaySoundUri(uri: String?)
    suspend fun setQuietMode(enabled: Boolean)
    suspend fun setCalendarName(provider: CalendarProvider, name: String)
```

- [ ] **Step 4: Implement the DataStore keys and accessors**

In `app/src/main/java/com/example/mycalendar/data/preferences/SettingsRepositoryImpl.kt`:

(a) Add keys inside `private object Keys` (после `ONBOARDING_COMPLETED`, строка 30):

```kotlin
        val ALARM_SOUND_URI = stringPreferencesKey("alarm_sound_uri")
        val BIRTHDAY_SOUND_URI = stringPreferencesKey("birthday_sound_uri")
        val QUIET_MODE = booleanPreferencesKey("quiet_mode")
```

(b) Add the provider-name key factory inside `Keys` (после `collection(provider)`, строка 38):

```kotlin
        /** Имя коллекции: calendar_name_google / _yandex. */
        fun calendarName(provider: CalendarProvider) =
            stringPreferencesKey("calendar_name_${provider.name.lowercase()}")
```

(c) Add getters after `onboardingCompleted` getter (после строки 57):

```kotlin
    override val alarmSoundUri: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.ALARM_SOUND_URI]
    }

    override val birthdaySoundUri: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.BIRTHDAY_SOUND_URI]
    }

    override val quietMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.QUIET_MODE] ?: false
    }
```

(d) Add the provider-name getter after `collectionUrl` getter (после строки 89):

```kotlin
    override fun calendarName(provider: CalendarProvider): Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.calendarName(provider)]
    }
```

(e) Add setters after `setCollectionUrl` (после строки 93):

```kotlin
    override suspend fun setAlarmSoundUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.ALARM_SOUND_URI) else prefs[Keys.ALARM_SOUND_URI] = uri
        }
    }

    override suspend fun setBirthdaySoundUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.BIRTHDAY_SOUND_URI) else prefs[Keys.BIRTHDAY_SOUND_URI] = uri
        }
    }

    override suspend fun setQuietMode(enabled: Boolean) {
        dataStore.edit { it[Keys.QUIET_MODE] = enabled }
    }

    override suspend fun setCalendarName(provider: CalendarProvider, name: String) {
        dataStore.edit { it[Keys.calendarName(provider)] = name }
    }
```

(f) Replace `clearCollectionUrl` (строки 95-97) — стирает и имя коллекции (смысл ключей один: «от какой коллекции знаем правду»; единственный caller — [AuthRepositoryImpl.kt:204](../../../app/src/main/java/com/example/mycalendar/data/repository/AuthRepositoryImpl.kt#L204)):

```kotlin
    override suspend fun clearCollectionUrl(provider: CalendarProvider) {
        dataStore.edit {
            it.remove(Keys.collection(provider))
            it.remove(Keys.calendarName(provider))
        }
    }
```

- [ ] **Step 5: Implement the policy**

Create `app/src/main/java/com/example/mycalendar/domain/reminder/ReminderSoundPolicy.kt`:

```kotlin
package com.example.mycalendar.domain.reminder

import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.ReminderSound
import com.example.mycalendar.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Вся арифметика выбора сигнала — здесь, до запуска оверлея. Порядок: тихий режим
 * поверх всего; день рождения (метка BIRTHDAY ИЛИ имя коллекции) → URI дня рождения;
 * иначе → URI будильника; пустой/null URI → встроенный reminder_sound. Пустая строка
 * в DataStore означает «не выбирали», а не «без звука» — для молчания служит тихий режим.
 */
class ReminderSoundPolicy @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    suspend fun resolve(event: Event, calendarName: String?): ReminderSound {
        if (settingsRepository.quietMode.first()) return ReminderSound.Silent
        val isBirthday = EventTag.BIRTHDAY in event.tags || BirthdayCalendar.matches(calendarName)
        val uri = if (isBirthday) {
            settingsRepository.birthdaySoundUri.first()
        } else {
            settingsRepository.alarmSoundUri.first()
        }
        return if (uri.isNullOrBlank()) ReminderSound.Builtin else ReminderSound.FromUri(uri)
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*ReminderSoundPolicyTest' --tests '*BirthdayCalendarTest'`
Expected: PASS — 6 + 7 tests, 0 failures.

- [ ] **Step 7: Verify the whole JVM suite still compiles**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — все существующие JVM-тесты зелены (новый контракт `SettingsRepository` могли реализовывать фейки в других тестах; если какой-то фейк не компилируется — добавить в него четыре заглушки по образцу Step 1, это часть задачи).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/mycalendar/domain/repository/SettingsRepository.kt \
        app/src/main/java/com/example/mycalendar/data/preferences/SettingsRepositoryImpl.kt \
        app/src/main/java/com/example/mycalendar/domain/reminder/ReminderSoundPolicy.kt \
        app/src/test/java/com/example/mycalendar/domain/reminder/ReminderSoundPolicyTest.kt
git commit -m "feat(reminder): настройки сигнала и тихого режима, политика разрешения"
```

---

### Task 3: Имя коллекции из источников в хранилище

**Files:**
- Modify: `app/src/main/java/com/example/mycalendar/data/remote/RemoteCalendarDataSource.kt:34-43`
- Modify: `app/src/main/java/com/example/mycalendar/data/system/SystemCalendarDataSource.kt:139-145`
- Modify: `app/src/main/java/com/example/mycalendar/data/remote/yandex/YandexCalendarDataSource.kt:44-99`
- Modify: `app/src/main/java/com/example/mycalendar/data/repository/SyncRepositoryImpl.kt:210-212`
- Test: `app/src/test/java/com/example/mycalendar/data/remote/yandex/YandexCalendarDataSourceTest.kt`

**Interfaces:**
- Consumes: `SettingsRepository.setCalendarName` (Task 2); `CalDavClient.Calendar.displayName` (уже есть, [CalDavClient.kt:35](../../../app/src/main/java/com/example/mycalendar/data/remote/yandex/CalDavClient.kt#L35)).
- Produces: `RemoteFetch.calendarName: String? = null`; заполняется в обоих источниках; пишется в `SettingsRepository` движком синхронизации рядом с `setCollectionUrl`.

- [ ] **Step 1: Write the failing test**

In `app/src/test/java/com/example/mycalendar/data/remote/yandex/YandexCalendarDataSourceTest.kt` add a new test method inside the class (после `fetchUpdatedSince runs discovery and returns parsed remote events`, строка 104). Mock `calendarsXml` отдаёт primary с `<d:displayname>Календарь</d:displayname>`, и `discoverAndPickCalendar` выбирает именно primary:

```kotlin
    @Test
    fun `fetchUpdatedSince carries the calendar display name`() {
        val captured = mutableListOf<MiniCalDavServer.Request>()
        yandexMockServer(captured).use { server ->
            val ds = YandexCalendarDataSource(
                caldav = CalDavClient(OkHttpClient(), baseUrl = server.baseUrl),
                auth = FakeTokenProvider(token),
                authRepository = FakeAuthRepository(UserAccount(CalendarProvider.YANDEX, email)),
            )

            val fetch = runBlocking { ds.fetchUpdatedSince(0) }

            // Имя коллекции нужно политике сигнала для распознавания календаря дней рождения.
            assertEquals("Календарь", fetch.calendarName)
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*YandexCalendarDataSourceTest'`
Expected: FAIL — `calendarName` unresolved on `RemoteFetch`.

- [ ] **Step 3: Add the field to RemoteFetch**

In `app/src/main/java/com/example/mycalendar/data/remote/RemoteCalendarDataSource.kt`, add after `collectionUrl` (строка 42, перед закрывающей `)` data class):

```kotlin
    /**
     * Имя коллекции (displayName). Политика сигнала распознаёт по нему календарь дней
     * рождения. null — источник не сообщил имя.
     */
    val calendarName: String? = null,
```

- [ ] **Step 4: Fill it from the Google (system) source**

In `app/src/main/java/com/example/mycalendar/data/system/SystemCalendarDataSource.kt`, replace the `RemoteFetch(...)` block in `fetchUpdatedSince` (строки 139-145). `calendar` уже получен на строке 134:

```kotlin
            RemoteFetch(
                events = events.map { event ->
                    val id = event.remoteId.toLongOrNull() ?: return@map event
                    val minutes = reminders[id] ?: return@map event
                    event.copy(reminderMinutes = minutes)
                },
                calendarName = calendar.displayName,
            )
```

- [ ] **Step 5: Cache the whole Calendar in the Yandex source**

In `app/src/main/java/com/example/mycalendar/data/remote/yandex/YandexCalendarDataSource.kt`:

(a) Replace the cached-url field (строки 44-46):

```kotlin
    /** Кэш выбранного календаря в рамках процесса (discovery — дорогая операция). */
    @Volatile
    private var cachedCalendar: CalDavClient.Calendar? = null
```

(b) Replace `calendarUrl(...)` (строки 57-62) with a calendar-object accessor:

```kotlin
    private suspend fun calendar(user: String, token: String): CalDavClient.Calendar {
        cachedCalendar?.let { return it }
        val chosen = caldav.discoverAndPickCalendar(user, token)
        cachedCalendar = chosen
        return chosen
    }
```

(c) Replace `fetchUpdatedSince` (строки 64-79):

```kotlin
    override suspend fun fetchUpdatedSince(sinceMillis: Long): RemoteFetch =
        withContext(Dispatchers.IO) {
            val (user, token) = credentials()
            val chosen = calendar(user, token)
            val calendars = caldav.fetchEvents(chosen.url, user, token)
            val parsed = calendars.mapNotNull { IcsMapper.fromIcs(it) }
            RemoteFetch(
                events = parsed,
                presentRemoteIds = if (parsed.size == calendars.size) {
                    parsed.mapTo(mutableSetOf()) { it.remoteId }
                } else {
                    null
                },
                collectionUrl = chosen.url,
                calendarName = chosen.displayName,
            )
        }
```

(d) Replace the three `val url = calendarUrl(user, token)` calls in `insert` (:83), `update` (:91), `delete` (:97) with `val url = calendar(user, token).url`.

- [ ] **Step 6: Persist the name in the sync engine**

In `app/src/main/java/com/example/mycalendar/data/repository/SyncRepositoryImpl.kt`, replace the block at строки 210-212:

```kotlin
        if (sameCollection || pinnedUrl == null) {
            fetch.collectionUrl?.let { settingsRepository.setCollectionUrl(provider, it) }
            fetch.calendarName?.let { settingsRepository.setCalendarName(provider, it) }
        }
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*YandexCalendarDataSourceTest'`
Expected: PASS — все тесты класса, включая новый `carries the calendar display name`; существующий `collectionUrl`-ассерт не сломан (url тот же).

- [ ] **Step 8: Run the whole JVM suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — 0 failures.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/example/mycalendar/data/remote/RemoteCalendarDataSource.kt \
        app/src/main/java/com/example/mycalendar/data/system/SystemCalendarDataSource.kt \
        app/src/main/java/com/example/mycalendar/data/remote/yandex/YandexCalendarDataSource.kt \
        app/src/main/java/com/example/mycalendar/data/repository/SyncRepositoryImpl.kt \
        app/src/test/java/com/example/mycalendar/data/remote/yandex/YandexCalendarDataSourceTest.kt
git commit -m "feat(reminder): имя коллекции из источников в настройки для признака дня рождения"
```

---

### Task 4: Оверлей — фабрика интента, разбор сигнала, вибрация, «Отложить»

**Files:**
- Create: `app/src/main/java/com/example/mycalendar/notifications/VibrationLoop.kt`
- Modify: `app/src/main/java/com/example/mycalendar/notifications/ReminderActivity.kt`
- Modify: `app/src/main/res/values/strings.xml` (строка кнопки «Отложить»)
- Test: `app/src/androidTest/java/com/example/mycalendar/ReminderActivityIntentTest.kt`
- Test: `app/src/androidTest/java/com/example/mycalendar/OverlayQuietModeTest.kt`
- Test: `app/src/androidTest/java/com/example/mycalendar/OverlaySnoozeTest.kt`

**Interfaces:**
- Consumes: `ReminderSound` (Task 1); `SnoozeReceiver.createIntent(context, eventId)` ([SnoozeReceiver.kt:97](../../../app/src/main/java/com/example/mycalendar/notifications/SnoozeReceiver.kt#L97)); `NotificationHelper.EXTRA_EVENT_ID`.
- Produces:
  - `ReminderActivity.createIntent(context: Context, event: Event, sound: ReminderSound, timeText: String): Intent` — единственный источник интента оверлея для Task 5.
  - `ReminderActivity.EXTRA_QUIET: Boolean` (`"extra_reminder_quiet"`), `EXTRA_SOUND_URI: String?` (`"extra_reminder_sound_uri"`).
  - `internal fun soundPlayingForTest(): Boolean`.
  - `class VibrationLoop(context: Context) { fun start(); fun stop() }`.

- [ ] **Step 1: Write the failing intent test**

Create `app/src/androidTest/java/com/example/mycalendar/ReminderActivityIntentTest.kt`:

```kotlin
package com.example.mycalendar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.ReminderSound
import com.example.mycalendar.notifications.NotificationHelper
import com.example.mycalendar.notifications.ReminderActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Фабрика интента оверлея кладёт вид и URI сигнала в примитивные экстры. Оба пути
 * (AlarmReceiver и NotificationHelper) зовут одну фабрику — расхождение экстр невозможно.
 */
@RunWith(AndroidJUnit4::class)
class ReminderActivityIntentTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val event = Event(id = "intent-1", title = "Встреча", startTime = 1L, endTime = 2L)

    @Test
    fun silent_setsQuietAndNoUri() {
        val intent = ReminderActivity.createIntent(context, event, ReminderSound.Silent, "09:00")
        assertTrue(intent.getBooleanExtra(ReminderActivity.EXTRA_QUIET, false))
        assertNull(intent.getStringExtra(ReminderActivity.EXTRA_SOUND_URI))
        assertEquals("intent-1", intent.getStringExtra(NotificationHelper.EXTRA_EVENT_ID))
        assertEquals("Встреча", intent.getStringExtra(ReminderActivity.EXTRA_TITLE))
        assertEquals("09:00", intent.getStringExtra(ReminderActivity.EXTRA_TIME_TEXT))
    }

    @Test
    fun fromUri_setsUriAndNotQuiet() {
        val intent = ReminderActivity.createIntent(context, event, ReminderSound.FromUri("content://x/1"), "09:00")
        assertFalse(intent.getBooleanExtra(ReminderActivity.EXTRA_QUIET, true))
        assertEquals("content://x/1", intent.getStringExtra(ReminderActivity.EXTRA_SOUND_URI))
    }

    @Test
    fun builtin_setsNeitherQuietNorUri() {
        val intent = ReminderActivity.createIntent(context, event, ReminderSound.Builtin, "09:00")
        assertFalse(intent.getBooleanExtra(ReminderActivity.EXTRA_QUIET, true))
        assertNull(intent.getStringExtra(ReminderActivity.EXTRA_SOUND_URI))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (эмулятор запущен): `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.ReminderActivityIntentTest`
Expected: FAIL — `createIntent`/`EXTRA_QUIET`/`EXTRA_SOUND_URI` unresolved.

- [ ] **Step 3: Add the overlay snooze string**

In `app/src/main/res/values/strings.xml`, add in the «Уведомления» block (после `notification_snoozed`, строка 166):

```xml
    <string name="overlay_snooze">Отложить</string>
```

- [ ] **Step 4: Implement VibrationLoop**

Create `app/src/main/java/com/example/mycalendar/notifications/VibrationLoop.kt`:

```kotlin
package com.example.mycalendar.notifications

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Зацикленный пульс вибрации тихого режима: 600 мс вибрации, 1200 мс паузы (шаг 1800 мс).
 * На устройствах без вибрации (hasVibrator() == false) молча ничего не делает — оверлей
 * не должен падать на headless-эмуляторе. Остановка обязательна в onDestroy и finishIfRunning,
 * иначе пульс продолжится после «Отложить».
 */
class VibrationLoop(context: Context) {

    private val vibrator: Vibrator? = run {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (v.hasVibrator()) v else null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val pulse = object : Runnable {
        override fun run() {
            vibrator?.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
            handler.postDelayed(this, 1800)
        }
    }

    fun start() {
        if (running || vibrator == null) return
        running = true
        handler.post(pulse)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pulse)
        vibrator?.cancel()
    }
}
```

- [ ] **Step 5: Rework ReminderActivity**

In `app/src/main/java/com/example/mycalendar/notifications/ReminderActivity.kt`:

(a) Add imports (после строки 5 `import android.os.Bundle`):

```kotlin
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.ReminderSound
```

(b) Add the vibration field next to `mediaPlayer` (строка 46):

```kotlin
    private var mediaPlayer: MediaPlayer? = null
    private var vibrationLoop: VibrationLoop? = null
```

(c) In `onCreate`, capture the snooze handler. Replace the `setContent { ... }` block (строки 73-93) — `eventId` уже прочитан на строке 69:

```kotlin
        setContent {
            MyCalendarTheme {
                AlarmOverlay(
                    title = title,
                    timeText = timeText,
                    onOpen = {
                        stopLoopingSound()
                        startActivity(com.example.mycalendar.MainActivity.createIntent(this, eventId))
                        finish()
                    },
                    onSnooze = {
                        stopLoopingSound()
                        if (eventId != null) {
                            sendBroadcast(SnoozeReceiver.createIntent(this, eventId))
                        }
                        finish()
                    },
                    onDismiss = {
                        stopLoopingSound()
                        finish()
                    },
                )
            }
        }
```

(d) Replace `startLoopingSound()` (строки 96-119):

```kotlin
    /** Зацикленный сигнал: тихий режим → вибрация; URI → MediaPlayer с откатом на встроенный. */
    private fun startLoopingSound() {
        stopLoopingSound()
        if (intent.getBooleanExtra(EXTRA_QUIET, false)) {
            vibrationLoop = VibrationLoop(this).also { it.start() }
            return
        }
        val uri = intent.getStringExtra(EXTRA_SOUND_URI)
        Log.d(TAG, "startLoopingSound: uri=$uri")
        try {
            val mp = if (uri != null) {
                MediaPlayer.create(this, Uri.parse(uri)) ?: MediaPlayer.create(this, R.raw.reminder_sound)
            } else {
                MediaPlayer.create(this, R.raw.reminder_sound)
            }
            if (mp == null) {
                Log.e(TAG, "  MediaPlayer.create returned null!")
                return
            }
            mp.isLooping = true
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.start()
            mediaPlayer = mp
            Log.d(TAG, "  MediaPlayer started, duration=${mp.duration} ms")
        } catch (e: Exception) {
            Log.e(TAG, "  MediaPlayer creation failed", e)
        }
    }
```

(e) Replace `stopLoopingSound()` (строки 121-127) — гасит и звук, и пульс:

```kotlin
    private fun stopLoopingSound() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        vibrationLoop?.stop()
        vibrationLoop = null
    }

    /** Для connected-теста тихого режима: MediaPlayer не поднят. */
    internal fun soundPlayingForTest(): Boolean =
        runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)
```

(f) In `companion object`, add the two extras after `EXTRA_TIME_TEXT` (строка 138) and the factory after `finishIfRunning()` (перед закрывающей `}` companion, строка 157):

```kotlin
        const val EXTRA_QUIET = "extra_reminder_quiet"
        const val EXTRA_SOUND_URI = "extra_reminder_sound_uri"
```

```kotlin
        /**
         * Единственный источник интента оверлея: зовут и AlarmReceiver (прямой запуск),
         * и NotificationHelper (full-screen intent). sealed-объект в интент не кладём —
         * только примитивы: Quiet == true → Silent, иначе URI != null → FromUri, иначе Builtin.
         */
        fun createIntent(context: Context, event: Event, sound: ReminderSound, timeText: String): Intent =
            Intent(context, ReminderActivity::class.java).apply {
                putExtra(NotificationHelper.EXTRA_EVENT_ID, event.id)
                putExtra(EXTRA_TITLE, event.title)
                putExtra(EXTRA_TIME_TEXT, timeText)
                putExtra(EXTRA_QUIET, sound == ReminderSound.Silent)
                putExtra(EXTRA_SOUND_URI, (sound as? ReminderSound.FromUri)?.uri)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
```

(g) Replace the `AlarmOverlay` composable signature and button block. Signature (строки 161-167):

```kotlin
@Composable
private fun AlarmOverlay(
    title: String,
    timeText: String,
    onOpen: () -> Unit,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
) {
```

Between the «Открыть событие» `Button` (строки 207-209) and the «Закрыть» `TextButton` (строки 210-217), insert the snooze button:

```kotlin
                    TextButton(
                        onClick = onSnooze,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        Text(stringResource(R.string.overlay_snooze))
                    }
```

- [ ] **Step 6: Run the intent test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.ReminderActivityIntentTest`
Expected: PASS — 3 tests, 0 failures.

- [ ] **Step 7: Write the quiet-overlay test**

Create `app/src/androidTest/java/com/example/mycalendar/OverlayQuietModeTest.kt`:

```kotlin
package com.example.mycalendar

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.ReminderSound
import com.example.mycalendar.notifications.ReminderActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Тихий оверлей не поднимает MediaPlayer и не падает там, где hasVibrator() == false
 * (headless-эмулятор). Запуск с кастомным интентом — через ActivityScenario, без compose-rule.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OverlayQuietModeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() {
        hiltRule.inject()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            ui.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            ui.executeShellCommand("svc power stayon true")
            ui.executeShellCommand("wm dismiss-keyguard")
        }
    }

    @Test
    fun quietOverlay_doesNotCreateMediaPlayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val event = Event(id = "quiet-1", title = "Тихое напоминание", startTime = 1L, endTime = 2L)
        val intent = ReminderActivity.createIntent(context, event, ReminderSound.Silent, "09:00")
        ActivityScenario.launch<ReminderActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("В тихом режиме MediaPlayer не создаётся", activity.soundPlayingForTest())
            }
        }
    }
}
```

- [ ] **Step 8: Write the snooze-overlay test**

Create `app/src/androidTest/java/com/example/mycalendar/OverlaySnoozeTest.kt`:

```kotlin
package com.example.mycalendar

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.ReminderSound
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.notifications.NotificationHelper
import com.example.mycalendar.notifications.ReminderActivity
import com.example.mycalendar.work.ReminderScheduler
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Тап «Отложить» в оверлее рассылает SnoozeReceiver.ACTION_SNOO с верным EXTRA_EVENT_ID
 * и закрывает активность. Доказательство верного eventId: SnoozeReceiver снимает уведомление
 * с id == eventId.hashCode() — заранее публикуем такое уведомление и проверяем, что оно исчезло.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OverlaySnoozeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var eventRepository: EventRepository
    @Inject lateinit var reminderScheduler: ReminderScheduler
    @Inject lateinit var notificationHelper: NotificationHelper

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var event: Event

    @Before
    fun setUp() {
        hiltRule.inject()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            ui.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            ui.executeShellCommand("svc power stayon true")
            ui.executeShellCommand("wm dismiss-keyguard")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                ui.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            }
        }
        notificationHelper.createChannels()
        event = Event(
            id = "overlay-snooze-${System.nanoTime()}",
            title = "Отложить из оверлея",
            startTime = System.currentTimeMillis() + 3_600_000L,
            endTime = System.currentTimeMillis() + 7_200_000L,
            reminderMinutes = listOf(10),
        )
        runBlocking { eventRepository.saveFromUser(event) }
        // Простое уведомление без full-screen intent, чтобы не было второго запуска оверлея.
        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(event.title)
            .build()
        NotificationManagerCompat.from(context).notify(event.id.hashCode(), notification)
    }

    @After
    fun tearDown() {
        runCatching { reminderScheduler.cancel(event) }
        runCatching { manager.cancel(event.id.hashCode()) }
        runCatching { runBlocking { eventRepository.hardDelete(event.id) } }
    }

    @Test
    fun snoozeButton_broadcastsAndClosesOverlay() {
        val intent = ReminderActivity.createIntent(context, event, ReminderSound.Builtin, "09:00")
        ActivityScenario.launch<ReminderActivity>(intent).use { scenario ->
            composeRule.waitForIdle()
            composeRule.onNodeWithText(context.getString(R.string.overlay_snooze)).performClick()
            // SnoozeReceiver асинхронен (goAsync + корутина): ждём снятия уведомления и DESTROYED.
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val cancelled = manager.activeNotifications.none { it.id == event.id.hashCode() }
                if (cancelled && scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED) break
                Thread.sleep(100)
            }
            assertTrue(
                "После «Отложить» уведомление с id eventId.hashCode() должно быть снято",
                manager.activeNotifications.none { it.id == event.id.hashCode() },
            )
            assertTrue(
                "Оверлей должен закрыться",
                scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED,
            )
        }
    }
}
```

- [ ] **Step 9: Run the overlay tests**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.OverlayQuietModeTest,com.example.mycalendar.OverlaySnoozeTest`
Expected: PASS — 2 tests, 0 failures. Если `createEmptyComposeRule` не резолвится в v2 — проверить импорт по байткоду артефакта (он подтверждён в v2 на 1.12.1); НЕ менять пакет наугад.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/example/mycalendar/notifications/VibrationLoop.kt \
        app/src/main/java/com/example/mycalendar/notifications/ReminderActivity.kt \
        app/src/main/res/values/strings.xml \
        app/src/androidTest/java/com/example/mycalendar/ReminderActivityIntentTest.kt \
        app/src/androidTest/java/com/example/mycalendar/OverlayQuietModeTest.kt \
        app/src/androidTest/java/com/example/mycalendar/OverlaySnoozeTest.kt
git commit -m "feat(reminder): оверлей — выбор сигнала, вибрация тихого режима, кнопка Отложить"
```

---

### Task 5: Wiring — AlarmReceiver и NotificationHelper

**Files:**
- Modify: `app/src/main/java/com/example/mycalendar/notifications/NotificationHelper.kt`
- Modify: `app/src/main/java/com/example/mycalendar/notifications/AlarmReceiver.kt`
- Modify: `app/src/main/res/values/strings.xml` (названия тихого канала)
- Test: `app/src/androidTest/java/com/example/mycalendar/QuietModeChannelTest.kt`

**Interfaces:**
- Consumes: `ReminderSoundPolicy.resolve` (Task 2); `SettingsRepository.calendarName(provider)` (Task 2); `ReminderActivity.createIntent` (Task 4).
- Produces: `NotificationHelper.CHANNEL_REMINDERS_QUIET = "channel_event_reminders_quiet_v2"`; `showEventReminder(event, reminderMinutes, occurrenceStart, occurrenceEnd, sound: ReminderSound = ReminderSound.Builtin)`.

- [ ] **Step 1: Write the failing channel test**

Create `app/src/androidTest/java/com/example/mycalendar/QuietModeChannelTest.kt`:

```kotlin
package com.example.mycalendar

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.notifications.NotificationHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Тихий канал существует с IMPORTANCE_HIGH, без звука/вибрации/lights; существующий v2
 * сохраняет IMPORTANCE_HIGH — регрессия на «не трогать существующий канал».
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class QuietModeChannelTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var notificationHelper: NotificationHelper

    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        hiltRule.inject()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationHelper.createChannels()
    }

    @Test
    fun quietChannel_existsWithHighImportance() {
        val channel = manager.getNotificationChannel(NotificationHelper.CHANNEL_REMINDERS_QUIET)
        assertNotNull("Тихий канал должен быть создан", channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel!!.importance)
    }

    @Test
    fun quietChannel_hasNoSoundVibrationLights() {
        val channel = manager.getNotificationChannel(NotificationHelper.CHANNEL_REMINDERS_QUIET)!!
        assertNull("У тихого канала нет звука", channel.sound)
        assertFalse("Тихий канал не вибрирует", channel.shouldVibrate())
        assertFalse("Тихий канал без lights", channel.shouldShowLights())
    }

    @Test
    fun remindersChannel_keepsHighImportance() {
        val channel = manager.getNotificationChannel(NotificationHelper.CHANNEL_REMINDERS)
        assertNotNull(channel)
        assertEquals("Существующий v2 канал не тронут", NotificationManager.IMPORTANCE_HIGH, channel!!.importance)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.QuietModeChannelTest`
Expected: FAIL — `CHANNEL_REMINDERS_QUIET` unresolved.

- [ ] **Step 3: Add the quiet-channel strings**

In `app/src/main/res/values/strings.xml`, add after `notification_channel_reminders_desc` (строка 161):

```xml
    <string name="notification_channel_reminders_quiet">Тихие напоминания</string>
    <string name="notification_channel_reminders_quiet_desc">Служебный канал тихого режима: напоминание приходит без звука</string>
```

- [ ] **Step 4: Create the quiet channel and select it by sound**

In `app/src/main/java/com/example/mycalendar/notifications/NotificationHelper.kt`:

(a) Add imports (после строки 19 `import com.example.mycalendar.domain.model.Event`):

```kotlin
import com.example.mycalendar.domain.model.ReminderSound
```

(b) Add the constant in `companion object` after `CHANNEL_REMINDERS` (строка 45):

```kotlin
        const val CHANNEL_REMINDERS_QUIET = "channel_event_reminders_quiet_v2"
        private const val CHANNEL_REMINDERS_QUIET_LEGACY = "channel_event_reminders_quiet"
```

(c) In `createChannels()`, after `manager.createNotificationChannel(remindersChannel)` (строка 76), add:

```kotlin
        // Тихий режим: канал IMPORTANCE_HIGH, но без звука/вибрации/lights.
        // HIGH нужен для доставки full-screen intent: на API 29+ прямой startActivity
        // из receiver запрещён (точные будильники не входят в исключения BAL).
        // Импортанс неизменяем, а пересоздание канала с тем же id восстанавливает
        // прежние настройки — поэтому новый id, а прежний канал удаляем.
        manager.deleteNotificationChannel(CHANNEL_REMINDERS_QUIET_LEGACY)
        val quietChannel = NotificationChannel(
            CHANNEL_REMINDERS_QUIET,
            context.getString(R.string.notification_channel_reminders_quiet),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_reminders_quiet_desc)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setBypassDnd(false)
        }
        manager.createNotificationChannel(quietChannel)
```

(d) Add the `sound` parameter to `showEventReminder` (строки 104-109):

```kotlin
    fun showEventReminder(
        event: Event,
        reminderMinutes: Int,
        occurrenceStart: Long,
        occurrenceEnd: Long,
        sound: ReminderSound = ReminderSound.Builtin,
    ) {
```

(e) Replace the full-screen intent block (строки 125-131) — теперь общая фабрика:

```kotlin
        // Полноэкранный запуск аларм-экрана: та же фабрика, что у AlarmReceiver.
        val fullScreenIntent = ReminderActivity.createIntent(context, event, sound, label)
```

(f) Replace the builder channel/priority (строки 164-168). Выбери канал по сигналу:

```kotlin
        val channel = if (sound == ReminderSound.Silent) CHANNEL_REMINDERS_QUIET else CHANNEL_REMINDERS
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(event.title)
            .setContentText(label)
            .setPriority(if (sound == ReminderSound.Silent) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_MAX)
```

(Остальные методы билдера — `.setCategory` … `.build()` — остаются без изменений.)

- [ ] **Step 5: Resolve the sound in AlarmReceiver**

In `app/src/main/java/com/example/mycalendar/notifications/AlarmReceiver.kt`:

(a) Add imports (после строки 8 `import com.example.mycalendar.domain.repository.EventRepository`):

```kotlin
import com.example.mycalendar.domain.reminder.ReminderSoundPolicy
import com.example.mycalendar.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
```

(b) Add injected fields after `reminderScheduler` (строка 32):

```kotlin
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var reminderSoundPolicy: ReminderSoundPolicy
```

(c) After `val label = notificationHelper.buildReminderLabel(startTime, minutes)` (строка 65), resolve the sound:

```kotlin
                val calendarName = settingsRepository.calendarName(event.calendarProvider).first()
                val sound = reminderSoundPolicy.resolve(event, calendarName)
```

(d) Pass `sound` to `showEventReminder` (строки 68-73):

```kotlin
                notificationHelper.showEventReminder(
                    event = event,
                    reminderMinutes = minutes,
                    occurrenceStart = startTime,
                    occurrenceEnd = endTime,
                    sound = sound,
                )
```

(e) Replace the direct-launch intent block (строки 87-96) with the shared factory:

```kotlin
                    val alarmIntent = ReminderActivity.createIntent(context, event, sound, label)
```

- [ ] **Step 6: Run the channel test**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.QuietModeChannelTest`
Expected: PASS — 3 tests, 0 failures.

- [ ] **Step 7: Run the existing notification-action regression**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.NotificationActionsTest`
Expected: PASS — 2 tests (default `sound = Builtin` сохранил старые вызовы).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/mycalendar/notifications/NotificationHelper.kt \
        app/src/main/java/com/example/mycalendar/notifications/AlarmReceiver.kt \
        app/src/main/res/values/strings.xml \
        app/src/androidTest/java/com/example/mycalendar/QuietModeChannelTest.kt
git commit -m "feat(reminder): тихий канал и разрешение сигнала в AlarmReceiver/NotificationHelper"
```

---

### Task 6: Карточка «Напоминания» в настройках

**Files:**
- Modify: `app/src/main/java/com/example/mycalendar/presentation/settings/SettingsViewModel.kt:70-182`
- Modify: `app/src/main/java/com/example/mycalendar/presentation/settings/SettingsScreen.kt`
- Create: `app/src/main/java/com/example/mycalendar/presentation/components/ReminderTags.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml:28`
- Test: `app/src/androidTest/java/com/example/mycalendar/SettingsRemindersCardTest.kt`

**Interfaces:**
- Consumes: `SettingsRepository.quietMode/alarmSoundUri/birthdaySoundUri` + сеттеры (Task 2).
- Produces: `SettingsUiState.quietMode: Boolean`, `.alarmSoundUri: String?`, `.birthdaySoundUri: String?`; `SettingsViewModel.setQuietMode(Boolean)`, `.setAlarmSoundUri(String?)`, `.setBirthdaySoundUri(String?)`; testTag-константы `QUIET_MODE_SWITCH_TAG`, `ALARM_SOUND_ROW_TAG`, `BIRTHDAY_SOUND_ROW_TAG`.

- [ ] **Step 1: Add the test tags**

Create `app/src/main/java/com/example/mycalendar/presentation/components/ReminderTags.kt`:

```kotlin
package com.example.mycalendar.presentation.components

/** Теги контролов карточки «Напоминания» и индикатора тихого режима (для connected-тестов). */
const val QUIET_MODE_SWITCH_TAG = "quiet_mode_switch"
const val ALARM_SOUND_ROW_TAG = "alarm_sound_row"
const val BIRTHDAY_SOUND_ROW_TAG = "birthday_sound_row"
const val QUIET_INDICATOR_TAG = "quiet_mode_indicator"
```

- [ ] **Step 2: Add the card strings**

In `app/src/main/res/values/strings.xml`, add a new block after the «Обновления» block (после строки 147, перед «Настройка OAuth»):

```xml
    <!-- Карточка «Напоминания»: сигнал и тихий режим -->
    <string name="reminders_section">Напоминания</string>
    <string name="quiet_mode">Тихий режим</string>
    <string name="quiet_mode_desc">Напоминание приходит молча: окно и вибрация, без сигнала</string>
    <string name="alarm_sound">Сигнал напоминания</string>
    <string name="birthday_sound">Сигнал дня рождения</string>
    <string name="sound_default">Стандартный</string>
    <string name="sound_unavailable">Сигнал недоступен, используется стандартный</string>
    <string name="sound_preview_play">Прослушать</string>
    <string name="sound_preview_stop">Остановить</string>
    <string name="quiet_mode_indicator">Тихий режим включён, нажмите чтобы выключить</string>
```

- [ ] **Step 3: Add the storage permission**

In `app/src/main/AndroidManifest.xml`, add after `VIBRATE` (строка 28):

```xml
    <!-- Доступ к файлам рингтонов на Android 8–9; на API 29+ не требуется -->
    <uses-permission
        android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
```

- [ ] **Step 4: Extend SettingsUiState and the ViewModel**

In `app/src/main/java/com/example/mycalendar/presentation/settings/SettingsViewModel.kt`:

(a) Add fields to `SettingsUiState` (после `conflictCount`, строка 75):

```kotlin
    /** Глобальный тихий режим: оверлей молчит и вибрирует. */
    val quietMode: Boolean = false,
    /** URI сигнала обычного напоминания (null — встроенный). */
    val alarmSoundUri: String? = null,
    /** URI сигнала дня рождения (null — встроенный). */
    val birthdaySoundUri: String? = null,
```

(b) Add a nested combine holder before `val uiState` (перед строкой 162). Внешний `combine` уже несёт 4 потока; пятый — вложенный combine трёх потоков напоминаний (typed-перегрузка combine допускает максимум 5 аргументов):

```kotlin
    /** Три потока напоминаний одним снимком — пятый аргумент внешнего combine. */
    private data class ReminderSettings(
        val quietMode: Boolean,
        val alarmSoundUri: String?,
        val birthdaySoundUri: String?,
    )
```

(c) Replace the `uiState` combine (строки 162-182):

```kotlin
    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.themeMode,
        authRepository.connections,
        lastSyncTimes,
        combine(connecting, syncing, results, conflictCount, ::SyncFlags),
        combine(
            settingsRepository.quietMode,
            settingsRepository.alarmSoundUri,
            settingsRepository.birthdaySoundUri,
            ::ReminderSettings,
        ),
    ) { themeMode, connections, syncTimes, flags, reminders ->
        SettingsUiState(
            themeMode = themeMode,
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
            quietMode = reminders.quietMode,
            alarmSoundUri = reminders.alarmSoundUri,
            birthdaySoundUri = reminders.birthdaySoundUri,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())
```

(d) Add setters after `setThemeMode` (после строки 188):

```kotlin
    // ---------- Напоминания ----------

    fun setQuietMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setQuietMode(enabled) }
    }

    fun setAlarmSoundUri(uri: String?) {
        viewModelScope.launch { settingsRepository.setAlarmSoundUri(uri) }
    }

    fun setBirthdaySoundUri(uri: String?) {
        viewModelScope.launch { settingsRepository.setBirthdaySoundUri(uri) }
    }
```

- [ ] **Step 5: Add imports and the card to SettingsScreen**

In `app/src/main/java/com/example/mycalendar/presentation/settings/SettingsScreen.kt`:

(a) Add imports (в алфавитный порядок существующих импортов):

```kotlin
import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Switch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import com.example.mycalendar.presentation.components.ALARM_SOUND_ROW_TAG
import com.example.mycalendar.presentation.components.BIRTHDAY_SOUND_ROW_TAG
import com.example.mycalendar.presentation.components.QUIET_MODE_SWITCH_TAG
```

(`getValue`/`mutableStateOf`/`remember`/`LocalContext`/`ContextCompat`/`PackageManager`/`Manifest` уже импортированы.)

(b) Insert the card between the «Обновления» divider (строка 240 `HorizontalDivider()`) and the «Разрешения» comment (строка 242). Replace the single `HorizontalDivider()` at строка 240 with:

```kotlin
            HorizontalDivider()

            // ---------- Напоминания ----------
            SectionHeader(stringResource(R.string.reminders_section))
            RemindersCard(
                quietMode = state.quietMode,
                alarmSoundUri = state.alarmSoundUri,
                birthdaySoundUri = state.birthdaySoundUri,
                onQuietChange = viewModel::setQuietMode,
                onAlarmPick = viewModel::setAlarmSoundUri,
                onBirthdayPick = viewModel::setBirthdaySoundUri,
            )

            HorizontalDivider()
```

(c) Add the `RemindersCard` composable and helpers at the end of the file (после `formatDateTime`, строка 615):

```kotlin
/** Какая строка открыла пикер — результат маршрутизируется в нужный сеттер. */
private enum class SoundTarget { ALARM, BIRTHDAY }

/**
 * Карточка «Напоминания»: тумблер тихого режима и две строки выбора сигнала с пикером
 * и предпрослушиванием. При активном тихом режиме строки затемнены (enabled = false),
 * выбор сохранён и возвращается после выключения тумблера.
 */
@Composable
private fun RemindersCard(
    quietMode: Boolean,
    alarmSoundUri: String?,
    birthdaySoundUri: String?,
    onQuietChange: (Boolean) -> Unit,
    onAlarmPick: (String?) -> Unit,
    onBirthdayPick: (String?) -> Unit,
) {
    val context = LocalContext.current
    var pickerTarget by remember { mutableStateOf(SoundTarget.ALARM) }
    var preview by remember { mutableStateOf<Ringtone?>(null) }

    DisposableEffect(Unit) {
        onDispose { preview?.stop() }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uri = if (Build.VERSION.SDK_INT >= 33) {
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            preview?.stop()
            preview = null
            if (pickerTarget == SoundTarget.ALARM) onAlarmPick(uri?.toString()) else onBirthdayPick(uri?.toString())
        }
    }

    val launchPicker: (SoundTarget) -> Unit = { target ->
        val existing = if (target == SoundTarget.ALARM) alarmSoundUri else birthdaySoundUri
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_TITLE,
                context.getString(if (target == SoundTarget.ALARM) R.string.alarm_sound else R.string.birthday_sound),
            )
            if (existing != null) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(existing))
        }
        pickerLauncher.launch(intent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Отказ не блокирует: работает откат на встроенный сигнал.
        launchPicker(pickerTarget)
    }

    val requestPick: (SoundTarget) -> Unit = { target ->
        pickerTarget = target
        val needsPermission = Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            launchPicker(target)
        }
    }

    val togglePreview: (String?) -> Unit = { uri ->
        val current = preview
        if (current != null && current.isPlaying) {
            current.stop()
            preview = null
        } else if (uri != null) {
            current?.stop()
            val ringtone = RingtoneManager.getRingtone(context, Uri.parse(uri))
            if (ringtone != null) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                ringtone.play()
                preview = ringtone
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AppSpacing.screen)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.quiet_mode), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.quiet_mode_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = quietMode,
                    onCheckedChange = onQuietChange,
                    modifier = Modifier.testTag(QUIET_MODE_SWITCH_TAG),
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.cardVPad))
            SoundRow(
                label = stringResource(R.string.alarm_sound),
                uri = alarmSoundUri,
                enabled = !quietMode,
                tag = ALARM_SOUND_ROW_TAG,
                previewing = preview?.isPlaying == true,
                onPick = { requestPick(SoundTarget.ALARM) },
                onPreview = { togglePreview(alarmSoundUri) },
            )
            Spacer(modifier = Modifier.height(AppSpacing.cardVPad))
            SoundRow(
                label = stringResource(R.string.birthday_sound),
                uri = birthdaySoundUri,
                enabled = !quietMode,
                tag = BIRTHDAY_SOUND_ROW_TAG,
                previewing = preview?.isPlaying == true,
                onPick = { requestPick(SoundTarget.BIRTHDAY) },
                onPreview = { togglePreview(birthdaySoundUri) },
            )
        }
    }
}

/** Строка выбора сигнала: имя файла (или «Стандартный»/«недоступен») и кнопка play/stop. */
@Composable
private fun SoundRow(
    label: String,
    uri: String?,
    enabled: Boolean,
    tag: String,
    previewing: Boolean,
    onPick: () -> Unit,
    onPreview: () -> Unit,
) {
    val context = LocalContext.current
    val ringtone = remember(uri) { uri?.let { RingtoneManager.getRingtone(context, Uri.parse(it)) } }
    val available = ringtone != null
    val valueText = when {
        uri == null -> stringResource(R.string.sound_default)
        !available -> stringResource(R.string.sound_unavailable)
        else -> ringtone?.getTitle(context) ?: stringResource(R.string.sound_default)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(enabled = enabled, onClick = onPick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                valueText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPreview, enabled = enabled && available) {
            Icon(
                if (previewing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (previewing) R.string.sound_preview_stop else R.string.sound_preview_play
                ),
            )
        }
    }
}
```

Add the missing `clickable` import (если ещё нет — в SettingsScreen.kt его нет): `import androidx.compose.foundation.clickable`.

- [ ] **Step 6: Write the failing card test**

Create `app/src/androidTest/java/com/example/mycalendar/SettingsRemindersCardTest.kt`:

```kotlin
package com.example.mycalendar

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import com.example.mycalendar.presentation.components.ALARM_SOUND_ROW_TAG
import com.example.mycalendar.presentation.components.BIRTHDAY_SOUND_ROW_TAG
import com.example.mycalendar.presentation.components.QUIET_MODE_SWITCH_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Карточка «Напоминания»: тумблер тихого режима делает обе строки сигнала неактивными;
 * переключение с сохранённым выбором не стирает URI.
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsRemindersCardTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var authRepository: AuthRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            ui.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            ui.executeShellCommand("svc power stayon true")
            ui.executeShellCommand("wm dismiss-keyguard")
        }
        runBlocking {
            settingsRepository.setOnboardingCompleted(false)
            settingsRepository.setQuietMode(false)
            settingsRepository.setAlarmSoundUri(null)
            authRepository.signOutAll()
        }
        Thread.sleep(1_000)
    }

    private fun reachSettings() {
        val res = composeRule.activity.resources
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasText(res.getString(R.string.enter_app), substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodes(
                    androidx.compose.ui.test.hasText(res.getString(R.string.view_month), substring = true),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
        }
        val enter = composeRule.onAllNodes(
            androidx.compose.ui.test.hasText(res.getString(R.string.enter_app), substring = true),
            useUnmergedTree = true,
        )
        if (enter.fetchSemanticsNodes().isNotEmpty()) enter[0].performClick()
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasText(res.getString(R.string.view_month), substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(res.getString(R.string.settings)).performClick()
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasText(res.getString(R.string.calendars_section), substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun quietMode_disablesBothSoundRows() {
        reachSettings()
        composeRule.onNodeWithTag(QUIET_MODE_SWITCH_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ALARM_SOUND_ROW_TAG).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(BIRTHDAY_SOUND_ROW_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun togglingQuietMode_preservesSavedUri() {
        runBlocking { settingsRepository.setAlarmSoundUri("content://media/test/1") }
        reachSettings()
        composeRule.onNodeWithTag(QUIET_MODE_SWITCH_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(QUIET_MODE_SWITCH_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(
            "Сохранённый URI не стёрся переключением тихого режима",
            "content://media/test/1",
            runBlocking { settingsRepository.alarmSoundUri.first() },
        )
    }
}
```

- [ ] **Step 7: Run the card test**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.SettingsRemindersCardTest`
Expected: PASS — 2 tests, 0 failures. Если `assertIsNotEnabled` не срабатывает — убедиться, что `testTag` стоит на том же узле, что и `clickable(enabled = false)` (порядок `.testTag(tag).clickable(...)` в SoundRow).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/mycalendar/presentation/settings/SettingsViewModel.kt \
        app/src/main/java/com/example/mycalendar/presentation/settings/SettingsScreen.kt \
        app/src/main/java/com/example/mycalendar/presentation/components/ReminderTags.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/AndroidManifest.xml \
        app/src/androidTest/java/com/example/mycalendar/SettingsRemindersCardTest.kt
git commit -m "feat(reminder): карточка Напоминания — тихий режим, пикер сигнала, предпрослушивание"
```

---

### Task 7: Индикатор тихого режима в шапке календаря

**Files:**
- Modify: `app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarViewModel.kt:95-119`
- Modify: `app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt:86-100`
- Test: `app/src/androidTest/java/com/example/mycalendar/QuietModeIndicatorTest.kt`

**Interfaces:**
- Consumes: `SettingsRepository.quietMode/setQuietMode` (Task 2); `QUIET_INDICATOR_TAG` (Task 6).
- Produces: `CalendarViewModel.quietMode: StateFlow<Boolean>`, `CalendarViewModel.setQuietMode(Boolean)`.

- [ ] **Step 1: Write the failing indicator test**

Create `app/src/androidTest/java/com/example/mycalendar/QuietModeIndicatorTest.kt`:

```kotlin
package com.example.mycalendar

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import com.example.mycalendar.presentation.components.QUIET_INDICATOR_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Индикатор NotificationsOff виден только при активном тихом режиме; тап выключает режим.
 * Включается режим только из настроек — здесь он задаётся напрямую через репозиторий.
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class QuietModeIndicatorTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var authRepository: AuthRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            ui.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            ui.executeShellCommand("svc power stayon true")
            ui.executeShellCommand("wm dismiss-keyguard")
        }
        runBlocking {
            settingsRepository.setOnboardingCompleted(false)
            settingsRepository.setQuietMode(true)
            authRepository.signOutAll()
        }
        Thread.sleep(1_000)
    }

    @Test
    fun indicator_visibleWhenQuiet_tapTurnsOff() {
        val res = composeRule.activity.resources
        // Добираемся до календаря (стартовый экран → «Войти»).
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasText(res.getString(R.string.enter_app), substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodes(
                    androidx.compose.ui.test.hasText(res.getString(R.string.view_month), substring = true),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
        }
        val enter = composeRule.onAllNodes(
            androidx.compose.ui.test.hasText(res.getString(R.string.enter_app), substring = true),
            useUnmergedTree = true,
        )
        if (enter.fetchSemanticsNodes().isNotEmpty()) enter[0].performClick()
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasText(res.getString(R.string.view_month), substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        // Индикатор на месте, тап выключает режим.
        composeRule.onNodeWithTag(QUIET_INDICATOR_TAG).performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { settingsRepository.quietMode.first() } == false
        }
        assertFalse(runBlocking { settingsRepository.quietMode.first() })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.QuietModeIndicatorTest`
Expected: FAIL — узел `QUIET_INDICATOR_TAG` не найден (индикатор не реализован).

- [ ] **Step 3: Expose quietMode in CalendarViewModel**

In `app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarViewModel.kt`:

(a) Add imports (после строки 9 `import com.example.mycalendar.domain.usecase.ObserveEventsBetweenUseCase`):

```kotlin
import com.example.mycalendar.domain.repository.SettingsRepository
```

(`StateFlow`, `SharingStarted`, `stateIn`, `launch`, `viewModelScope` уже импортированы.)

(b) Add the constructor parameter (строки 95-99):

```kotlin
class CalendarViewModel @Inject constructor(
    observeEventsBetween: ObserveEventsBetweenUseCase,
    private val expandOccurrences: ExpandOccurrencesUseCase,
    private val holidayRegistry: HolidayRegistry,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
```

(c) Add the flow and setter after `val occurrences` (после строки 140):

```kotlin
    /** Тихий режим для индикатора в шапке: показывается только при активном режиме. */
    val quietMode: StateFlow<Boolean> = settingsRepository.quietMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Тап по индикатору выключает режим; включается он только из настроек. */
    fun setQuietMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setQuietMode(enabled) }
    }
```

- [ ] **Step 4: Show the indicator in CalendarScreen**

In `app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt`:

(a) Add imports:

```kotlin
import androidx.compose.material.icons.filled.NotificationsOff
import com.example.mycalendar.presentation.components.QUIET_INDICATOR_TAG
```

(`testTag`, `collectAsState`, `getValue` уже импортированы.)

(b) Read the flow next to the other state (после строки 87):

```kotlin
    val quietMode by viewModel.quietMode.collectAsState()
```

(c) Insert the conditional indicator before the «Сегодня» button (перед строкой 94):

```kotlin
                    if (quietMode) {
                        IconButton(
                            onClick = { viewModel.setQuietMode(false) },
                            modifier = Modifier.testTag(QUIET_INDICATOR_TAG),
                        ) {
                            Icon(
                                Icons.Filled.NotificationsOff,
                                contentDescription = stringResource(R.string.quiet_mode_indicator),
                            )
                        }
                    }
```

- [ ] **Step 5: Run the indicator test**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.QuietModeIndicatorTest`
Expected: PASS — 1 test, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarViewModel.kt \
        app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt \
        app/src/androidTest/java/com/example/mycalendar/QuietModeIndicatorTest.kt
git commit -m "feat(reminder): индикатор тихого режима в шапке календаря, тап выключает"
```

---

### Task 8: Полные прогоны и счётчики в документах

**Files:**
- Modify: `README.md:173-187`
- Modify: `docs/how-to-verify.md:158-187`

**Interfaces:**
- Consumes: всё дерево после Tasks 1-7.
- Produces: фактические счётчики JVM/lint/connected в README и памятке; раздел «Что проверить руками» для тихого режима и выбора сигнала.

- [ ] **Step 1: Full JVM run**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 0 failures. Снять фактическое число тестов с отчёта `app/build/reports/tests/testDebugUnitTest/index.html` (или сумма внутренних `<testsuite>` в `app/build/test-results/testDebugUnitTest/*.xml`). Ожидаемый прирост относительно базы 117: +7 (BirthdayCalendar) +6 (ReminderSoundPolicy) +1 (Yandex calendarName) = 131, но ЗАПИСАТЬ измеренное, а не ожидаемое.

- [ ] **Step 2: Lint**

Run: `./gradlew :app:lintDebug`
Expected: 0 errors. Снять фактическое число предупреждений с `app/build/reports/lint-results-debug.html`. База — 11 предупреждений; новые контролы могут добавить (например, `NewerVersionAvailable`) — записать измеренное и перечислить состав.

- [ ] **Step 3: Full connected run**

Run (эмулятор запущен, НЕ `Api37` с живым входом в Яндекс): `./gradlew :app:connectedDebugAndroidTest`
Expected: PASS, 0 failures. Снять фактическое число с JUnit-XML `app/build/outputs/androidTest-results/connected/debug/` (сумма внутренних `<testsuite>`, сверка по `<testcase>`). Ожидаемый прирост относительно базы 62: +3 (Intent) +1 (QuietOverlay) +1 (Snooze) +3 (Channel) +2 (Card) +1 (Indicator) = 73, но ЗАПИСАТЬ измеренное.

- [ ] **Step 4: Update README counters**

In `README.md`, replace the JVM bullet (строки 173-179) and the instrumented bullet (строки 180-185) с фактическими числами из Steps 1-3, добавив упоминание новых тестов (`BirthdayCalendarTest`, `ReminderSoundPolicyTest`, `QuietModeChannelTest`, `OverlayQuietModeTest`, `OverlaySnoozeTest`, `SettingsRemindersCardTest`, `QuietModeIndicatorTest`, `ReminderActivityIntentTest`). Обновить дату замера. Формат сохранить: «**N JVM-тестов** … итог N и 0 падений сняты с отчёта … (замер ДД.ММ.ГГГГ)». Строку lint (186-187) обновить фактическим числом предупреждений.

- [ ] **Step 5: Update how-to-verify counters and manual section**

In `docs/how-to-verify.md`:
- Обновить строки 158-159, 172-178, 185-187 фактическими числами JVM/lint/connected и датой замера.
- Добавить в «Что проверить руками» подраздел «Сигнал напоминания и тихий режим»:
  - Настройки → «Напоминания» → строка «Сигнал напоминания» открывает системный пикер (будильники); выбрать сигнал, нажать play — предпрослушивание; то же для «Сигнал дня рождения».
  - Тумблер «Тихий режим» затемняет обе строки; выбор сохраняется и возвращается после выключения.
  - При тихом режиме в шапке календаря появляется `NotificationsOff`; тап выключает режим; включается только из настроек.
  - Напоминание в тихом режиме: оверлей молчит, вибрирует пульсом, кнопка «Отложить» работает; при погашенном экране оверлей всплывает (служебный канал — пропуск).
  - Событие с меткой «День рождения» или из коллекции «Дни рождения» звучит выбранным сигналом дня рождения.
- Добавить команды точечного прогона новых connected-классов по образцу существующих `am instrument` строк.

- [ ] **Step 6: Clean up temp artifacts**

После прогонов удалить свои временные файлы и профили из `C:\Users\obogr\AppData\Local\Temp` (только созданные этим прогоном; чужие процессы не завершать, эмулятор/сервер остановить заранее по PID).

- [ ] **Step 7: Commit (docs only, after code)**

```bash
git add README.md docs/how-to-verify.md
git commit -m "docs: счётчики после выбора сигнала и тихого режима — JVM <N>, connected <M>"
```

(Подставить фактические N и M из Steps 1-3.)

---

## Self-Review

**1. Spec coverage:**
- §3 модель → Task 1 (`ReminderSound`, `BirthdayCalendar`) + Task 2 (`ReminderSoundPolicy`). ✓
- §4 хранилище → Task 2 (ключи, сеттеры, `clearCollectionUrl` стирает имя) + Task 3 (`RemoteFetch.calendarName`, оба источника, `SyncRepositoryImpl`). ✓
- §5 разрешение и запуск → Task 4 (`createIntent`, экстры, `startLoopingSound` ветки, `VibrationLoop`) + Task 5 (`AlarmReceiver.resolve`, общая фабрика). ✓
- §6 тихий режим → Task 5 (канал `IMPORTANCE_HIGH` без звука, выбор канала, миграция прежнего id) + Task 4 (оверлей молчит/вибрирует/«Отложить») + Task 7 (индикатор). ✓
- §7 экран настроек → Task 6 (карточка, Switch, две строки, пикер, предпрослушивание, затемнение). ✓
- §8 разрешения → Task 6 (`READ_EXTERNAL_STORAGE maxSdkVersion=28`, запрос при первом выборе). ✓
- §9 деградация → Task 4 (откат URI→Builtin, `hasVibrator` guard) + Task 2 (null calendarName → только метка) + Task 6 (строка «недоступен»). ✓
- §10 тесты → все восемь классов распределены по Tasks 1-7; счётчики — Task 8. ✓
- §11 файлы → все перечислены в File Structure; `CalDavClient` исключён (поправка 1). ✓

**2. Placeholder scan:** плейсхолдеров нет; единственные угловые скобки — `<N>`/`<M>`/`ДД.ММ.ГГГГ` в Task 8 Step 7, это намеренные подстановки ФАКТИЧЕСКИХ замеров (требование Global Constraints), а не пропуск кода. Все листинги полные.

**3. Type consistency:**
- `ReminderSound` (`Silent`/`FromUri(uri)`/`Builtin`) — одинаков в Tasks 1/2/4/5. ✓
- `createIntent(context, event, sound, timeText)` — сигнатура Task 4 совпадает с вызовами Task 5 (`AlarmReceiver`, `NotificationHelper`). ✓
- `showEventReminder(..., sound = Builtin)` — default сохраняет `NotificationActionsTest`. ✓
- `SettingsRepository` новые члены — имена в Task 2 (контракт+импл) совпадают с потреблением в Tasks 3/5/6/7 (`calendarName`, `quietMode`, `alarmSoundUri`, `birthdaySoundUri`, сеттеры). ✓
- `RemoteFetch.calendarName` — Task 3 добавляет и заполняет; `SyncRepositoryImpl` читает `fetch.calendarName`. ✓
- testTag-константы — Task 6 создаёт `ReminderTags.kt`, Tasks 6/7 импортируют; тесты импортируют те же. ✓
- `SettingsUiState` новые поля — Task 6 ViewModel заполняет из `ReminderSettings`, Screen читает `state.quietMode/alarmSoundUri/birthdaySoundUri`. ✓
- `soundPlayingForTest()` — Task 4 определяет, `OverlayQuietModeTest` зовёт. ✓

**Известные риски для имплементера:**
- `combine` 5 аргументов — предел typed-перегрузки; Task 6 использует вложенный combine (как существующий `SyncFlags`). Если компилятор не примет 5-й аргумент — вынести reminder-потоки в отдельный `combine` и передавать его результат (уже сделано в листинге).
- `createEmptyComposeRule` подтверждён в пакете `androidx.compose.ui.test.junit4.v2` по байткоду артефакта 1.12.1; при сбое импорта сверять по `classes.jar`, не менять пакет наугад.
- `OverlaySnoozeTest`: интент `SnoozeReceiver.createIntent` — explicit (component), поэтому динамический receiver его НЕ поймает; тест доказывает верный `eventId` снятием уведомления с `id == eventId.hashCode()`.
- Connected-тесты стирают данные приложения; не запускать на `Api37` с живым входом в Яндекс.