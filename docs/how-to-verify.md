# Как установить и что проверить

Практическая памятка: куда ставить сборку, что смотреть руками и как прогнать автотесты.
Все команды — из корня проекта, в Git Bash (Windows) или обычном терминале.

## Что где лежит

| Что | Путь |
|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK (подписан, если есть `keystore.properties`) | `app/build/outputs/apk/release/app-release.apk` |
| Отчёт unit-тестов | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Отчёт instrumented-тестов | `app/build/reports/androidTests/connected/debug/index.html` |
| JUnit-XML instrumented | `app/build/outputs/androidTest-results/connected/debug/` |
| Отчёт lint | `app/build/reports/lint-results-debug.html` |
| Скриншоты экранов | `docs/screenshots/` |

Сборка (SDK с платформой API 37, JDK 17+):

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

## Установка на телефон

1. Включите «Параметры разработчика» → «Отладка по USB».
2. Подключите телефон, проверьте, что он виден: `adb devices` (путь к `adb` — см. ниже).
3. Установка:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# или
adb install -r app/build/outputs/apk/release/app-release.apk
```

Debug и release подписаны разными ключами, поэтому поверх одной сборки другую поставить нельзя:
сначала `adb uninstall ru.zanzibara82.calendar`.

`adb` не в PATH, полный путь: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
(в Git Bash — `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`).

## Запуск эмулятора

Готовые AVD: `Medium_Phone` (API 28, для автотестов) и `Api37` (API 37; на нём может остаться
единственный живой вход в Яндекс, поэтому его данные не трогаем).

```bash
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd Medium_Phone -no-window -no-audio \
    -no-boot-anim -gpu swiftshader_indirect -no-snapshot-load -memory 2048
```

- `-no-snapshot-load` обязателен: снимок приносит будильники прошлых прогонов.
- `-memory 2048` нужен, когда у хоста мало свободной памяти, иначе эмулятор не стартует.
- После загрузки дайте системе 1–2 минуты: пока идёт `dexopt`, тесты и широковещательные
  интенты могут вести себя нестабильно.
- Остановка: `adb emu kill`.

## Что проверить руками

### Запуск и базовые сценарии

1. Первый экран → «Войти»: открывается календарь (это вход в приложение, а не подключение
   календарей).
2. Создайте событие, откройте его, отредактируйте, удалите.
3. Напоминание: событие через 2–3 минуты с напоминанием «5 мин» → уведомление должно прийти
   почти сразу (время напоминания уже прошло, а событие ещё нет); в уведомлении кнопки
   «Открыть событие» и «Отложить на 10 мин»; «Отложить» снимает уведомление и повторяет его
   через 10 минут.

### Google

Синхронизация идёт через системный календарь: достаточно аккаунта Google на устройстве.
Настройки → карточка Google → «Подключить» → выберите календарь. OAuth и client id не нужны.

### Яндекс: подключение

1. Настройки → карточка Яндекса → «Подключить» — открывается браузер со страницей OAuth.
2. Вход и подтверждение выполняет человек (пароль и push-код вводятся только им).
3. После согласия приложение возвращается на `mycalendar://oauth`, и в карточке вместо
   «Не подключён» появляется email аккаунта.

Ожидание токена — **10 минут**. Если вход занял больше, приложение вернёт сообщение
«Вход через Яндекс не завершён. Если вы уже вошли в браузере, нажмите «Подключить» ещё раз…».

### Яндекс: переиспользование сохранённого токена

Проверка исправления «опоздавшего редиректа»:

1. Доведите вход до состояния, когда показано сообщение «Вход через Яндекс не завершён…»
   (то есть токен уже пришёл, а аккаунт ещё нет).
2. Нажмите «Подключить» ещё раз.
3. **Ожидаемо:** браузер не открывается, в карточке сразу появляется email аккаунта.

Быстро воспроизвести именно опоздание редиректа нельзя — оно зависит от длительности входа.
Сам сценарий «токен есть, аккаунта нет» покрыт тестом `YandexSignInReuseTokenTest`.

### Яндекс: синхронизация

1. **Отправка:** создайте событие в приложении → «Синхронизировать» → событие появилось
   в веб-календаре Яндекса.
2. **Получение:** измените событие в вебе → «Синхронизировать» → правка видна в приложении.
3. **Конфликт:** измените одно и то же поле в обоих местах → «Синхронизировать» покажет
   «Готово: … конфликтов 1» → откройте разбор → выберите значение → очередь пустеет.
4. **Удаление на сервере:** удалите событие в веб-календаре → «Синхронизировать» → событие
   исчезает в приложении. Если с момента подключения не было ни одной успешной синхронизации,
   первый проход только запоминает коллекцию, и удаление подхватится вторым проходом.

### Обновления через GitHub Releases

Проверка идёт по `api.github.com/repos/zanzibara82/calendar/releases/latest` без токена.

1. Настройки → «Обновления» → «Проверить обновления». Пока релизов нет, ожидаемо
   «Опубликованных релизов пока нет» (ответ 404 — это нормальный результат, а не ошибка).
2. После публикации релиза с тегом вида `v1.1.0` и приложенным APK проверка покажет
   «Доступна версия 1.1.0»; если тег не новее установленной версии — «Установлена последняя версия».
3. «Скачать и установить» загружает APK в кэш и открывает системный установщик. Если установка
   из этого источника запрещена, приложение предложит «Открыть разрешение».
4. APK с другой подписью не устанавливается: SHA-256 сертификата архива сверяется с установленным
   пакетом, при несовпадении выводится «Подпись загруженного APK не совпадает…». На Android 9
   сертификат архива читается через устаревший `GET_SIGNATURES`: `GET_SIGNING_CERTIFICATES`
   для файла там возвращает пустые данные, и без отката отвергался бы любой корректный APK
   (регресс закрыт тестом `ApkInstallerTest`).
5. Релиз `v1.1.1` помечен pre-release: в нём тот самый баг проверки подписи, поэтому на Android 9
   встроенное обновление с установленной 1.1.1 всегда отклоняет скачанный APK. `releases/latest`
   pre-release игнорирует — проверка обновлений его не предложит. Первый корректный релиз — `v1.1.2`.

### Сигнал напоминания и тихий режим

1. Настройки → «Напоминания» → строка «Сигнал напоминания» открывает системный пикер
   (вкладка будильников): выберите сигнал и нажмите play — он проигрывается для предпрослушивания.
   То же для строки «Сигнал дня рождения».
2. Тумблер «Тихий режим» затемняет обе строки сигналов: выбор сохраняется, а после выключения
   тихого режима возвращается.
3. При включённом тихом режиме в шапке календаря виден значок `NotificationsOff`; тап по нему
   выключает режим. Включить режим можно только из настроек.
4. Напоминание в тихом режиме: оверлей молчит (без звука), вибрирует пульсом, кнопка «Отложить»
   работает; при погашенном экране оверлей всплывает поверх блокировки. Это делает канал
   `channel_event_reminders_quiet_v2` с `IMPORTANCE_HIGH` без звука/вибрации/lights: full-screen
   intent доставляется только с HIGH и выше, а прямой `startActivity` из receiver на API 29+
   запрещён. Побочное следствие: при разблокированном экране возможен беззвучный heads-up.
5. Событие с меткой «День рождения» или из коллекции «Дни рождения» звучит выбранным сигналом
   дня рождения, а не сигналом напоминания.

### Windows: вход через Яндекс по коду устройства

Запуск: `./gradlew :desktopApp:run` (или собранный `.exe`).

1. Шестерёнка → секция «Календари» → строка «Яндекс» с подписью «Не подключён — вход по коду
   на ya.ru/device» → «Войти». Появляется диалог с одноразовым кодом Яндекса и адресом
   `https://ya.ru/device`.
2. «Скопировать код» кладёт код в буфер обмена. «Открыть страницу» открывает браузер; если
   рабочий стол их не открывает, в диалоге появляется ошибка с адресом для ручного перехода.
3. Введите код на странице и подтвердите вход — карточка сменится на «Подключено: имя или email»,
   диалог закроется сам. Ожидание ограничено 5 минутами; по истечении код перестаёт действовать,
   и повторное «Войти» запрашивает новый.
4. «Отмена» (или Esc) закрывает диалог без сообщения об ошибке, вход отменяется.
5. Токен живёт только в памяти процесса. `account.json` помнит аккаунт, поэтому после перезапуска
   карточка снова покажет «Подключено», но токена уже нет: для синхронизации вход придётся
   повторить («Выйти» → «Войти»).

### Windows: синхронизация

1. После входа в карточке «Календари» под строкой аккаунта появляется кнопка «Синхронизировать»
   и строка состояния («Синхронизация ещё не выполнялась» до первого прохода).
2. Нажмите её: на время прохода кнопка неактивна, строка показывает «Синхронизация…», затем —
   «Готово: отправлено N, получено M, конфликтов K». Созданное в окне событие появляется
   в веб-календаре Яндекса, а правки из веба видны в сетке месяца без перезапуска.
3. Закройте и откройте настройки снова или перезапустите программу: строка покажет время
   последней синхронизации из `settings.json`.
4. Ошибки видны там же: «Ошибка синхронизации: …» (например, истёк токен — войдите заново),
   «Синхронизировано частично: …». Конфликты пока только считаются: разбор появится следующим
   шагом, до него спорные события остаются локальными и в облако не отправляются.

## Визуальный язык

Спека: [docs/superpowers/specs/2026-11-01-android-visual-language-design.md](superpowers/specs/2026-11-01-android-visual-language-design.md).
Палитра navy и токены (`presentation/theme/Tokens.kt`, `Type.kt`) — единый источник стилей для
Android и будущего Windows-приложения, поэтому динамические цвета Material You вырезаны: тема
даёт ровно те цвета, что заданы токенами.

Что смотреть руками (светлая и тёмная тема):

- месяц, неделя и день — события в карточках с полосой провайдера слева; чипы провайдера и метки;
- неделя — столбец из семи карточек дней: пустой день — карточка только с заголовком «пн 02.11»,
  в занятом дне не больше четырёх строк, дальше — «+N ещё» по тапу;
- название события в недельной строке не режется и не упирается в край карточки;
- сегодня помечено точкой в заголовке карточки дня, а в месяце — рамкой ячейки; выбранный день
  в месяце залит `primary` с тем же скруглением ячейки.

Что прогонять:

```bash
./gradlew :app:testDebugUnitTest --tests '*PaletteContrastTest'  # контраст пар текст/поверхность
adb shell am instrument -w -r -e class com.example.mycalendar.EventCardUiTest \
    ru.zanzibara82.calendar.test/com.example.mycalendar.HiltTestRunner
adb shell am instrument -w -r -e class com.example.mycalendar.ThemePaletteTest \
    ru.zanzibara82.calendar.test/com.example.mycalendar.HiltTestRunner
adb shell am instrument -w -r -e class com.example.mycalendar.WeekViewCardsUiTest \
    ru.zanzibara82.calendar.test/com.example.mycalendar.HiltTestRunner
adb shell am instrument -w -r -e class com.example.mycalendar.DayViewHolidayUiTest \
    ru.zanzibara82.calendar.test/com.example.mycalendar.HiltTestRunner
```

Ожидается (замерено 13.11.2026 по отчётам полных прогонов): JVM — 274 теста
(54 в `:app`, 91 в `:shared`, 129 в `:desktopApp`), 0 падений; линт — 0 ошибок,
17 предупреждений (состав ниже); connected — 75 тестов, 0 падений.
`WeekViewCardsUiTest` проверяет карточки и в понедельник, и в воскресенье: даты в нём считаются от
текущего дня, а не зашиты, и стрелками периодов он не пользуется. По месяцам сеткой ездит только
`DayViewHolidayUiTest` — через `testTag` стрелок периодов `period_prev` / `period_next`, поэтому
переименование этих тегов ломает именно его.

**Напоминание:** `connectedDebugAndroidTest` снимает приложение и стирает его данные, а заодно
сбрасывает выбранную тему на системную. Для скриншотов после прогона нужно заново поставить APK
(`adb install -r -t`), выбрать тему и засеять демо-события.

## Автоматические проверки

```bash
./gradlew :app:testDebugUnitTest :shared:test :desktopApp:test  # JVM: 274 теста, 0 падений
./gradlew :app:lintDebug                                        # 0 ошибок, 17 предупреждений
./gradlew :app:connectedDebugAndroidTest                        # эмулятор/устройство: 75 тестов
```

Перед полным connected-прогоном выдайте пакету разрешения вручную: установка APK их не даёт,
а без них прогон нестабилен (18/73 и падение `EventLog`/`EventCardUiTest`):

```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" shell appops set ru.zanzibara82.calendar SCHEDULE_EXACT_ALARM allow
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" shell pm grant ru.zanzibara82.calendar android.permission.POST_NOTIFICATIONS
```

Если пакета на устройстве ещё нет, сначала поставьте APK:
`adb install -r -t app/build/outputs/apk/debug/app-debug.apk` (или `./gradlew :app:installDebug`).

Ожидаемый результат connected: **75 тестов, 0 падений** (сумма классовых `<testsuite>` в JUnit-отчёте
`connectedDebugAndroidTest`, сверка по `<testcase>`; замер 12.11.2026).
`SchedulerAndMediaTest.voiceRecorder_recordsM4aFile` пишет настоящий `.m4a` и проходит даже на
headless-эмуляторе с `-no-audio` (замер 05.11.2026). Прежде тест молча пропускался:
`MediaRecorder.setAudioSource` не стартует без runtime-разрешения `RECORD_AUDIO`, которого установка
APK не даёт, а отсутствие разрешения списывали на отсутствие микрофона у эмулятора. Теперь
`RECORD_AUDIO` и `POST_NOTIFICATIONS` выдаются в `setUp` через `pm grant`.

Состав линта — 17 предупреждений: `UseKtx` (5, из них 4 — в новых файлах сигнала и тихого режима),
`NewerVersionAvailable` (4 — Kotlin 2.4.21 и kotlinx-serialization-json 1.13.0), `IconXmlAndPng` (3),
`UnusedAttribute` (2 — `showWhenLocked`/`turnScreenOn` при minSdk 26),
`AndroidGradlePluginVersion` (2), `GradleDependency` (1). Ошибок нет: подписи пикера сигнала берутся
через `stringResource` до лямбды, а не через `context.getString`.

Отдельные тесты без полного прогона:

```bash
adb shell am instrument -w -r -e class com.example.mycalendar.YandexSignInReuseTokenTest \
    ru.zanzibara82.calendar.test/com.example.mycalendar.HiltTestRunner
adb shell am instrument -w -r -e class com.example.mycalendar.ReminderActivityIntentTest \
    ru.zanzibara82.calendar.test/com.example.mycalendar.HiltTestRunner
adb shell am instrument -w -r -e class com.example.mycalendar.QuietModeChannelTest \
    ru.zanzibara82.calendar.test/com.example.mycalendar.HiltTestRunner
adb shell am instrument -w -r -e class com.example.mycalendar.OverlayQuietModeTest \
    ru.zanzibara82.calendar.test/com.example.mycalendar.HiltTestRunner
adb shell am instrument -w -r -e class com.example.mycalendar.OverlaySnoozeTest \
    ru.zanzibara82.calendar.test/com.example.mycalendar.HiltTestRunner
adb shell am instrument -w -r -e class com.example.mycalendar.SettingsRemindersCardTest \
    ru.zanzibara82.calendar.test/com.example.mycalendar.HiltTestRunner
adb shell am instrument -w -r -e class com.example.mycalendar.QuietModeIndicatorTest \
    ru.zanzibara82.calendar.test/com.example.mycalendar.HiltTestRunner
```

**Важно:** `connectedDebugAndroidTest` снимает приложение с устройства после прогона и стирает
его данные. Не запускайте его на эмуляторе, где живёт единственный вход в Яндекс (`Api37`).

## Проверка подписи release

```bash
"$LOCALAPPDATA/Android/Sdk/build-tools/<версия>/apksigner.bat" verify --print-certs \
    app/build/outputs/apk/release/app-release.apk
```

Ожидаемый отпечаток V2-подписи:
`230043938f8f36ec14cf210828184e7ac4a39c8fed593e8b49d3a2d177c4cf38`.

Если файла `keystore.properties` нет, release собирается неподписанным
(`app-release-unsigned.apk`) и `apksigner` его отклонит — это ожидаемо.

## Если что-то пошло не так

| Симптом | Причина и что делать |
|---|---|
| Эмулятор не стартует, «Insufficient RAM» | Не хватает памяти: закройте сборку Gradle, запускайте с `-memory 2048` |
| «Pixel Launcher isn't responding» | Мало памяти у эмулятора: закройте лишние AVD и приложения |
| Тест упал сразу после загрузки эмулятора | Ещё идёт `dexopt`: подождите 1–2 минуты и повторите |
| `connectedDebugAndroidTest` прошёл «0 тестов» | Ручной AVD не подхватился: ставьте APK вручную (`adb install`) и запускайте через `am instrument` |
| «Вход через Яндекс не завершён» после долгого входа | Вход шёл дольше 10 минут: нажмите «Подключить» ещё раз — сохранённый токен подхватится без браузера |
| Пропали данные приложения | `connectedDebugAndroidTest` снёс пакет; вход в Яндекс придётся повторить |
