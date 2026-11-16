# Google Календарь в Windows-клиенте — спека

Дата: 2026-11-16. Ветка: `main` (реализация — по плану, отдельными задачами).

Согласовано с пользователем 16.11.2026, ответы на четыре вопроса дизайна:

1. синхронизация двусторонняя, как у Яндекса: pull-изменения в приложение и push локальных правок;
2. свой проект в Google Cloud Console, `client_id`/`client_secret` вшиваются в сборку;
3. только основной календарь (`primary`), выбор календаря не делаем;
4. синхронизация ручная — кнопка «Синхронизировать», как сейчас; фонового расписания нет.

## 1. Цель и границы

В Windows-клиенте появляется вторая карточка календаря — Google: вход через браузер,
двусторонняя синхронизация основного календаря, конфликты и напоминания — тем же движком,
что у Яндекса.

Почему сейчас этого нет: на Android Google подключён через системный календарь
(`CalendarContract`) — OAuth не нужен. На Windows системного календаря нет, поэтому нужен
собственный клиент Google Calendar API v3 и собственный вход. Заглушки
`DesktopAuthRepository.connectGoogleCalendar()` и `DesktopRemoteDataSourceProvider`
сегодня честно сообщают, что Google не поддерживается; они заменяются реализацией.

Вне границ:

- выбор календаря среди нескольких (только `primary`), списки календарей и цвета;
- участники, приглашения, конференции, вложения, `sendUpdates` с рассылкой писем;
- инкрементальный pull по `syncToken` (Yandex тоже читает коллекцию целиком);
- повторяющиеся исключения (правка одного вхождения серии) — серия живёт как одно событие;
- фоновая синхронизация и напоминания вне запущенного клиента;
- изменения Android-клиента и схемы Room.

## 2. Предпосылка: OAuth-клиент Google

Без собственного OAuth-клиента API не вызывает. Это ручной шаг владельца проекта
(агент не создаёт облачные ресурсы и не входит в аккаунт):

1. Google Cloud Console → новый проект (или существующий).
2. APIs & Services → Library → включить **Google Calendar API**.
3. OAuth consent screen: тип **External**, режим **Testing**; в Test users добавить
   собственный Google-аккаунт; в Scopes добавить `.../auth/calendar.events`.
4. Credentials → Create credentials → OAuth client ID → тип **Desktop app**.
5. Полученные `client_id` и `client_secret` положить в `local.properties` (файл вне git):

```properties
google.oauth.clientId=<client_id>
google.oauth.clientSecret=<client_secret>
```

6. Пересобрать клиент. Без этих двух свойств кнопка «Войти» у Google неактивна
   с подписью «Вход не настроен: нет client_id в local.properties» — приложение
   собирается и работает, Яндекс это не затрагивает.

Оговорки, которые попадут в README и в подсказку карточки:

- в режиме Testing refresh-токен живёт **7 дней** — раз в неделю нужен повторный вход;
  снять ограничение можно публикацией приложения с верификацией чувствительного scope;
- `client_secret` desktop-клиента Google по документации не является конфиденциальным,
  но в репозиторий не попадает: только `local.properties` и JVM-свойства сборки.

## 3. Вход: loopback + PKCE

Google не предоставляет device flow для календарных scope обычным desktop-приложениям
(его TV/limited-input вариант требует отдельного одобрения), поэтому вход — системный
браузер и локальный HTTP-сервер на петле, в отличие от кода устройства у Яндекса.

`desktopApp/.../auth/DesktopGoogleLogin.kt`:

- `HttpServer` на `127.0.0.1`, порт `0` (выбирает ОС), контекст `/oauth2callback`;
- redirect URI — `http://127.0.0.1:<порт>/oauth2callback`: для клиента типа Desktop
  Google принимает loopback с любым портом, регистрировать адрес не нужно;
- PKCE S256: `code_verifier` — 64 случайных байта Base64URL без паддинга,
  `code_challenge` — SHA-256 от него;
- URL авторизации: `client_id`, `redirect_uri`, `response_type=code`,
  `scope=openid email https://www.googleapis.com/auth/calendar.events`,
  `code_challenge`, `code_challenge_method=S256`, `state` (случайный),
  `access_type=offline`, `prompt=consent` (иначе refresh-токен может не прийти);
- браузер открывает переданный `openBrowser: (String) -> Boolean`
  (в `Main.kt` — существующий `openInBrowser`); `false` — вход завершается ошибкой
  «Не удалось открыть браузер»;
- сервер отвечает на callback страницей «Вход завершён, вернитесь в программу
  и закройте вкладку» (200) либо коротким текстом ошибки и завершает ожидание;
  `state` сверяется, несовпадение — ошибка входа; `error=access_denied` — «Вход отменён»;
- общий таймаут ожидания — 10 минут (`withTimeout`), отмена корутины останавливает сервер;
- обмен кода на токены: POST `https://oauth2.googleapis.com/token`
  (`grant_type=authorization_code`, `code`, `redirect_uri`, `client_id`,
  `client_secret`, `code_verifier`), разбор `access_token`, `refresh_token`,
  `expires_in`;
- профиль: GET `https://openidconnect.googleapis.com/v1/userinfo` с Bearer-токеном;
  `email` и `name` → `UserAccount(provider = GOOGLE, …)`;
- возвращает `GoogleSession(access, refresh, expiresAt, account)`; сам токен нигде
  не логируется и не попадает в сообщения об ошибках.

Все адреса и `OkHttpClient` инъектируются — тесты поднимают локальный `HttpServer`
и подставной `openBrowser`, сеть Google не нужна.

Состояния карточки при входе: кнопка «Войти» → «Ожидаем подтверждения в браузере…»
(кнопка неактивна), ошибка — текстом под карточкой, отмена (закрытие окна) —
корутина отменяется, сервер закрывается.

## 4. Токены

`desktopApp/.../data/DesktopGoogleTokens.kt` — хранение и обновление:

- файл `%APPDATA%\MyCalendar\google-token.bin`, внутри — JSON
  `{"access":"…","refresh":"…","expiresAt":<millis>}` под тем же DPAPI
  (`TokenCipher`/`DpapiTokenCipher`); открытые токены на диск не попадают;
- `DesktopPaths.googleTokenFile()` рядом с `token.bin` Яндекса;
- `accessToken(forceRefresh)`: токен считается свежим, пока
  `expiresAt - now > 60_000`; иначе — POST на token endpoint с
  `grant_type=refresh_token` (обновление под мьютексом, single-flight);
  если ответ не содержит нового `refresh_token`, старый сохраняется;
- `400 invalid_grant` (токен отозван, истекли 7 дней Testing, смена пароля) —
  файл токена стирается, вызывающий получает понятную ошибку
  «Сессия Google истекла — войдите заново в Настройках»;
- нечитаемый blob или один токен без другого — файл стирается, клиент просит войти заново;
- осиротевший файл (нет аккаунта Google в `account.json`) стирается при старте;
- «Выйти» у Google стирает только google-токен; токен и аккаунт Яндекса не трогаются.

`DesktopAuthRepository`:

- `managedProviders = listOf(GOOGLE, YANDEX)` — порядок как в контракте `AuthRepository`;
- конструктор получает `googleTokens` и `authorizeGoogle: suspend () -> GoogleSession`;
- `connectGoogleCalendar()` — реальный вход: сессия → сохранить токены и аккаунт;
  сбой сохранения токена не отменяет уже состоявшийся вход (как у Яндекса);
- `getAccessToken(GOOGLE, forceRefresh)` — access-токен Google; `getAccessToken()`
  (интерфейс `AccessTokenProvider`) по-прежнему отдаёт токен Яндекса для CalDAV;
- `restoreToken()` переименовывается в `restoreTokens()` и восстанавливает оба токена:
  Яндекс — если есть его аккаунт, Google — если есть аккаунт Google и файл читается;
- `signOut(GOOGLE)` стирает google-токен и запись аккаунта, `signOutAll()` — оба.

KDoc контрактов, которые перестают быть правдой, правятся в `:shared`
(изменения только документации, поведение Android не меняется):

- `AuthRepository.connectGoogleCalendar` — «клиент решает, как подключиться:
  Android — системный календарь, desktop — OAuth-вход»;
- `AuthRepository.getAccessToken` — Google не всегда `null`: desktop отдаёт access-токен;
- `AccessTokenProvider` — «только Яндекс» дополняется оговоркой про desktop;
- `RemoteCalendarDataSource` — в списке реализаций появляется Google.

## 5. Источник данных: GoogleCalendarDataSource

`shared/.../data/remote/google/GoogleCalendarDataSource.kt` (+ DTO и маппер
`GoogleEventMapper.kt`) — реализация `RemoteCalendarDataSource` поверх
Google Calendar API v3. Базовый URL и `OkHttpClient` инъектируются; токен берётся
через `AuthRepository.getAccessToken(GOOGLE, forceRefresh)`.

Pull — полная выборка основного календаря:

- GET `/calendars/primary/events` с `showDeleted=true`, `singleEvents=false`,
  `maxResults=2500`, пагинация по `nextPageToken` до конца;
- `collectionUrl = "google:primary"` — синтетический маркер коллекции для защиты
  движка от чужого/пустого ответа после смены аккаунта; `calendarName = null`
  (имена календарей не читаем, дни рождения Google лежат в отдельном календаре);
- `presentRemoteIds` — id всех полученных записей, включая `status=cancelled`;
  ответ неполный/неразобранный — исключение, успешная выборка всегда полная;
- `status=cancelled` → `deleted = true`: в отличие от CalDAV, Google присылает
  tombstone'ы, и удаление видно так же надёжно;
- маппинг полей:
  - `summary` (пустое → «(без названия)»), `description`;
  - `start.dateTime`/`end.dateTime` — RFC3339 → epoch millis;
  - события без времени (`start.date`): начало — 00:00 локальной зоны,
    конец — конец дня `end.date` (дата в Google исключительная, поэтому
    `atStartOfDay(zone) + 1 день - 1 нс`), то есть «00:00–23:59:59.999»;
  - `reminders.overrides` с `method=popup` и `minutes >= 0` → `reminderMinutes`;
    `useDefault=true` без overrides → пустой список (умолчания Google не импортируем);
  - `recurrence`: первый элемент вида `RRULE:FREQ=…` → `RepeatRule`
    (только DAILY/WEEKLY/MONTHLY/YEARLY, остальное игнорируем);
  - `updated` → `updatedAt` (в решении о слиянии не участвует, как и везде);
- ответ 401 → один принудительный refresh токена и повтор запроса; повторный 401 —
  ошибка «Сессия Google истекла — войдите заново в Настройках»;
  403 — текст ошибки с подсказкой про scope/верификацию; 429 — «Google временно
  ограничил запросы, повторите позже»; прочие не-2xx — исключение с кодом.

Push:

- `insert` — POST `/calendars/primary/events?sendUpdates=none`; тело:
  `summary`, `description`, `start.dateTime` + `timeZone`, `end.dateTime` + `timeZone`
  (зона — `ZoneId.systemDefault().id`), `recurrence` (`RRULE:FREQ=…` или нет поля),
  `reminders = {useDefault:false, overrides:[{method:"popup", minutes:N}]}`;
  возвращает `id` из ответа;
- `update` — PATCH `/calendars/primary/events/{id}?sendUpdates=none`, то же тело;
- `delete` — DELETE `/calendars/primary/events/{id}?sendUpdates=none`;
- `id` экранируется как сегмент пути; пустой ответ DELETE не ошибка.

Общий код RRULE: приватные `repeatToRrule`/`rruleToRepeat` из `IcsMapper` выносятся
в `domain/util/RruleCodec.kt` (`fun toRrule(RepeatRule?): String?`,
`fun fromRrule(String?): RepeatRule?`); `IcsMapper` и Google-маппер используют его,
поведение iCalendar не меняется (покрыто существующими тестами).

## 6. UI и wiring

`SettingsScreen` в разделе «Календари» получает вторую карточку **Google** — первой,
над карточкой Яндекса, как в `AuthRepository.connections` (GOOGLE, затем YANDEX):

- «Не подключён — вход через браузер» + кнопка «Войти»;
- не настроен OAuth-клиент — «Вход не настроен: нет client_id в local.properties»,
  кнопка неактивна;
- идёт вход — «Ожидаем подтверждения в браузере…», кнопка неактивна;
- «Подключено: <displayName или email>» + «Выйти»;
- ошибка входа — текст под карточкой.

Новые параметры `SettingsScreen`: `googleAccount`, `googleLoginError`,
`googleSignInEnabled`, `googleSigningIn`, `onGoogleSignIn`, `onGoogleSignOut`;
существующие параметры Яндекса не переименовываются.

`Main.kt`:

- создать `DesktopGoogleLogin` с настоящими адресами Google и `openInBrowser`,
  `DesktopGoogleTokens` (vault на `googleTokenFile()`), передать их в
  `DesktopAuthRepository`;
- `LaunchedEffect` вызывает `restoreTokens()`;
- кнопка «Войти» у Google запускает `connectGoogleCalendar()` в той же корутине-скоупе,
  что и Яндекс; повторный клик при идущем входе игнорируется;
- `DesktopRemoteDataSourceProvider` получает `google` и отдаёт его для
  `CalendarProvider.GOOGLE`;
- `syncEngine` не меняется: `syncNow()` сам обходит `connectedProviders()`,
  частичный успех («Синхронизировано частично») уже поддержан; карточка Яндекса
  и её статусы не меняются;
- конфликты и напоминания — без изменений: они работают по локальным событиям,
  провайдер для них не важен.

`desktopApp/build.gradle.kts`: читает `local.properties`, передаёт значения
JVM-свойствами `-Dmycalendar.google.clientId=…` и `-Dmycalendar.google.clientSecret=…`
рядом с `-Dmycalendar.version`. Свойства читает `DesktopGoogleOAuthConfig`
(константы адресов, scope, `configured`). Пустые свойства — допустимая сборка:
Google просто неактивен. Значения в логи и отчёты не печатаются.

## 7. Тесты

`shared`, `GoogleCalendarDataSourceTest` (JDK `HttpServer`, как у CalDAV-фикстур):

- pull: summary/description/время, popup-напоминания, RRULE → поля `RemoteEvent`;
- событие на весь день: локально 00:00–23:59:59.999;
- `status=cancelled` → `deleted = true`;
- пагинация: две страницы, все id в `presentRemoteIds`, `collectionUrl = "google:primary"`;
- insert шлёт ожидаемое тело (включая reminders/overrides) и возвращает id;
- update — PATCH по id, delete — DELETE по id;
- 401 → один refresh и повтор; повторный 401 — ошибка;
- нет токена — понятная ошибка «войдите заново».

`RruleCodecTest` (shared): четыре правила туда-обратно, `null`, мусор → `null`.

`DesktopGoogleLoginTest` (desktopApp): успешный вход на локальном сервере
(токены, email, expiresAt); несовпадение `state`; `access_denied`; недоступный браузер;
таймаут (сокращённый параметром) — сервер закрыт во всех случаях.

`DesktopGoogleTokensTest`: сохранение/чтение через vault; refresh при истечении и
сохранение старого refresh; `invalid_grant` стирает файл; нечитаемый blob не роняет запуск.

`DesktopAuthRepositoryTest` (дополнить): `connectGoogleCalendar` сохраняет аккаунт и токен;
`getAccessToken(GOOGLE)` делегирует в токены; `restoreTokens` поднимает оба токена и стирает
осиротевший Google-файл; `signOut(GOOGLE)` не трогает Яндекс; `connectedProviders`
содержит Google после входа.

`SettingsScreenUiTest` (дополнить): карточка Google в трёх состояниях (не подключён,
не настроен, подключён), ошибка входа, неактивная кнопка при `googleSignInEnabled = false`.

`DesktopRemoteDataSourceProviderTest` (дополнить): `GOOGLE` → google-источник,
`LOCAL` → `null`.

## 8. Безопасность

- loopback-сервер слушает только `127.0.0.1`, живёт не дольше одного входа и
  закрывается в `finally`; `state` сверяется, чужой callback отбрасывается;
- токены на диске — только под DPAPI; в `account.json`, `settings.json`, логах и
  сообщениях об ошибках их нет;
- `client_secret` — только в `local.properties` (вне git) и в JVM-свойствах сборки;
  в коде, тестах, документации и чате его нет; тесты используют подставные значения;
- письма участникам не рассылаются (`sendUpdates=none`); участников не отправляем.

## 9. Честные ограничения (в README)

- Testing consent screen: refresh-токен на 7 дней, потом повторный вход;
  публикация с верификацией scope снимает ограничение;
- только основной календарь; выбор календарей, цвета и участники — не поддерживаются;
- синхронизация ручная, без фона; удаления — по tombstone'ам Google;
- инкрементального `syncToken` нет: каждая синхронизация читает коллекцию целиком;
- события без часового пояса трактуются как плавающие в локальном времени;
- умолчания напоминаний Google не импортируются: после первого push событие
  получает явные overrides (или пустой список).

## 10. Как проверять живьём

Предпосылка — OAuth-клиент из §2 и вход в свой Google-аккаунт (это делает человек;
автоматизировать вход агент не может и не должен).

1. Настройки → Календари → Google → «Войти»: открывается браузер, после согласия
   вкладка сообщает об успехе; в карточке — «Подключено: <email>».
2. «Синхронизировать»: события основного календаря появляются в Windows-клиенте
   с точками провайдера; статус — «Готово: отправлено …, получено …».
3. Создать событие в Windows-клиенте, синхронизировать — оно видно в
   calendar.google.com; правка и удаление там же отражаются после синхронизации.
4. Удалить событие в Google → синхронизация → локально оно исчезло; при
   неотправленной локальной правке — конфликт, а не молчаливая потеря.
5. Свернуть/перезапустить клиент: аккаунт и токен живы, синхронизация работает
   без повторного входа; «Выйти» у Google не трогает Яндекс.
6. Негативные: отмена в браузере, недоступный браузер, нет `client_id` в сборке,
   просроченный refresh (Testing-неделя) — понятные сообщения, Яндекс продолжает работать.

Живой прогон выполняется пользователем на его аккаунте; результаты (что видели,
что нет) фиксируются в README/`docs/how-to-verify.md` без токенов и кодов.

## 11. Совместимость

- формат `events.json`, `settings.json`, `account.json` не меняется; добавляется
  только новый файл `google-token.bin`;
- `collectionUrl` Google — `"google:primary"` в существующем поле провайдера;
- Android не затрагивается ни в поведении, ни в схеме;
- установленный Windows-клиент без `client_id` работает как раньше (Яндекс),
  Google неактивен с подсказкой.
