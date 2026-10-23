# MyCalendar — долговременные заметки проекта

## Стек
Kotlin + Jetpack Compose (Material 3), MVVM + Clean Architecture, Hilt, Room,
Coroutines/Flow, DataStore Preferences. Пакет `com.example.mycalendar`.
minSdk 26, compileSdk 36.

## Ключевая архитектурная договорённость
**Вход в приложение ≠ синхронизация календарей.**
- Вход — локальный, флаг `SettingsRepository.onboardingCompleted`. Кнопка «Войти».
- Google / Яндекс подключаются **в Настройках** и служат только для синхронизации.
- Провайдеры независимы: можно подключить оба сразу, у каждого своё
  `lastSyncTime(provider)` и своя кнопка синхронизации.
- События с `calendarProvider == LOCAL` пушатся в облако только если подключён
  **ровно один** провайдер (защита от дублей в оба облака).

## Sync-архитектура (актуально)
- `RemoteCalendarDataSource` (data/remote) — общий интерфейс pull/push.
  Реализации:
  - `GoogleCalendarDataSource` (Google REST API v3) — fallback, не подключён к SyncRepository.
  - `YandexCalendarDataSource` (CalDAV over `caldav.yandex.ru`) — нужен `YANDEX_CLIENT_ID`;
    карточка скрыта без него. Реализован (см. ниже «Yandex OAuth»).
  - `SystemCalendarDataSource` (data/system, `CalendarContract`) — **текущий Google-стек**.
    Аккаунт Google уже на телефоне → OAuth не нужен, только runtime
    `READ_CALENDAR`/`WRITE_CALENDAR`. Выбор календаря: primary Google-календарь
    с уровнем доступа ≥ CONTRIBUTOR; id и account запоминаются в DataStore
    (`system_calendar_id`/`system_calendar_account`).
- `RemoteCalendarDataSource.hasReliableUpdatedAt` (по умолчанию `true`,
  `SystemCalendarDataSource` = `false`): у `CalendarContract.Events` нет колонки
  «время изменения», поэтому `SyncRepositoryImpl.doSync` для таких провайдеров
  сравнивает события **по содержимому** (`sameContent`). `delete` идёт по
  `Events.DELETED == 1`.
- `MANAGED` в `AuthRepositoryImpl` / `SettingsViewModel`:
  `listOf(GOOGLE) + if (isYandexConfigured) YANDEX else emptyList()` —
  Яндекс-карточка автоматически появляется, когда в `OAuthConfig` появится client_id.

## Тестирование на эмуляторе
- Эмулятор: AVD `Medium_Phone`, Android 9 (API 28), `emulator-5554`.
- `adb` не в PATH: `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`.
- Прогон: `pm clear` → `am instrument -w -r … com.example.mycalendar.test/HiltTestRunner`.
- В `@Before` Hilt-теста **обязательно** `hiltRule.inject()`, иначе `@Inject lateinit`
  поля не инициализированы.
- Перед UI-тестами, зависящими от стартового экрана, сбрасывать
  `onboardingCompleted = false` и `authRepository.signOutAll()` — иначе
  карточки провайдеров перестают показывать «Подключить» (общий DataStore
  для процесса + переиспользование Activity).
- «Process crashed» в конце прогона обычно = умер эмулятор, а не баг в коде.
- Координаты `adb input tap` — в **реальных** пикселях устройства. Device `Medium_Phone`
  1080x2400; скриншоты приходят 720x1280 → 1.66x масштаб.
- `uiautomator dump` иногда падает с «could not get idle state» —
  частая причина долгая анимация Compose. Не критично, если результат проверяется
  через `screencap` или эмулятор+запросы.
- Календарные права `READ_CALENDAR`/`WRITE_CALENDAR` для тестов выдаются **через
  `GrantPermissionRule`**, а не `pm grant` через `uiAutomation.executeShellCommand`:
  shell-вариант асинхронный, и первый тест класса успевал упасть с «Нет доступа
  к календарю телефона». Правило грантит синхронно.
- Прогон по матрице: **одновременно только один эмулятор**, классы — по одному
  Gradle-вызову (`-Pandroid.testInstrumentationRunnerArguments.class=…`),
  перед каждым `pm clear`. Машина не тянет два эмулятора.
- AVD: `Medium_Phone` = Android 9 / API 28 (грузится ~45 с),
  `Api30` (pixel_2, `system-images;android-30;google_apis;x86_64`) = Android 11 (грузится ~60 с).
  AVD `BP_34` (API 34) **не поднимается на этой машине** — adb `offline` 15 мин, потом смерть.
- Тесты `CalendarContract`: на чистом AVD календарей нет вообще → `SystemCalendarTest`
  сам создаёт локальный календарь (`ACCOUNT_TYPE_LOCAL`, `CALLER_IS_SYNCADAPTER=true`)
  и удаляет его в `@After`; иначе `Assume` — скип, а не фейл.

## Экран входа: случайный фон
- 4 иллюстрации лежат в `res/drawable-nodpi` как WebP: `auth_bg_plans`,
  `auth_bg_features`, `auth_bg_day_planning`, `auth_bg_all_in_one`.
  `AuthScreen` выбирает `remember { AUTH_BACKGROUNDS.random() }` — **не**
  `rememberSaveable` (иначе фон залипает на многие запуски: saved state
  переживает смерть процесса).
- Scrim поверх — градиент `colorScheme.surface`, плотность по яркости темы:
  светлая `0.30/0.45/0.55`, тёмная `0.68/0.74/0.82`.
- AGP 9 даёт ресурсам короткие имена (`res/zY.webp`) — искать свой drawable
  в APK по имени бесполезно, только по размеру/хешу.

## Приёмы проверки UI без просмотра скриншотов
- Модель не читает PNG. Проверять картинку/фон численно: `adb screencap` +
  Pillow (`ImageStat.mean/stddev` по полосам). stddev ≈ 0 → пустой surface,
  20–45 → контент есть.
- Pillow ставлен в `~/.workbuddy-ai/binaries/python/envs/default`.
- Тёмная тема: `adb shell cmd uimode night yes|no` (работает и на API 28).
- Иконки в Compose TopAppBar не видны в `uiautomator dump` — искать их по
  тёмным столбцам пикселей в скриншоте.

## Baseline Profile / R8
- `android.enableR8.fullMode=true` включён (A/B: 2.28 МБ vs 2.58 МБ compat, старт ~на 6% быстрее).
- Модуль `:baselineprofile` (`com.android.test`, `BaselineProfileRule.collect`) создан
  и компилируется, но **профиль ни разу не сгенерирован**: нужен API 33+ (или root),
  а API 34 на этой машине не стартует.
- AGP = **9.4.0**: плагин `androidx.baselineprofile` 1.4.1 не создаёт
  `:app:generateBaselineProfile` (consumer-wiring несовместим).
- Release APK УЖЕ содержит `assets/dexopt/baseline.prof` (профили зависимостей
  androidx/Compose мержит сам AGP) + подключён `androidx.profileinstaller`.

## Окружение / незавершённое
Только Яндекс-сторона требует ручной регистрации (после того, как впишете
`YANDEX_CLIENT_ID`, карточка автоматически появится в Настройках):
- Яндекс: `OAuthConfig.YANDEX_CLIENT_ID` пуст → карточка Яндекса скрыта
  в UI; `OAuthConfig.isYandexConfigured` = `false`.

## Yandex OAuth
- Регистрация: https://oauth.yandex.ru/client/new, callback `mycalendar://oauth`.
- Scopes в коде (`YandexAuthDataSource.SCOPES`):
  - `login:info` — логин/имя/пол/display_name
  - `login:email` — default_email
  - `calendar:all` — полный доступ к Календарю (используется CalDAV-клиентом)
- CalDAV-сервер: `caldav.yandex.ru` (НЕ `caldav.imap.yandex.ru`).
- **Синхронизация с Яндексом РЕАЛИЗОВАНА** (см. `data/remote/yandex/`):
  `IcsMapper` (Event↔VCALENDAR), `CalDavClient` (PROPFIND/REPORT/PUT/DELETE,
  HTTP Basic `user=email, pass=OAuth-token`), `YandexCalendarDataSource`
  (`RemoteCalendarDataSource`, `hasReliableUpdatedAt=false` → сверка по содержимому
  в `SyncRepositoryImpl`). `SettingsViewModel.syncAvailable(YANDEX)=true` — кнопка
  «Синхронизировать» активна. Для реальной сетевой синхронизации нужен живой
  токен (`calendar:all`) + включённый «Яндекс.Календарь» в Доступах oauth.yandex.ru.
- Токен хранится в `EncryptedSharedPreferences` (security-crypto 1.1.0-alpha06,
  мастер-ключ AES256_GCM). R8 требует `-dontwarn com.google.errorprone.annotations.**`
  в `proguard-rules.pro` из-за зависимостей Tink.
