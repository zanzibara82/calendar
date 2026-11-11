# Выбор сигнала напоминания и тихий режим — спека

Дата: 2026-11-05. Ветка: `feature/visual-language`. Родительская спека:
[2026-11-01-android-visual-language-design.md](2026-11-01-android-visual-language-design.md).

## 1. Цель и границы

Пользователь выбирает, что звучит в полноэкранном окне напоминания: отдельно для
обычного события и отдельно для дня рождения. Плюс глобальный «Тихий режим»: сигнал не
звенит, напоминание приходит молча — модальное окно и вибрация.

Вне границ:

- канал `channel_event_reminders_v2` не трогаем: звук живёт в канале, а смена звука канала
  требует пересоздания id и молча сбрасывает системные настройки пользователя;
- звук heads-up уведомления не меняется — выбранному сигналу подчиняется только оверлей;
- структура экранов и навигация не меняются: новые контролы живут в существующем экране
  настроек и в существующей шапке главного экрана;
- Room-схема не меняется: и признак дня рождения, и тихий режим — настройки и производные от
  уже хранящихся полей;
- Windows-порт унаследует только идею и имя флага; `content://` URI — android-специфичны,
  переносить их нельзя.

## 2. Решения, согласованные с пользователем 05.11.2026

| Развилка | Решение |
| --- | --- |
| Источник сигнала | системный пикер `RingtoneManager.ACTION_RINGTONE_PICKER` |
| Признак дня рождения | локальная метка `EventTag.BIRTHDAY` **плюс** имя привязанной коллекции; решать в момент срабатывания, данные событий не переписывать |
| Где играет выбранный сигнал | только `ReminderActivity`, канал уведомления не меняется |
| URI недоступен (риингтон удалён, доступа нет) | играть встроенный `R.raw.reminder_sound`, в настройках показывать «сигнал недоступен» |
| Пункт «Без звука» в пикере | нет: сигнал обязателен, для молчания служит тихий режим |
| Предпрослушивание | да, кнопка play/stop у каждой строки выбора |
| По умолчанию, если ничего не выбрано | встроенный `R.raw.reminder_sound` (текущее поведение) |
| Android 8–9 и доступ к файлам рингтонов | `READ_EXTERNAL_STORAGE` с `maxSdkVersion="28"`, запрос при первом выборе |
| Тихий режим | только модалка + вибрация, heads-up уведомления нет |
| Вибрация оверлея | зацикленный пульс 600 мс / пауза 1200 мс, пока окно открыто |
| Приоритет тихого режима | выше обоих выбранных сигналов |
| Колокольчик в шапке | тап переключает режим |
| Всплытие при погашенном экране | канал `IMPORTANCE_HIGH` без звука: full-screen intent доставляется только с HIGH и выше, а прямой `startActivity` из receiver на API 29+ запрещён |
| «Отложить» в тихом режиме | кнопка в самом оверлее, тот же путь, что у кнопки в уведомлении |
| Место в настройках | новая карточка «Напоминания» |

**Решение контроллера (не спрашивал, обратимо одной строкой):** обе строки открывают пикер с
`EXTRA_RINGTONE_TYPE = TYPE_ALARM`. Сигнал всегда звучит в контексте будильника и на
аларм-потоке; фильтр `TYPE_RINGTONE` показал бы для дня рождения список, который в этом
контексте не играется. Различие между двумя строками — в выборе пользователя, а не в наборе
доступных файлов.

## 3. Модель

`domain/model/ReminderSound.kt`:

```kotlin
/** Что играть в оверлее. Вычисляется до запуска активности, активность не читает настройки. */
sealed interface ReminderSound {
    data object Silent : ReminderSound
    data class FromUri(val uri: String) : ReminderSound
    data object Builtin : ReminderSound
}
```

`domain/holiday/` остаётся отдельной папкой; сюда — `domain/reminder/BirthdayCalendar.kt`:

```kotlin
object BirthdayCalendar {
    /** «Дни рождения», «День рождения (Яндекс)», «Birthdays», «ДР» → true; null → false. */
    fun matches(calendarName: String?): Boolean
}
```

Совпадение по нормализованному имени: `lowercase()` плюс вхождения `день рожд`, `birthda`,
либо отдельное слово `др`. Проверка по слову, а не по подстроке, нужна потому, что `др`
содержится внутри кириллических слов («дровни») и давала бы ложные срабатывания.

`domain/reminder/ReminderSoundPolicy.kt` — чистый класс, вся арифметика выбора здесь:

```kotlin
class ReminderSoundPolicy @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    suspend fun resolve(event: Event, calendarName: String?): ReminderSound
}
```

Порядок разрешения: `quietMode` → `Silent`; далее день рождения (метка `BIRTHDAY` у события
ИЛИ `BirthdayCalendar.matches(calendarName)`) → `birthdaySoundUri`; иначе → `alarmSoundUri`;
отсутствующий (null) URI → `Builtin`. `FromUri("")` нормализуется в `Builtin` — пустая строка
в DataStore означает «не выбирали», а не «без звука».

## 4. Хранилище настроек

В `SettingsRepository`/`SettingsRepositoryImpl` (ключи рядом с `Keys.THEME_MODE`):

```kotlin
val alarmSoundUri: Flow<String?>          // key "alarm_sound_uri"
val birthdaySoundUri: Flow<String?>       // key "birthday_sound_uri"
val quietMode: Flow<Boolean>              // key "quiet_mode", false по умолчанию
fun calendarName(provider: CalendarProvider): Flow<String?>  // "calendar_name_google" / "_yandex"
suspend fun setAlarmSoundUri(uri: String?)
suspend fun setBirthdaySoundUri(uri: String?)
suspend fun setQuietMode(enabled: Boolean)
suspend fun setCalendarName(provider: CalendarProvider, name: String)
```

Имя коллекции нужно потому, что у Google `UserAccount.displayName` — это имя календаря, а у
Яндекса — имя пользователя из OAuth: единого источника нет. Пишется там же, где
`setCollectionUrl` ([SyncRepositoryImpl.kt:211](../../../app/src/main/java/com/example/mycalendar/data/repository/SyncRepositoryImpl.kt#L211)),
и стирается при `clearCollectionUrl` — смысл у ключей один и тот же: «от какой коллекции мы
знаем правду».

`RemoteFetch` получает поле `calendarName: String? = null`
([RemoteCalendarDataSource.kt:34-43](../../../app/src/main/java/com/example/mycalendar/data/remote/RemoteCalendarDataSource.kt#L34-L43))
и заполняется в обоих источниках: Google — из `SystemCalendar.displayName`, уже доступного в
`fetchUpdatedSince` ([SystemCalendarDataSource.kt:131-146](../../../app/src/main/java/com/example/mycalendar/data/system/SystemCalendarDataSource.kt#L131-L146));
Яндекс — из `CalDavClient.Calendar.displayName`, который сейчас теряется в кэше
`calendarUrl()` ([YandexCalendarDataSource.kt:57-62](../../../app/src/main/java/com/example/mycalendar/data/remote/yandex/YandexCalendarDataSource.kt#L57-L62))
— кэшируем объект целиком.

## 5. Разрешение сигнала и запуск

`AlarmReceiver` уже читает событие из репозитория
([AlarmReceiver.kt:56-98](../../../app/src/main/java/com/example/mycalendar/notifications/AlarmReceiver.kt#L56-L98));
он же получает `ReminderSoundPolicy.resolve(event, calendarName)` и передаёт результат дальше.
Интент оверлея строится в одном месте — фабрика `ReminderActivity.createIntent(context, event,
sound)`, которую вызывают и `AlarmReceiver` (прямой запуск), и `NotificationHelper`
(full-screen intent, сейчас дублируют друг друга в
[NotificationHelper.kt:140-148](../../../app/src/main/java/com/example/mycalendar/notifications/NotificationHelper.kt#L140-L148)).
Экстры: `EXTRA_EVENT_ID`, `EXTRA_TITLE`, `EXTRA_TIME_TEXT`, плюс две примитивные —
`EXTRA_QUIET: Boolean` и `EXTRA_SOUND_URI: String?`. sealed-объект в интент не кладём:
`Quiet == true` → `Silent`, иначе `EXTRA_SOUND_URI != null` → `FromUri`, иначе `Builtin`.
Активность ничего не читает из DataStore в `onCreate` — решение принято до неё.

`startLoopingSound()` ([ReminderActivity.kt:97-119](../../../app/src/main/java/com/example/mycalendar/notifications/ReminderActivity.kt#L97-L119)):

- `Silent` — звук не поднимается вообще, вместо него `VibrationLoop`;
- `FromUri` — `MediaPlayer.create(this, Uri.parse(uri))`; этот перегруженный вариант возвращает
  `null`, если файл не открылся (удалён, нет доступа, чужой провайдер умер), — тогда второй
  попыткой `MediaPlayer.create(this, R.raw.reminder_sound)`;
- `Builtin` — текущая ветка без изменений.

`AudioAttributes` (USAGE_ALARM + CONTENT_TYPE_MUSIC) и зацикливание `isLooping` остаются как
сейчас и для выбранного URI — иначе громкость и поведение будут различаться.

`VibrationLoop` (`notifications/`): `VibratorManager` на API 31+, `Vibrator` ниже,
`VibrationEffect.createOneShot(600, DEFAULT_AMPLITUDE)` плюс `Handler.postDelayed` с шагом
1800 мс (600 + 1200). Проверка `hasVibrator()`: на headless-эмуляторе вибрации нет, и отсутствие
не должно ронять оверлей. Остановка в `onDestroy` и в `finishIfRunning()` — иначе пульс
продолжится после «Отложить», ровно как сегодня не должен продолжаться звук.

## 6. Тихий режим

Уведомление в тихом режиме публикуется на новом канале
`channel_event_reminders_quiet` (`IMPORTANCE_HIGH`, без звука, без вибрации, без lights).
HIGH здесь — не громкость, а условие доставки full-screen intent: система доставляет FSI
только с канала HIGH и выше, а прямой `startActivity` из `BroadcastReceiver` на API 29+
запрещён (точные будильники не входят в исключения BAL). Поэтому звук, вибрация и lights
канала выключены, а оверлей всплывает и при погашенном экране. Честное следствие: при
разблокированном экране система может показать беззвучный heads-up — это плата за доставку
оверлея, «тихий режим» остаётся тихим по звуку, но не по присутствию на экране.

Создание нового канала — добавка в `createChannels()`, существующий `v2` остаётся нетронутым:
импортанс и звук неизменяемы после создания, поэтому «сделать канал тихим» означало бы
пересоздать его с новым id и сбросить пользовательские настройки.

Оверлей в тихом режиме: молчит, вибрирует пульсом, показывает те же кнопки плюс новая
«Отложить». Кнопка в самом окне нужна потому, что оверлей перекрывает шторку: действия
уведомления доступны только после её разворачивания, а окно уже на экране. Обработчик
вызывает тот же путь, что и действие уведомления:
`SnoozeReceiver.createIntent(context, eventId)` через `sendBroadcast()`, после чего активность
закрылась бы сама (`SnoozeReceiver` уже зовёт `ReminderActivity.finishIfRunning()`), поэтому
активность дополнительно вызывает `stopLoopingSound()`-эквивалент (остановка пульса) и
`finish()`.

Индикатор: `Icons.Filled.NotificationsOff` (зависимость `material-icons-extended` уже есть) в
`actions` шапки главного экрана
([CalendarScreen.kt:87-94](../../../app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt#L87-L94)),
перед «Сегодня». Иконка показывается только при активном режиме; тап выключает. Включается
режим только из настроек — так и просил пользователь.

## 7. Экран настроек

Новая карточка «Напоминания» — `SectionHeader` плюс три контрола, после «Оформления», до
«Разрешений» ([SettingsScreen.kt:240-258](../../../app/src/main/java/com/example/mycalendar/presentation/settings/SettingsScreen.kt#L240-L258)):

- `Switch` «Тихий режим» с подпиской в `SettingsUiState.quietMode`;
- строка «Сигнал напоминания» — имя выбранного файла (`Ringtone.getTitle`) или
  «Стандартный», справа `IconButton` play/stop;
- строка «Сигнал дня рождения» — то же.

Пикер запускается из Compose через `rememberLauncherForActivityResult(StartActivityForResult())`
— `ACTION_RINGTONE_PICKER` не имеет готового контракта; результат читается из
`RingtoneManager.EXTRA_RINGTONE_PICKED_URI`. `EXTRA_RINGTONE_SHOW_SILENT = false`,
`EXTRA_RINGTONE_SHOW_DEFAULT = true`, `EXTRA_RINGTONE_EXISTING_URI` = текущий выбор.

Предпрослушивание: `RingtoneManager.getRingtone(context, uri)` c
`AudioAttributes(USAGE_ALARM)`, один раз без зацикливания; `stop()` при смене выбора, при
повторном тапе и в `onStop` экрана. Кнопка недоступна, если рингтон не открывается, а строка
показывает «Сигнал недоступен, используется стандартный» — то же самое состояние, что и
фактический откат в оверлее, поэтому вранья в UI нет.

При активном тихом режиме обе строки сигнала затемнены (`enabled = false`), выбор сохранён и
возвращается при выключении тумблера.

## 8. Разрешения

`AndroidManifest.xml`: `<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
android:maxSdkVersion="28" />`. На API 29+ файлы рингтонов открываются без разрешения; на
API 26–28 без него `MediaPlayer.create(context, uri)` вернёт `null`, и выбранная настройка
стала бы декоративной. Запрос — через `RequestPermission` контракт в момент первого выбора
сигнала, только на API ≤ 28; отказ не блокирует работу: работает откат на встроенный сигнал.

`VIBRATE` уже объявлен ([AndroidManifest.xml:28](../../../app/src/main/AndroidManifest.xml#L28)).

## 9. Ошибки и деградация

| Случай | Поведение |
| --- | --- |
| URI удалён/недоступен в момент напоминания | играет `reminder_sound`; в настройках строка «Сигнал недоступен» |
| URI недоступен в предпрослушивании | кнопка play неактивна, то же состояние |
| `quietMode` включён | `Silent`, выбор сигнала игнорируется, но сохранён |
| Устройства без вибрации | пульс пропускается, оверлей показывается |
| Провайдер ещё не подключен, `calendarName` null | день рождения определяется только по метке |
| Событие без напоминаний | ни уведомления, ни оверлея — поведение не меняется |

## 10. Тесты

- `BirthdayCalendarTest` (JVM): «Дни рождения» → true, «День рождения (Яндекс)» → true,
  «Birthdays» → true, «ДР» → true, «Дровни» → false, «Рабочие встречи» → false, null → false.
- `ReminderSoundPolicyTest` (JVM, fake `SettingsRepository`): тихий режим → `Silent` поверх
  выбранного URI; метка `BIRTHDAY` + заданный `birthdaySoundUri` → URI дня рождения; события без
  метки из коллекции «Дни рождения» → URI дня рождения; обычное событие → `alarmSoundUri`;
  null URI → `Builtin`; `FromUri("")` → `Builtin`.
- `ReminderActivityIntentTest` (JVM или connected): `createIntent` кладёт в экстры вид и URI
  сигнала; `AlarmReceiver` и `NotificationHelper` собирают одинаковые экстры (регрессия на
  расхождение двух путей); повторный `onNewIntent` с новым событием и `Silent` обновляет
  eventId и гасит прежний звук.
- `QuietModeChannelTest` (connected): канал тихого режима существует с
  `IMPORTANCE_HIGH`, `shouldShowLights() == false`, звук не задан; канал `v2` сохраняет
  `IMPORTANCE_HIGH` — регрессия на «не трогать существующий канал».
- `OverlayQuietModeTest` (connected): запуск `ReminderActivity` с `EXTRA_QUIET = true`
  не создаёт `MediaPlayer` (`internal fun soundPlayingForTest(): Boolean == false`) и не
  падает там, где `hasVibrator() == false`.
- `OverlaySnoozeTest` (connected): тап «Отложить» рассылает `SnoozeReceiver.ACTION_SNOOZE` с
  `EXTRA_EVENT_ID` и закрывает активность.
- `SettingsRemindersCardTest` (connected): переключение тумблера делает обе строки сигнала
  неактивными; тумблер с сохранённым выбором не стирает сохранённый URI.
- Счётчики прогонов после реализации обновляются в `docs/how-to-verify.md` и README.

## 11. Что меняется в файлах

Создаются: `domain/model/ReminderSound.kt`, `domain/reminder/BirthdayCalendar.kt`,
`domain/reminder/ReminderSoundPolicy.kt`, `notifications/VibrationLoop.kt`,
`app/src/test/java/com/example/mycalendar/domain/reminder/BirthdayCalendarTest.kt`,
`app/src/test/java/com/example/mycalendar/domain/reminder/ReminderSoundPolicyTest.kt`,
`app/src/androidTest/java/com/example/mycalendar/QuietModeChannelTest.kt`,
`app/src/androidTest/java/com/example/mycalendar/OverlayQuietModeTest.kt`.

Изменяются: `domain/repository/SettingsRepository.kt` (+4 getter/4 setter),
`data/preferences/SettingsRepositoryImpl.kt` (ключи),
`data/remote/RemoteCalendarDataSource.kt` (`RemoteFetch.calendarName`),
`data/system/SystemCalendarDataSource.kt`, `data/remote/yandex/YandexCalendarDataSource.kt`,
`data/remote/yandex/CalDavClient.kt` (не теряем displayName),
`data/repository/SyncRepositoryImpl.kt` (запись имени рядом с `setCollectionUrl`),
`notifications/ReminderActivity.kt` (фабрика интента, разбор экстр сигнала, пульс, кнопка
«Отложить»), `notifications/AlarmReceiver.kt`, `notifications/NotificationHelper.kt`
(новый канал, выбор канала, общий `createIntent`),
`presentation/settings/SettingsScreen.kt`, `presentation/settings/SettingsViewModel.kt`,
`presentation/calendar/CalendarScreen.kt`, `presentation/calendar/CalendarViewModel.kt`
(индикатор и переключатель), `AndroidManifest.xml`, `strings.xml`, README,
`docs/how-to-verify.md`.
