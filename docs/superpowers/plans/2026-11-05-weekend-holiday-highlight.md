# Тонирование выходных и праздничных дней — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Карточки выходных и праздничных дней получают собственный тон поверхности и форму-признак — во всех трёх видах календаря.

**Architecture:** Домен отдаёт метку дня по чистой таблице твёрдых дат (`HolidayRegistry`), ViewModel кладёт карту меток в состояние экрана, три композабла (сетка месяца, карточка недели, шапка дня) читают тон из палитры через один хелпер. Композаблы не обращаются к домену напрямую — иначе любой тест вида требовал бы Hilt.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, Hilt, JUnit 4 (JVM), Compose UI Test (androidTest), Gradle.

**Spec:** [docs/superpowers/specs/2026-11-05-weekend-holiday-highlight-design.md](../specs/2026-11-05-weekend-holiday-highlight-design.md)

## Global Constraints

- Всё, что читает пользователь (строки, комментарии, имена коммитов), — по-русски; эмодзи в новых строках и комментариях не использовать.
- Числа в UI живут только в `presentation/theme/Tokens.kt`; экраны и компоненты обязаны брать значения оттуда.
- Контраст текста — WCAG AA 4.5, графики — 3.0. Тест `PaletteContrastTest` считает relative luminance по формуле sRGB (порог 0.03928, степень 2.4).
- Тёмная схема — эталон, светлая — производная от неё по тем же ролям.
- Material You (динамические цвета обоев) возвращать нельзя: палитра общая с будущей Windows-версией.
- Новых зависимостей в `build.gradle.kts` не появляется; `minSdk = 26`, `compileSdk`/`targetSdk` = 37.
- `java.time` компилируется против `android.jar` (libcore), а не JDK: у `LocalDate` там нет публичного
  `getMonthDay()`, поэтому Kotlin-свойство `date.monthDay` не собирается. Ключ таблицы берут
  `MonthDay.from(date)`; любое свойство `java.time` из листинга проверять на присутствие в android.jar.
- Connected-тесты идут по API `androidx.compose.ui:ui-test` 1.12.1: матчер тега называется `hasTestTag`
  (матчера `hasTag` в артефакте нет), `assertExists()` и `assertCountEquals()` — методы узла без отдельного
  импорта, а потомки `clickable`-узла сливаются в него и видны только с `useUnmergedTree = true`.
- `versionCode = 5` и `versionName = "1.1.3"` не трогать; `applicationId` не менять — его смена отдельное изменение.
- Тесты гонять только через `./gradlew` из корня проекта; одновременно — один прогон Gradle, второй запускать только после завершения первого.
- Коммит каждой задачи — только с явного разрешения пользователя, данного в этот момент; текст коммита указан в шаге. Пуш и теги — отдельные разрешения, в этот план не входят.
- Состав предупреждений линта не должен расти: было 11, остаётся 11.
- Текущие счётчики дерева, от которых считаем прирост: JVM 101 тест / 0 падений, линт 0 ошибок / 11 предупреждений, connected 55 тестов / 0 падений.

## Уточнения к спеке

Спека аргументирована, но в девяти местах её буквы расходятся с деревом или молчат. План решает так; ревью сверяется с этим списком, а не с догадками.

1. **`AppSizes.holidayMarker` не появляется.** §9 требует новый токен `holidayMarker = 6.dp`, §4 говорит «точка-маркер (`AppSizes.dot`)». Значения совпадают, а финальное ревью визуального языка как раз вычищало дубли токенов. Маркер берёт `AppSizes.dot`.
2. **Диапазон меток — сетка, а не `rangeMillis()`.** §6 считает `marksBetween` по диапазону загрузки событий; в виде «Месяц» `rangeMillis()` охватывает только календарный месяц, а сетка показывает до 42 клеток, включая дни соседних месяцев — они остались бы без тона. Добавляется `CalendarUiState.gridBounds()`: понедельник первой клетки … последняя клетка. `rangeMillis()` не меняется — он про Room.
3. **`holidayTitles` вместо обращения к реестру из композабла.** Шапка дня в виде «День» показывает название праздника, но компонент не должен зависеть от домена (требование §6). ViewModel кладёт рядом `holidayTitles: Map<LocalDate, Int>` — ресурсные id, собранные из `holidayTitleRes` по ключам `dayMarks`.
4. **Теги с датой и меткой.** §8 требует `testTag("week_day_card")`. Голый тег не отличает тонированную карточку от обычной без проверки цвета пикселем, а проверка цвета в моках прямо запрещена той же секцией. Формат — `week_day_card_2026-05-09.HOLIDAY`, суффикс `PLAIN`, если метки нет. То же для клетки месяца (`month_cell_…`) и шапки дня (`day_header_…`).
5. **Маркер не рисуется на выбранной клетке.** Выбранная клетка залита `primary`, тон под ней не виден (§5, намеренно), поэтому маркер под заливкой бесполезен. Тест не ждёт `holiday_marker` на выбранной клетке.
6. **Название праздника — только в виде «День».** `DayHeader` вызывается и в месяце ([CalendarScreen.kt:270](../../../app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt#L270)), поэтому месяц передаёт `holidayTitle = null`, тон при этом получает.
7. **`stateIn` с `map`, а не `combine`.** §6 упоминает «тот же `combine`»; в ViewModel потока `combine` нет — состояние одно (`_uiState`). Метки считаются `map`-ом по `_uiState` и отдаются через `stateIn(SharingStarted.Eagerly, …)`, чтобы значение было готово до первой подписки.
8. **`ThemePaletteTest` не меняется.** §8 просит проверить новые роли «как в существующих ассертах», но новые тона не входят в `ColorScheme` (роль `surfaceVariant` занята карточками), поэтому проверять состав схемы нечего. Проверка соответствия палитре перенесена в JVM-тест `DayMarkToneTest` — дешёвый гейт без прогона на устройстве.
9. **Форма-признак в виде «День» не нужна.** §4: 1.4.1 закрывается названием праздника; в шапке дня день недели написан словом («суббота, 9 мая»), так что выходной различим текстом. Маркер — в сетке месяца и в карточке недели.

## Файлы и ответственность

| Файл | Ответственность | Действие |
| --- | --- | --- |
| `app/src/main/java/com/example/mycalendar/domain/holiday/DayMark.kt` | перечисление меток дня | создать (Task 1) |
| `…/domain/holiday/HolidayRegistry.kt` | таблица твёрдых дат + правило выходного | создать (Task 1) |
| `app/src/main/res/values/strings.xml` | 13 названий праздников | дополнить (Task 1) |
| `app/src/test/java/com/example/mycalendar/domain/holiday/HolidayRegistryTest.kt` | таблица и приоритеты | создать (Task 1) |
| `…/presentation/theme/Palette.kt` | 4 hex-значения тонов дня | дополнить (Task 2) |
| `…/presentation/theme/Theme.kt` | `dayMarkContainer` (чистая) + `dayMarkColor` (composable) | дополнить (Task 2) |
| `app/src/test/java/com/example/mycalendar/PaletteContrastTest.kt` | контраст тонов | дополнить (Task 2) |
| `app/src/test/java/com/example/mycalendar/DayMarkToneTest.kt` | тон == значение палитры | создать (Task 2) |
| `…/presentation/calendar/MonthGrid.kt` | `monthWeeks`, вынесенная с экрана | создать (Task 3) |
| `…/presentation/calendar/CalendarViewModel.kt` | `gridBounds()`, `dayMarks`, `holidayTitles` | дополнить (Task 3) |
| `app/src/test/java/com/example/mycalendar/presentation/calendar/CalendarUiStateTest.kt` | границы сетки/недели/дня | создать (Task 3) |
| `…/presentation/theme/Tokens.kt` | `gridDot`, `detailsDot`, `markInset` | дополнить (Task 4) |
| `…/presentation/components/DayMarkUi.kt` | `HolidayMarker`, константы тегов, `dayMarkTag` | создать (Task 4) |
| `…/presentation/components/EventListItem.kt` | `ProviderDot` с обводкой | дополнить (Task 4) |
| `…/components/ProviderChip.kt`, `…/event/EventDetailsScreen.kt`, `CalendarScreen.kt` | потребители точки | правка (Task 4) |
| `CalendarScreen.kt` (`MonthView`, стрелки периодов) | тон клетки + маркер + теги навигации | правка (Task 5) |
| `…/components/WeekColumn.kt` + `CalendarScreen.kt` (`WeekView`) | тон карточки + маркер + тег | правка (Task 6) |
| `…/components/DayHeader.kt` + `CalendarScreen.kt` (`DayView`) | тон шапки + строка названия | правка (Task 7) |
| `app/src/androidTest/java/com/example/mycalendar/DayViewHolidayUiTest.kt` | месяц и день на живом экране | создать (Tasks 5, 7) |
| `…/WeekViewCardsUiTest.kt` | тон карточки недели | дополнить (Task 6) |
| `README.md`, `docs/how-to-verify.md` | счётчики прогонов | правка (Task 8) |

---

### Task 1: Домен меток дня и строки названий

**Files:**
- Create: `app/src/main/java/com/example/mycalendar/domain/holiday/DayMark.kt`
- Create: `app/src/main/java/com/example/mycalendar/domain/holiday/HolidayRegistry.kt`
- Create: `app/src/test/java/com/example/mycalendar/domain/holiday/HolidayRegistryTest.kt`
- Modify: `app/src/main/res/values/strings.xml` (блок «Календарь», после строки `new_event` — строка 19)

**Interfaces:**
- Consumes: ничего (первая задача).
- Produces: `enum class DayMark { WEEKEND, HOLIDAY }`; `class HolidayRegistry @Inject constructor()` с методами `markFor(date: LocalDate): DayMark?`, `holidayTitleRes(date: LocalDate): Int?`, `marksBetween(from: LocalDate, to: LocalDate): Map<LocalDate, DayMark>`; ресурсные id `R.string.holiday_*` — 13 штук.

- [ ] **Step 1: Добавить строки названий**

В `app/src/main/res/values/strings.xml`, сразу после `<string name="new_event">Новое событие</string>`:

```xml
    <!-- Названия праздников: вторая строка шапки дня (спека, §2) -->
    <string name="holiday_new_year_holidays">Новогодние каникулы</string>
    <string name="holiday_christmas_orthodox">Рождество</string>
    <string name="holiday_defender_of_fatherland">День защитника Отечества</string>
    <string name="holiday_womens_day">Международный женский день</string>
    <string name="holiday_spring_labor">Праздник Весны и Труда</string>
    <string name="holiday_victory_day">День Победы</string>
    <string name="holiday_russia_day">День России</string>
    <string name="holiday_national_unity">День народного единства</string>
    <string name="holiday_valentines">День святого Валентина</string>
    <string name="holiday_childrens_day">День защиты детей</string>
    <string name="holiday_halloween">Хеллоуин</string>
    <string name="holiday_christmas_west">Рождество</string>
    <string name="holiday_new_year_eve">Канун Нового года</string>
```

Тексты — дословно из таблицы §2 спеки. `holiday_christmas_west` совпадает по тексту с `holiday_christmas_orthodox`: это разные даты, и слияние строк потом помешало бы правке любой из них по отдельности.

- [ ] **Step 2: Написать падающий тест**

`app/src/test/java/com/example/mycalendar/domain/holiday/HolidayRegistryTest.kt`. Декабрь 2026 начинается во вторник, поэтому выходных в нём восемь (5, 6, 12, 13, 19, 20, 26, 27) и два праздника (25 декабря — пятница, 31 декабря — четверг): всего десять помеченных дней.

```kotlin
package com.example.mycalendar.domain.holiday

import com.example.mycalendar.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Таблица твёрдых дат и приоритет меток. Год в ключе не участвует, поэтому високосное
 * 29 февраля обязано вести себя как обычный день.
 */
class HolidayRegistryTest {

    private val registry = HolidayRegistry()

    @Test
    fun `holiday beats weekend on the same date`() {
        // 9 мая 2026 — суббота: тон один, и это тон праздника
        assertEquals(DayMark.HOLIDAY, registry.markFor(LocalDate.of(2026, 5, 9)))
        assertEquals(R.string.holiday_victory_day, registry.holidayTitleRes(LocalDate.of(2026, 5, 9)))
    }

    @Test
    fun `weekend days are marked`() {
        // 7 и 8 ноября 2026 — суббота и воскресенье без праздника
        assertEquals(DayMark.WEEKEND, registry.markFor(LocalDate.of(2026, 11, 7)))
        assertEquals(DayMark.WEEKEND, registry.markFor(LocalDate.of(2026, 11, 8)))
        assertNull(registry.holidayTitleRes(LocalDate.of(2026, 11, 7)))
    }

    @Test
    fun `plain weekday has no mark`() {
        // 15 сентября 2026 — вторник вне таблицы
        assertNull(registry.markFor(LocalDate.of(2026, 9, 15)))
    }

    @Test
    fun `every fixed holiday date is in the table`() {
        val dates = listOf(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 7),
            LocalDate.of(2026, 2, 14), LocalDate.of(2026, 2, 23), LocalDate.of(2026, 3, 8),
            LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 9), LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 12), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 4),
            LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 31),
        )
        dates.forEach { date ->
            assertEquals("нет метки у $date", DayMark.HOLIDAY, registry.markFor(date))
            assertNotNull("нет названия у $date", registry.holidayTitleRes(date))
        }
    }

    @Test
    fun `leap day is not a holiday and does not throw`() {
        // 29 февраля 2028 — вторник; в таблице его нет, и поиск по MonthDay безопасен
        assertNull(registry.markFor(LocalDate.of(2028, 2, 29)))
    }

    @Test
    fun `marksBetween covers the whole month`() {
        val marks = registry.marksBetween(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31))
        assertEquals(DayMark.HOLIDAY, marks[LocalDate.of(2026, 12, 25)])
        assertEquals(DayMark.HOLIDAY, marks[LocalDate.of(2026, 12, 31)])
        assertEquals(DayMark.WEEKEND, marks[LocalDate.of(2026, 12, 5)])
        assertNull(marks[LocalDate.of(2026, 12, 3)])
        assertEquals("в карте только помеченные дни", 10, marks.size)
    }
}
```

- [ ] **Step 3: Прогнать тест, убедиться, что он падает**

Run: `./gradlew :app:testDebugUnitTest --tests '*HolidayRegistryTest'`
Expected: FAIL — «Unresolved reference: HolidayRegistry», компилировать тест нечем.

- [ ] **Step 4: Написать реестр**

`app/src/main/java/com/example/mycalendar/domain/holiday/DayMark.kt`:

```kotlin
package com.example.mycalendar.domain.holiday

/**
 * Метка дня. Праздник важнее выходного: тон один, и выбирается тон праздника.
 * У обычного буднего дня метки нет.
 */
enum class DayMark { WEEKEND, HOLIDAY }
```

`app/src/main/java/com/example/mycalendar/domain/holiday/HolidayRegistry.kt`:

```kotlin
package com.example.mycalendar.domain.holiday

import com.example.mycalendar.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import javax.inject.Inject

/**
 * Твёрдые даты праздников и правило выходного дня.
 *
 * Ключ таблицы — (месяц, день): год не участвует, поэтому високосное 29 февраля просто
 * отсутствует в наборе и не требует специальной ветки. Название — id строки, потому что
 * текст, который видит пользователь, остаётся ресурсом (спека, §2): домен знает id, но не
 * локализацию.
 *
 * Вне набора сознательно: переносы праздников, пасхальный круг и лунные даты (спека, §1).
 */
class HolidayRegistry @Inject constructor() {

    /** HOLIDAY важнее WEEKEND: 9 мая, выпавшее на субботу, остаётся праздником. */
    fun markFor(date: LocalDate): DayMark? {
        if (monthDayOf(date) in HOLIDAYS) return DayMark.HOLIDAY
        val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        return if (isWeekend) DayMark.WEEKEND else null
    }

    /** Ресурс названия праздника, или null, если день не праздничный. */
    fun holidayTitleRes(date: LocalDate): Int? = HOLIDAYS[monthDayOf(date)]

    /**
     * Метки всех дней диапазона включительно. Ключи есть только у помеченных дней, поэтому
     * карта остаётся маленькой: за месяц — не больше сорока двух записей.
     */
    fun marksBetween(from: LocalDate, to: LocalDate): Map<LocalDate, DayMark> {
        val marks = LinkedHashMap<LocalDate, DayMark>()
        var day = from
        while (!day.isAfter(to)) {
            markFor(day)?.let { marks[day] = it }
            day = day.plusDays(1)
        }
        return marks
    }

    /**
     * Ключ таблицы: у java.time.LocalDate в android.jar (libcore) нет публичного
     * getMonthDay(), поэтому берём MonthDay.from — это ровно то, что делает JDK-версия.
     */
    private fun monthDayOf(date: LocalDate): MonthDay = MonthDay.from(date)

    private companion object {
        val HOLIDAYS: Map<MonthDay, Int> = mapOf(
            MonthDay.of(1, 1) to R.string.holiday_new_year_holidays,
            MonthDay.of(1, 2) to R.string.holiday_new_year_holidays,
            MonthDay.of(1, 3) to R.string.holiday_new_year_holidays,
            MonthDay.of(1, 4) to R.string.holiday_new_year_holidays,
            MonthDay.of(1, 5) to R.string.holiday_new_year_holidays,
            MonthDay.of(1, 6) to R.string.holiday_new_year_holidays,
            MonthDay.of(1, 7) to R.string.holiday_christmas_orthodox,
            MonthDay.of(2, 14) to R.string.holiday_valentines,
            MonthDay.of(2, 23) to R.string.holiday_defender_of_fatherland,
            MonthDay.of(3, 8) to R.string.holiday_womens_day,
            MonthDay.of(5, 1) to R.string.holiday_spring_labor,
            MonthDay.of(5, 9) to R.string.holiday_victory_day,
            MonthDay.of(6, 1) to R.string.holiday_childrens_day,
            MonthDay.of(6, 12) to R.string.holiday_russia_day,
            MonthDay.of(10, 31) to R.string.holiday_halloween,
            MonthDay.of(11, 4) to R.string.holiday_national_unity,
            MonthDay.of(12, 25) to R.string.holiday_christmas_west,
            MonthDay.of(12, 31) to R.string.holiday_new_year_eve,
        )
    }
}
```

- [ ] **Step 5: Прогнать тест, убедиться, что он проходит**

Run: `./gradlew :app:testDebugUnitTest --tests '*HolidayRegistryTest'`
Expected: PASS, 6 тестов.

Если `R.string.*` в JVM-тесте читается как `0` (в unit-классе нет Android-ресурсов), заменить сверку id на `assertNotNull` в тесте `holiday beats weekend on the same date`; точное название тогда проверяет connected-тест Task 7. Это единственная допустимая замена: факт метки она не ослабляет.

- [ ] **Step 6: Полный JVM-прогон**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 107 тестов (было 101, добавилось 6), 0 падений.

- [ ] **Step 7: Коммит**

Только с явного разрешения пользователя.

```bash
git add app/src/main/java/com/example/mycalendar/domain/holiday app/src/test/java/com/example/mycalendar/domain/holiday/HolidayRegistryTest.kt app/src/main/res/values/strings.xml
git commit -m "feat(holidays): реестр праздничных и выходных дней со строками названий"
```

---

### Task 2: Тона дня в палитре и проверка контраста

**Files:**
- Modify: `app/src/main/java/com/example/mycalendar/presentation/theme/Palette.kt` (между блоком светлой схемы и блоком провайдеров — строка 45)
- Modify: `app/src/main/java/com/example/mycalendar/presentation/theme/Theme.kt` (после `LocalIsDarkTheme`, строка 56)
- Modify: `app/src/test/java/com/example/mycalendar/PaletteContrastTest.kt`
- Create: `app/src/test/java/com/example/mycalendar/DayMarkToneTest.kt`

**Interfaces:**
- Consumes: `DayMark` (Task 1), `LocalIsDarkTheme` (существует).
- Produces: `val DarkDayWeekendContainer`, `val DarkDayHolidayContainer`, `val LightDayWeekendContainer`, `val LightDayHolidayContainer`; `fun dayMarkContainer(mark: DayMark, dark: Boolean): Color` — обычная функция, её видно из JVM-теста; `@Composable fun dayMarkColor(mark: DayMark?): Color?` — для `null` возвращает `null`, чтобы потребитель писал `dayMarkColor(mark) ?: colorScheme.surface`.

- [ ] **Step 1: Падающие тесты**

`app/src/test/java/com/example/mycalendar/DayMarkToneTest.kt`:

```kotlin
package com.example.mycalendar

import com.example.mycalendar.domain.holiday.DayMark
import com.example.mycalendar.presentation.theme.DarkDayHolidayContainer
import com.example.mycalendar.presentation.theme.DarkDayWeekendContainer
import com.example.mycalendar.presentation.theme.LightDayHolidayContainer
import com.example.mycalendar.presentation.theme.LightDayWeekendContainer
import com.example.mycalendar.presentation.theme.dayMarkContainer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тон дня собирается прямо из палитры: ни одного цвета, заданного по месту. Это то окно,
 * из которого Windows-версия копирует hex-значения.
 */
class DayMarkToneTest {

    @Test
    fun `weekend tone comes from the palette`() {
        assertEquals(DarkDayWeekendContainer, dayMarkContainer(DayMark.WEEKEND, dark = true))
        assertEquals(LightDayWeekendContainer, dayMarkContainer(DayMark.WEEKEND, dark = false))
    }

    @Test
    fun `holiday tone comes from the palette`() {
        assertEquals(DarkDayHolidayContainer, dayMarkContainer(DayMark.HOLIDAY, dark = true))
        assertEquals(LightDayHolidayContainer, dayMarkContainer(DayMark.HOLIDAY, dark = false))
    }
}
```

В `PaletteContrastTest.kt` добавить три теста и импорты `com.example.mycalendar.domain.holiday.DayMark`, `com.example.mycalendar.presentation.theme.dayMarkContainer`. В таблице §4 спеки четыре текстовые пары (по две схемы на каждую) и две графические:

```kotlin
    @Test
    fun `weekend tone keeps day text at AA`() {
        assertAtLeast(AA, DarkOnSurface, dayMarkContainer(DayMark.WEEKEND, dark = true))
        assertAtLeast(AA, LightOnSurface, dayMarkContainer(DayMark.WEEKEND, dark = false))
        assertAtLeast(AA, DarkOnSurfaceVariant, dayMarkContainer(DayMark.WEEKEND, dark = true))
        assertAtLeast(AA, LightOnSurfaceVariant, dayMarkContainer(DayMark.WEEKEND, dark = false))
    }

    @Test
    fun `holiday tone keeps day text at AA`() {
        assertAtLeast(AA, DarkOnSurface, dayMarkContainer(DayMark.HOLIDAY, dark = true))
        assertAtLeast(AA, LightOnSurface, dayMarkContainer(DayMark.HOLIDAY, dark = false))
        assertAtLeast(AA, DarkOnSurfaceVariant, dayMarkContainer(DayMark.HOLIDAY, dark = true))
        assertAtLeast(AA, LightOnSurfaceVariant, dayMarkContainer(DayMark.HOLIDAY, dark = false))
    }

    @Test
    fun `today outline on a tinted day meets graphics contrast`() {
        // Рамка «сегодня» — графика, у неё порог 3.0. Она же эталон для обводки точки
        // провайдера: onSurfaceVariant на обоих тонах даёт 4.7–5.7 и вписывается в AA.
        assertAtLeast(GRAPHICS, DarkPrimary, dayMarkContainer(DayMark.WEEKEND, dark = true))
        assertAtLeast(GRAPHICS, LightPrimary, dayMarkContainer(DayMark.WEEKEND, dark = false))
        assertAtLeast(GRAPHICS, DarkPrimary, dayMarkContainer(DayMark.HOLIDAY, dark = true))
        assertAtLeast(GRAPHICS, LightPrimary, dayMarkContainer(DayMark.HOLIDAY, dark = false))
    }
```

- [ ] **Step 2: Прогнать, убедиться, что тесты падают**

Run: `./gradlew :app:testDebugUnitTest --tests '*DayMarkToneTest' --tests '*PaletteContrastTest'`
Expected: FAIL — «Unresolved reference: dayMarkContainer».

- [ ] **Step 3: Значения палитры**

В `Palette.kt`, между блоком светлой схемы и блоком провайдеров:

```kotlin
// ---------- Маркеры дня: тон карточки выходного и праздника ----------
// Различаются только оттенком (бирюзовый против янтарного): по яркости разница
// 1.06–1.11, поэтому у праздника есть ещё и форма-маркер — WCAG 1.4.1.

val DarkDayWeekendContainer = Color(0xFF173F52)
val DarkDayHolidayContainer = Color(0xFF4A2E10)
val LightDayWeekendContainer = Color(0xFFB9DCC9)
val LightDayHolidayContainer = Color(0xFFF3D6A2)
```

- [ ] **Step 4: Хелперы в `Theme.kt`**

Добавить импорты `androidx.compose.ui.graphics.Color`, `com.example.mycalendar.domain.holiday.DayMark` и после `val LocalIsDarkTheme = staticCompositionLocalOf { false }`:

```kotlin
/**
 * Тон поверхности дня по метке. Обычная функция, не composable: тест контраста и
 * Windows-версия собирают цвета без CompositionLocal.
 */
fun dayMarkContainer(mark: DayMark, dark: Boolean): Color = when (mark) {
    DayMark.WEEKEND -> if (dark) DarkDayWeekendContainer else LightDayWeekendContainer
    DayMark.HOLIDAY -> if (dark) DarkDayHolidayContainer else LightDayHolidayContainer
}

/**
 * Тон дня для текущей схемы. Нет метки — нет и тона: вызывающий компонент оставляет
 * прежний фон (`?: colorScheme.surface`), поэтому поведение не меняется, пока реестр молчит.
 */
@Composable
fun dayMarkColor(mark: DayMark?): Color? =
    mark?.let { dayMarkContainer(it, LocalIsDarkTheme.current) }
```

- [ ] **Step 5: Прогнать тесты**

Run: `./gradlew :app:testDebugUnitTest --tests '*DayMarkToneTest' --tests '*PaletteContrastTest'`
Expected: PASS — 2 теста `DayMarkToneTest` и 7 тестов `PaletteContrastTest` (было 4, добавилось 3).

Числа, которые должны получиться (независимый пересчёт по формуле WCAG уже выполнен): 9.64 и 11.64 (`onSurface` на выходном), 10.67 и 12.31 (`onSurface` на празднике), 5.11 и 4.71 (`onSurfaceVariant` на выходном), 5.66 и 4.98 (`onSurfaceVariant` на празднике), 3.58 и 3.57 (рамка на выходном), 3.97 и 3.78 (рамка на празднике). Если фактический замер в тесте отличается от этих чисел — менять не тест, а значение: спека фиксирует hex.

- [ ] **Step 6: Коммит**

Только с явного разрешения пользователя.

```bash
git add app/src/main/java/com/example/mycalendar/presentation/theme/Palette.kt app/src/main/java/com/example/mycalendar/presentation/theme/Theme.kt app/src/test/java/com/example/mycalendar/DayMarkToneTest.kt app/src/test/java/com/example/mycalendar/PaletteContrastTest.kt
git commit -m "feat(theme): тона выходного и праздника с проверкой контраста по WCAG"
```

---

### Task 3: Метки дня в состоянии экрана

**Files:**
- Create: `app/src/main/java/com/example/mycalendar/presentation/calendar/MonthGrid.kt`
- Modify: `app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt` (удалить приватную `monthWeeks` — строки 406–419)
- Modify: `app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarViewModel.kt`
- Create: `app/src/test/java/com/example/mycalendar/presentation/calendar/CalendarUiStateTest.kt`

**Interfaces:**
- Consumes: `HolidayRegistry` (Task 1).
- Produces: `internal fun monthWeeks(month: LocalDate): List<List<LocalDate>>` в `MonthGrid.kt`; `CalendarUiState.dayMarks: Map<LocalDate, DayMark>` и `.holidayTitles: Map<LocalDate, Int>`; `CalendarUiState.gridBounds(): Pair<LocalDate, LocalDate>`; `CalendarViewModel.uiState: StateFlow<CalendarUiState>` — тот же тип, теперь с заполненными картами.

- [ ] **Step 1: Вынести сетку месяца**

`app/src/main/java/com/example/mycalendar/presentation/calendar/MonthGrid.kt` — тело переносится из `CalendarScreen.kt` (строки 406–419: комментарий «Сетка месяца…», `private fun monthWeeks`) без изменений логики; меняется только видимость:

```kotlin
package com.example.mycalendar.presentation.calendar

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Сетка месяца: недели Пн–Вс, включая дни соседних месяцев, которые попадают в первую
 * и последнюю неделю. Единственный источник числа клеток — от него зависит и диапазон
 * меток дня ([CalendarUiState.gridBounds]).
 */
internal fun monthWeeks(month: LocalDate): List<List<LocalDate>> {
    val first = month.withDayOfMonth(1)
    val firstCell = first.with(DayOfWeek.MONDAY)
    val daysInMonth = month.lengthOfMonth()
    // Число клеток, кратное 7: захватываем хвост соседнего месяца
    val totalCells = ((first.dayOfWeek.value - 1) + daysInMonth)
        .let { used -> ((used + 6) / 7) * 7 }
    return (0 until totalCells).chunked(7).map { week ->
        week.map { index -> firstCell.plusDays(index.toLong()) }
    }
}
```

В `CalendarScreen.kt` удалить это приватное определение. `import java.time.DayOfWeek` остаётся (его используют `headerTitle` и `WeekView`).

- [ ] **Step 2: Падающий тест на границы**

`app/src/test/java/com/example/mycalendar/presentation/calendar/CalendarUiStateTest.kt`:

```kotlin
package com.example.mycalendar.presentation.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Диапазон меток дня — это то, что реально показывает сетка, а не то, что загружено из
 * Room: у месяца шесть недель, и дни соседних месяцев тоже обязаны получить тон.
 */
class CalendarUiStateTest {

    @Test
    fun `month bounds cover the whole grid`() {
        val state = CalendarUiState(
            viewMode = CalendarViewMode.MONTH,
            selectedDate = LocalDate.of(2026, 11, 5),
            visibleMonth = LocalDate.of(2026, 11, 1),
        )
        // Ноябрь 2026 начинается в воскресенье: первая клетка — 26 октября, последняя — 6 декабря
        assertEquals(LocalDate.of(2026, 10, 26) to LocalDate.of(2026, 12, 6), state.gridBounds())
    }

    @Test
    fun `month bounds keep the selected day inside`() {
        val state = CalendarUiState(
            viewMode = CalendarViewMode.MONTH,
            selectedDate = LocalDate.of(2026, 12, 25),
            visibleMonth = LocalDate.of(2026, 11, 1),
        )
        // Выбор мог остаться в другом месяце: шапка дня под сеткой обязана попасть в диапазон
        assertEquals(LocalDate.of(2026, 10, 26) to LocalDate.of(2026, 12, 25), state.gridBounds())
    }

    @Test
    fun `week bounds are monday through sunday`() {
        val state = CalendarUiState(
            viewMode = CalendarViewMode.WEEK,
            selectedDate = LocalDate.of(2026, 11, 5),
        )
        assertEquals(LocalDate.of(2026, 11, 2) to LocalDate.of(2026, 11, 8), state.gridBounds())
    }

    @Test
    fun `day bounds are the single selected day`() {
        val state = CalendarUiState(
            viewMode = CalendarViewMode.DAY,
            selectedDate = LocalDate.of(2026, 5, 9),
        )
        assertEquals(LocalDate.of(2026, 5, 9) to LocalDate.of(2026, 5, 9), state.gridBounds())
    }
}
```

- [ ] **Step 3: Прогнать, убедиться, что тест падает**

Run: `./gradlew :app:testDebugUnitTest --tests '*CalendarUiStateTest'`
Expected: FAIL — «Unresolved reference: gridBounds».

- [ ] **Step 4: Состояние и ViewModel**

В `CalendarViewModel.kt` добавить импорты `com.example.mycalendar.domain.holiday.DayMark`, `com.example.mycalendar.domain.holiday.HolidayRegistry`, `kotlinx.coroutines.flow.map`. В `data class CalendarUiState` добавить параметры (после `visibleMonth`):

```kotlin
data class CalendarUiState(
    val viewMode: CalendarViewMode = CalendarViewMode.MONTH,
    /** Выбранный день (для списка событий под сеткой и видов Week/Day). */
    val selectedDate: LocalDate = LocalDate.now(),
    /** Отображаемый месяц в виде Месяц. */
    val visibleMonth: LocalDate = LocalDate.now(),
    /** Метки дней видимого диапазона: тон клетки, карточки и шапки. Пусто — без тона. */
    val dayMarks: Map<LocalDate, DayMark> = emptyMap(),
    /** Ресурсы названий праздников по тем же датам; заполнены только для DayMark.HOLIDAY. */
    val holidayTitles: Map<LocalDate, Int> = emptyMap(),
) {
```

В тот же класс, после `rangeMillis()`:

```kotlin
    /**
     * Диапазон дней, которые показывает сетка. Не путать с [rangeMillis]: тот задаёт,
     * что грузить из Room, и у месяца ограничен календарным месяцем, а клеток сетки —
     * до сорока двух, и тон обязаны получить тоже дни соседних месяцев.
     */
    fun gridBounds(): Pair<LocalDate, LocalDate> = when (viewMode) {
        CalendarViewMode.MONTH -> {
            val weeks = monthWeeks(visibleMonth)
            val start = weeks.first().first()
            val end = weeks.last().last()
            // Выбранный день мог остаться в другом месяце: шапка под сеткой всё равно
            // обязана получить свой тон, поэтому диапазон накрывает и его.
            minOf(start, selectedDate) to maxOf(end, selectedDate)
        }

        CalendarViewMode.WEEK -> {
            val monday = selectedDate.with(DayOfWeek.MONDAY)
            monday to monday.plusDays(6)
        }

        CalendarViewMode.DAY -> selectedDate to selectedDate
    }
```

В `CalendarViewModel` — новый параметр конструктора и производный поток вместо `asStateFlow`:

```kotlin
@HiltViewModel
class CalendarViewModel @Inject constructor(
    observeEventsBetween: ObserveEventsBetweenUseCase,
    private val expandOccurrences: ExpandOccurrencesUseCase,
    private val holidayRegistry: HolidayRegistry,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())

    /**
     * Состояние с метками дня. Считается map-ом по `_uiState`: вход здесь только даты
     * видимого диапазона, второго потока нет. Eagerly — чтобы значение было готово до
     * первой подписки. Обычный объём — клетки видимой сетки (до сорока двух); когда выбранный
     * день остался в другом месяце, диапазон накрывает и его, и карта растёт до этого разрыва.
     */
    val uiState: StateFlow<CalendarUiState> = _uiState
        .map { state ->
            val (from, to) = state.gridBounds()
            val marks = holidayRegistry.marksBetween(from, to)
            val titles = marks
                .filterValues { it == DayMark.HOLIDAY }
                .mapNotNull { (date, _) -> holidayRegistry.holidayTitleRes(date)?.let { date to it } }
                .toMap()
            state.copy(dayMarks = marks, holidayTitles = titles)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CalendarUiState())
```

Старое определение `val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()` удалить; импорт `kotlinx.coroutines.flow.asStateFlow` удалить, если он больше не используется. `HolidayRegistry` строить в DI-модуле не нужно: у него `@Inject constructor()`, Hilt соберёт сам. Остальные методы (`setViewMode`, `selectDate`, `goToday`, `goPrevious`, `goNext`, `eventsOn`) не меняются — они правят `_uiState`, а метки пересчитываются в производном потоке.

- [ ] **Step 5: Прогнать тесты и сборку**

Run: `./gradlew :app:testDebugUnitTest --tests '*CalendarUiStateTest'`
Expected: PASS, 4 теста.

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL — `CalendarScreen.kt` новые поля ещё не читает, компиляция ломаться не должна.

- [ ] **Step 6: Коммит**

Только с явного разрешения пользователя.

```bash
git add app/src/main/java/com/example/mycalendar/presentation/calendar app/src/test/java/com/example/mycalendar/presentation/calendar
git commit -m "feat(calendar): метки дня в состоянии экрана по диапазону сетки"
```

---

### Task 4: Общая точка провайдера, маркер праздника и токены

**Files:**
- Modify: `app/src/main/java/com/example/mycalendar/presentation/theme/Tokens.kt` (`AppSpacing` — строка 37, `AppSizes` — строка 49)
- Create: `app/src/main/java/com/example/mycalendar/presentation/components/DayMarkUi.kt`
- Modify: `app/src/main/java/com/example/mycalendar/presentation/components/EventListItem.kt` (после `providerColor`, строка 36)
- Modify: `app/src/main/java/com/example/mycalendar/presentation/components/ProviderChip.kt:44-48`
- Modify: `app/src/main/java/com/example/mycalendar/presentation/event/EventDetailsScreen.kt:138-142`
- Modify: `app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt:252-263`

**Interfaces:**
- Consumes: `providerColor` (существует), `DayMark` (Task 1).
- Produces: `AppSizes.gridDot: Dp = 5.dp`, `AppSizes.detailsDot: Dp = 12.dp`, `AppSpacing.markInset: Dp = 4.dp`; `@Composable fun ProviderDot(provider: CalendarProvider, modifier: Modifier = Modifier, size: Dp = AppSizes.dot)`; `@Composable fun HolidayMarker(modifier: Modifier = Modifier)`; константы `MONTH_CELL_TAG = "month_cell"`, `WEEK_CARD_TAG = "week_day_card"`, `DAY_HEADER_TAG = "day_header"`, `HOLIDAY_MARKER_TAG = "holiday_marker"`; `fun dayMarkTag(prefix: String, date: LocalDate, mark: DayMark?): String`.

Своего теста у задачи нет намеренно: спека (§8) запрещает проверять цвета пикселем в моках, а вид точки и маркера проверяется поведением вида — connected-тестами Tasks 5–7. Гейт задачи — компиляция, линт и сохранение существующих тестов.

- [ ] **Step 1: Токены**

В `Tokens.kt`, в `object AppSpacing` после `weekCellGap`:

```kotlin
    // Отступ маркера праздника от угла: клетка месяца меньше карточки, но отступ один.
    val markInset: Dp = 4.dp
```

В `object AppSizes` после `dot`:

```kotlin
    // Точки провайдера бывают трёх размеров; все три значения — токен, а не число по месту.
    val gridDot: Dp = 5.dp
    val detailsDot: Dp = 12.dp
```

- [ ] **Step 2: Маркер и теги**

`app/src/main/java/com/example/mycalendar/presentation/components/DayMarkUi.kt`:

```kotlin
package com.example.mycalendar.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.mycalendar.domain.holiday.DayMark
import com.example.mycalendar.presentation.theme.AppShapes
import com.example.mycalendar.presentation.theme.AppSizes
import java.time.LocalDate

/** Префиксы тегов: вид, дата и метка. Суффикс нужен тестам, чтобы отличать тон по имени. */
const val MONTH_CELL_TAG = "month_cell"
const val WEEK_CARD_TAG = "week_day_card"
const val DAY_HEADER_TAG = "day_header"
const val HOLIDAY_MARKER_TAG = "holiday_marker"

/**
 * Тег помеченного дня: «week_day_card_2026-05-09.HOLIDAY», у немеченого дня — «…PLAIN».
 * Тест сравнивает ровно этот суффикс: проверка цвета пикселем в моках невозможна,
 * а карта меток в состоянии экрана — проверяема.
 */
fun dayMarkTag(prefix: String, date: LocalDate, mark: DayMark?): String =
    "${prefix}_$date.${mark?.name ?: "PLAIN"}"

/**
 * Форма-признак праздника. Тон выходного и тон праздника различаются заметно только
 * оттенком (по яркости 1.06–1.11), поэтому одного цвета для распознавания не хватило бы:
 * WCAG 1.4.1 требует резервного признака. Выходной маркера не имеет.
 */
@Composable
fun HolidayMarker(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .size(AppSizes.dot)
            .background(MaterialTheme.colorScheme.onSurface, AppShapes.dayMarker)
            .testTag(HOLIDAY_MARKER_TAG),
    )
}
```

Цвет маркера — `onSurface`: на праздничном тоне это 10.67 (тёмная схема) и 12.31 (светлая), и маркер не спорит с акцентом, которым залит выбор и обведена рамка «сегодня».

- [ ] **Step 3: Точка провайдера с обводкой**

В `EventListItem.kt` после `providerColor` добавить:

```kotlin
/**
 * Точка провайдера. Обводка нужна потому, что на тонированном фоне выходного или праздника
 * цвет точки теряет порог 3.0 (замер: сиреневый локального календаря на бирюзовом — 2.58).
 * Обводка стоит на всех фонах, а не только на тонированных: вид точек меняется
 * единообразно, и компоненту не нужно знать, какой под ним тон.
 */
@Composable
fun ProviderDot(
    provider: CalendarProvider,
    modifier: Modifier = Modifier,
    size: Dp = AppSizes.dot,
) {
    Spacer(
        modifier = modifier
            .size(size)
            .background(providerColor(provider), AppShapes.dayMarker)
            .border(AppSizes.stroke, MaterialTheme.colorScheme.onSurfaceVariant, AppShapes.dayMarker),
    )
}
```

Импорты в `EventListItem.kt`: `androidx.compose.foundation.background`, `androidx.compose.foundation.border`, `androidx.compose.foundation.layout.Spacer`, `androidx.compose.foundation.layout.size`, `androidx.compose.material3.MaterialTheme`, `androidx.compose.ui.unit.Dp`, `com.example.mycalendar.presentation.theme.AppShapes`, `com.example.mycalendar.presentation.theme.AppSizes`. Ни `background`, ни `size`, ни `Dp` в этом файле раньше нет — `ProviderDot` приносит их.

- [ ] **Step 4: Три потребителя**

`ProviderChip.kt` — заменить `Spacer(...)` внутри `Row` (строки 44–48) на:

```kotlin
        ProviderDot(provider)
```

`EventDetailsScreen.kt` — заменить `Box(...)` с `size(12.dp)` и `CircleShape` (строки 138–142) на:

```kotlin
                ProviderDot(event.calendarProvider, size = AppSizes.detailsDot)
```

`CalendarScreen.kt` — внутри `Row` точек в клетке месяца (строки 252–263) заменить `Box(...)` на:

```kotlin
                            dayEvents.take(3).forEach { occurrence ->
                                ProviderDot(
                                    occurrence.event.calendarProvider,
                                    size = AppSizes.gridDot,
                                )
                            }
```

Размеры (`AppSizes.dot` = 6, `AppSizes.gridDot` = 5 dp, `AppSizes.detailsDot` = 12 dp) сохранены один-в-один: задача меняет только наличие обводки. В `CalendarScreen.kt` и `EventDetailsScreen.kt` добавить импорт `com.example.mycalendar.presentation.components.ProviderDot`.

Импорты, которые после замены становятся мёртвыми (проверено по текущему дереву — каждый символ больше нигде в файле не используется):

| Файл | Удалить | Остаётся |
| --- | --- | --- |
| `ProviderChip.kt` | `androidx.compose.foundation.layout.Spacer`, `androidx.compose.foundation.layout.size` | `AppSizes` (нужен `AppSizes.stroke` в рамке чипа), `AppShapes`, `background` (фон чипа) |
| `EventDetailsScreen.kt` | `androidx.compose.foundation.shape.CircleShape`, `androidx.compose.foundation.layout.size`, `androidx.compose.foundation.background`, `androidx.compose.foundation.layout.Box`, `com.example.mycalendar.presentation.components.providerColor` | `Spacer` (три других отступа), `AppSizes` (`AppSizes.stroke` в рамке карточки), `AppShapes` (`AppShapes.card`) |
| `CalendarScreen.kt` | `androidx.compose.foundation.shape.CircleShape`, `androidx.compose.foundation.layout.size`, `androidx.compose.foundation.layout.Box`, `com.example.mycalendar.presentation.components.providerColor` | `background` (фон клетки), `AppShapes`, `dp` (отступы и стрелки) |

`providerColor` в этих двух файлах стоит ровно в одной точке — в заменяемой; функция остаётся нужной `EventCard.kt`, `WeekColumn.kt`, `SettingsScreen.kt` и `ProviderChip.kt`, поэтому её определение не трогаем, умирает только импорт. В `ProviderChip.kt` вызов `providerColor` был в том же пакете, импорта там нет и удалять нечего. Задаче 5 импорт `Box` в `CalendarScreen.kt` потребуется заново: клетка месяца снова станет `Box`.

Если компилятор или линт укажет на другой неиспользуемый импорт — удалять по факту, но состав предупреждений линта (11) не должен вырасти из-за мёртвого импорта.

- [ ] **Step 5: Прогнать сборку и существующие тесты**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 116 тестов (101 + 6 + 2 + 3 + 4), 0 падений.

Run: `./gradlew :app:lintDebug`
Expected: 0 ошибок, 11 предупреждений — состав не изменился.

- [ ] **Step 6: Коммит**

Только с явного разрешения пользователя.

```bash
git add app/src/main/java/com/example/mycalendar/presentation/theme/Tokens.kt app/src/main/java/com/example/mycalendar/presentation/components/DayMarkUi.kt app/src/main/java/com/example/mycalendar/presentation/components/EventListItem.kt app/src/main/java/com/example/mycalendar/presentation/components/ProviderChip.kt app/src/main/java/com/example/mycalendar/presentation/event/EventDetailsScreen.kt app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt
git commit -m "feat(ui): точка провайдера с обводкой и маркер праздника"
```

---

### Task 5: Сетка месяца — тон клетки, маркер и навигация по периодам

**Files:**
- Modify: `app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt` (стрелки периодов — строки 132–147; цикл `week.forEach` в `MonthView` — строки 212–258)
- Create: `app/src/androidTest/java/com/example/mycalendar/DayViewHolidayUiTest.kt` (в этой задаче — тесты сетки месяца; Task 7 дополняет тот же файл шапкой дня, потому что и сетка, и шапка живут на одном экране)

**Interfaces:**
- Consumes: `CalendarUiState.dayMarks` (Task 3), `dayMarkColor` (Task 2), `HolidayMarker` (он сам ставит `HOLIDAY_MARKER_TAG`), `dayMarkTag`, `MONTH_CELL_TAG`, `AppSpacing.markInset` (Task 4), `ProviderDot` (Task 4). Экрану `HOLIDAY_MARKER_TAG` не импортируется — его читает только тест.
- Produces: теги `month_cell_<ISO-дата>.<МЕТКА>`, `period_prev`, `period_next` — на них опираются тесты Tasks 5–7.

- [ ] **Step 1: Теги на стрелках периодов**

В `CalendarScreen.kt`, блок «Стрелки периодов» — `testTag` на обе кнопки, чтобы тест мог доехать до нужного месяца:

```kotlin
                IconButton(onClick = viewModel::goPrevious, modifier = Modifier.testTag("period_prev")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(24.dp))
                IconButton(onClick = viewModel::goNext, modifier = Modifier.testTag("period_next")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
```

Импорт `androidx.compose.ui.platform.testTag`.

- [ ] **Step 2: Клетка месяца**

В `MonthView` цикл `week.forEach { date -> … }` (строки 212–258 на дереве после задачи 4) заменить целиком. `Column` становится содержимым `Box` — иначе маркеру в углу негде встать; модификаторы размера, фона, рамки и клика переходят на `Box`, поведение не меняется:

```kotlin
                week.forEach { date ->
                    val inMonth = date.month == state.visibleMonth.month
                    val isSelected = date == state.selectedDate
                    val isToday = date == LocalDate.now()
                    val mark = state.dayMarks[date]
                    val dayEvents = eventsOn(date, occurrences)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp)
                            .background(
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    else -> dayMarkColor(mark) ?: MaterialTheme.colorScheme.surface
                                },
                                shape = AppShapes.calendarCell,
                            )
                            .border(
                                width = if (isToday && !isSelected) AppSizes.stroke else 0.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = AppShapes.calendarCell,
                            )
                            .clickable { onDayClick(date) }
                            .testTag(dayMarkTag(MONTH_CELL_TAG, date, mark)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                    !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                            // Точки событий (максимум 3)
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                dayEvents.take(3).forEach { occurrence ->
                                    ProviderDot(
                                        occurrence.event.calendarProvider,
                                        size = AppSizes.gridDot,
                                    )
                                }
                            }
                        }
                        // На выбранной клетке тон скрыт под заливкой выбора, поэтому маркер
                        // там не рисуется: два признака в одной клетке только шумят.
                        if (mark == DayMark.HOLIDAY && !isSelected) {
                            HolidayMarker(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(AppSpacing.markInset),
                            )
                        }
                    }
                }
```

Импорты в `CalendarScreen.kt`: `androidx.compose.foundation.layout.Box` (задача 4 удалила его как мёртвый — здесь он снова нужен), `com.example.mycalendar.domain.holiday.DayMark`, `com.example.mycalendar.presentation.components.HolidayMarker`, `MONTH_CELL_TAG`, `dayMarkTag`, `com.example.mycalendar.presentation.theme.dayMarkColor`. `HOLIDAY_MARKER_TAG` здесь не импортируется: тег ставит сам `HolidayMarker` внутри `DayMarkUi.kt`, экрану он не нужен (находка M1 ревьюера задачи 5 — импорт из прежней редакции списка вешал мёртвую строку).

- [ ] **Step 3: Тесты сетки месяца**

`app/src/androidTest/java/com/example/mycalendar/DayViewHolidayUiTest.kt` — каркас и три теста месяца:

```kotlin
package com.example.mycalendar

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.holiday.DayMark
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import com.example.mycalendar.presentation.components.HOLIDAY_MARKER_TAG
import com.example.mycalendar.presentation.components.MONTH_CELL_TAG
import com.example.mycalendar.presentation.components.dayMarkTag
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Тонирование дней на живом экране: клетка месяца получает метку в теге, праздник —
 * форму-маркер, шапка дня — название праздника. Цвет пикселем не проверяется: тест сверяет
 * тег, то есть факт, что вид прочитал метку из состояния экрана.
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DayViewHolidayUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var authRepository: AuthRepository

    // 9 мая 2026 — суббота: тон праздника проверяется заодно с приоритетом над выходным.
    private val victoryDay: LocalDate = LocalDate.of(2026, 5, 9)

    // 5 мая 2026 — вторник без праздника; 16 мая 2026 — суббота без праздника.
    private val plainWeekday: LocalDate = LocalDate.of(2026, 5, 5)
    private val plainWeekend: LocalDate = LocalDate.of(2026, 5, 16)

    @Before
    fun setUp() {
        hiltRule.inject()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            ui.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            ui.executeShellCommand("svc power stayon true")
            ui.executeShellCommand("wm dismiss-keyguard")
        }
        runCatching { runBlocking { settingsRepository.setOnboardingCompleted(false) } }
        runCatching { runBlocking { authRepository.signOutAll() } }
        Thread.sleep(1_000)
    }

    /** Ожидание узла по тегу: состояние экрана приходит потоком, сразу после клика его ещё нет. */
    private fun awaitTag(tag: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun nodesWithText(text: String) =
        composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)

    private fun reachCalendar() {
        val res = composeRule.activity.resources
        val enter = res.getString(R.string.enter_app)
        val month = res.getString(R.string.view_month)
        composeRule.waitUntil(20_000) {
            nodesWithText(enter).fetchSemanticsNodes().isNotEmpty() ||
                nodesWithText(month).fetchSemanticsNodes().isNotEmpty()
        }
        if (nodesWithText(enter).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText(enter).performClick()
        }
        composeRule.waitUntil(20_000) {
            nodesWithText(month).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Переключение вида: название кнопки ищется точно, иначе «День» сливается с «День Победы». */
    private fun switchToView(label: String) = composeRule.onNodeWithText(label).performClick()

    /** Доезжает сеткой месяца до месяца целевой даты стрелками периодов. */
    private fun goToMonth(target: LocalDate) {
        reachCalendar()
        val months = ChronoUnit.MONTHS.between(LocalDate.now().withDayOfMonth(1), target.withDayOfMonth(1))
        val tag = if (months >= 0) "period_next" else "period_prev"
        repeat(kotlin.math.abs(months).toInt()) {
            composeRule.onNode(hasTestTag(tag)).performClick()
        }
    }

    @Test
    fun monthView_tintsHolidayCellAndShowsMarker() {
        goToMonth(victoryDay)
        val holidayTag = dayMarkTag(MONTH_CELL_TAG, victoryDay, DayMark.HOLIDAY)
        awaitTag(holidayTag)
        composeRule.onNode(hasTestTag(holidayTag)).assertExists()
        composeRule.waitUntil(10_000) {
            // Клетка clickable и сливает потомков в merged-дереве: маркер виден только в unmerged.
            composeRule.onAllNodes(hasTestTag(HOLIDAY_MARKER_TAG), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun monthView_marksOnlyHolidaysInTheGrid() {
        goToMonth(plainWeekend)
        awaitTag(dayMarkTag(MONTH_CELL_TAG, plainWeekend, DayMark.WEEKEND))
        composeRule.onNode(hasTestTag(dayMarkTag(MONTH_CELL_TAG, plainWeekday, null))).assertExists()
        // В мае 2026 ровно два твёрдых праздника — 1 и 9 мая, и маркеров ровно столько.
        // Суббота 16 мая тонирована, но формы-признака не получает.
        // Маркер живёт внутри clickable-клетки, которая сливает потомков: считаем unmerged-деревом.
        composeRule.onAllNodes(hasTestTag(HOLIDAY_MARKER_TAG), useUnmergedTree = true)
            .assertCountEquals(2)
    }

    @Test
    fun monthView_hidesMarkerOnSelectedCell() {
        goToMonth(victoryDay)
        val holidayTag = dayMarkTag(MONTH_CELL_TAG, victoryDay, DayMark.HOLIDAY)
        awaitTag(holidayTag)
        val markersBefore = composeRule.onAllNodes(hasTestTag(HOLIDAY_MARKER_TAG), useUnmergedTree = true)
            .fetchSemanticsNodes().size
        assertTrue("маркеров должно быть не меньше одного", markersBefore >= 1)
        composeRule.onNode(hasTestTag(holidayTag)).performClick()
        // Выбранная клетка залита акцентом: её маркер исчезает, а соседний праздник
        // (1 мая) остаётся — значит отключается именно выбор, а не маркеры вообще.
        // Ровно на один меньше, а не «меньше вообще»: при now == 0 это условие бы
        // провалилось и различило бы два этих случая.
        composeRule.waitUntil(10_000) {
            val now = composeRule.onAllNodes(hasTestTag(HOLIDAY_MARKER_TAG), useUnmergedTree = true)
                .fetchSemanticsNodes().size
            now == markersBefore - 1
        }
    }
}
```

Все три теста месяца работают в мае 2026: пять недель сетки (27 апреля — 31 мая), ровно два твёрдых праздника и заведомо нет сегодняшнего дня, поэтому выбор клетки не мешает подсчёту маркеров. `assertExists()` — метод `SemanticsNodeInteraction`, отдельного импорта не требует (так же делают `AppUiTest.kt` и `ConflictScreensUiTest.kt`): импорт `androidx.compose.ui.test.assertExists` в тесте не появится. Тот же разбор по фактическому API касается матчера — в 1.12.1 он `hasTestTag`, а не `hasTag`, и поиска маркера по `useUnmergedTree`: клетка `clickable`, и в merged-дереве её потомок-маркер не существует как узел, поэтому запрос без этого флага падает на пустом множестве.

- [ ] **Step 4: Прогнать connected**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.DayViewHolidayUiTest`
Expected: 3 теста, 0 падений.

Если connected-прогон печатает успех при неустановившемся APK (известная ловушка из `docs/how-to-verify.md`), сверять JUnit-XML в `app/build/outputs/androidTest-results/connected/debug/`, а не вывод консоли.

- [ ] **Step 5: Коммит**

Только с явного разрешения пользователя.

```bash
git add app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt app/src/androidTest/java/com/example/mycalendar/DayViewHolidayUiTest.kt
git commit -m "feat(calendar): сетка месяца тонируется по метке дня"
```

---

### Task 6: Карточки недельного вида

**Files:**
- Modify: `app/src/main/java/com/example/mycalendar/presentation/components/WeekColumn.kt`
- Modify: `app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt` (`WeekView` — вызов `WeekDayColumn`, строки 316–322)
- Modify: `app/src/androidTest/java/com/example/mycalendar/WeekViewCardsUiTest.kt`

**Interfaces:**
- Consumes: `DayMark`, `dayMarkColor`, `HolidayMarker`, `dayMarkTag`, `WEEK_CARD_TAG`.
- Produces: `WeekDayColumn(…, mark: DayMark? = null)`; тег `week_day_card_<ISO-дата>.<МЕТКА>` на корне карточки.

- [ ] **Step 1: Падающий тест**

В `WeekViewCardsUiTest.kt` добавить тест и импорты `androidx.compose.ui.test.hasTestTag`, `com.example.mycalendar.domain.holiday.DayMark`, `com.example.mycalendar.domain.holiday.HolidayRegistry`, `com.example.mycalendar.presentation.components.WEEK_CARD_TAG`, `com.example.mycalendar.presentation.components.dayMarkTag`. `or` импорта не требует: это infix-метод самого `SemanticsMatcher` (замер по байткодам `ui-test-android:1.12.1` — дескриптор `(SemanticsMatcher)SemanticsMatcher` в `SemanticsMatcher.class`, верхнеуровневой функции `or` в артефакте нет); `assertExists` — тоже метод узла:

```kotlin
    /**
     * Тон карточки читается из тега: проверка цвета пикселем в моках невозможна, а вот
     * факт «вид прочитал метку дня из состояния экрана» проверяется ровно именем тега.
     * Реестр здесь используется как фикстура: правильность его таблицы — задача
     * HolidayRegistryTest, а не этого теста.
     */
    @Test
    fun weekView_weekendCardCarriesItsMark() {
        val res = composeRule.activity.resources
        reachCalendar()
        composeRule.onNodeWithText(res.getString(R.string.view_week)).performClick()

        val registry = HolidayRegistry()
        val saturday = weekMonday.plusDays(5)
        scrollToWeekCard(saturday)
        // Суббота может оказаться праздником — тогда правильный тон праздник
        val weekendTag = dayMarkTag(WEEK_CARD_TAG, saturday, DayMark.WEEKEND)
        val holidayTag = dayMarkTag(WEEK_CARD_TAG, saturday, DayMark.HOLIDAY)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasTestTag(weekendTag) or hasTestTag(holidayTag))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Первый день недели, у которого метки нет вовсе: он обязан остаться без тона
        val plainDay = (0L..6L).map { weekMonday.plusDays(it) }.first { registry.markFor(it) == null }
        scrollToWeekCard(plainDay)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasTestTag(dayMarkTag(WEEK_CARD_TAG, plainDay, null)))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
```

- [ ] **Step 2: Прогнать, убедиться, что тест падает**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.WeekViewCardsUiTest`
Expected: FAIL нового теста («Could not find node with tag …»); остальные тесты класса — PASS.

- [ ] **Step 3: Тон и маркер в `WeekDayColumn`**

Параметр:

```kotlin
fun WeekDayColumn(
    date: LocalDate,
    dayLabel: String,
    events: List<EventOccurrence>,
    onEventClick: (String) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    isToday: Boolean = date == LocalDate.now(),
    mark: DayMark? = null,
) {
```

Корень карточки:

```kotlin
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(dayMarkTag(WEEK_CARD_TAG, date, mark)),
        shape = AppShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = dayMarkColor(mark) ?: MaterialTheme.colorScheme.surfaceVariant,
        ),
```

Строка заголовка: дата получает вес, чтобы маркер встал в правый край карточки, а не прилип к тексту:

```kotlin
                Text(
                    text = date.format(weekColDateFormatter),
                    style = WeekColumnTypography.date,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (mark == DayMark.HOLIDAY) {
                    HolidayMarker()
                }
```

Импорты в `WeekColumn.kt`: `androidx.compose.ui.platform.testTag`, `com.example.mycalendar.domain.holiday.DayMark`, `com.example.mycalendar.presentation.theme.dayMarkColor`.

Обводка карточки в светлой схеме (`BorderStroke(stroke, outline)`) остаётся как есть: спека её менять не просит, и она описывает контур, а не несёт информацию о дне.

- [ ] **Step 4: Недельный вид передаёт метку**

`CalendarScreen.kt`, `WeekView`:

```kotlin
            WeekDayColumn(
                date = date,
                dayLabel = dayShortName(date.dayOfWeek),
                events = eventsOn(date, occurrences),
                onEventClick = onEventClick,
                onOpenDay = onOpenDay,
                mark = state.dayMarks[date],
            )
```

- [ ] **Step 5: Прогнать набор недели и JVM**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.WeekViewCardsUiTest`
Expected: PASS, 6 тестов (было 5, добавился 1), 0 падений.

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 116 тестов, 0 падений.

- [ ] **Step 6: Коммит**

Только с явного разрешения пользователя.

```bash
git add app/src/main/java/com/example/mycalendar/presentation/components/WeekColumn.kt app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt app/src/androidTest/java/com/example/mycalendar/WeekViewCardsUiTest.kt
git commit -m "feat(calendar): карточки недельного вида получают тон дня"
```

---

### Task 7: Шапка дня — тон и название праздника

**Files:**
- Modify: `app/src/main/java/com/example/mycalendar/presentation/components/DayHeader.kt`
- Modify: `app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt` (`DayView` — вызов `DayHeader` строка 348; шапка под сеткой месяца — строки 286–289)
- Modify: `app/src/androidTest/java/com/example/mycalendar/DayViewHolidayUiTest.kt`

**Interfaces:**
- Consumes: `dayMarkColor`, `dayMarkTag`, `DAY_HEADER_TAG`, `CalendarUiState.holidayTitles`.
- Produces: `DayHeader(date, modifier, isToday, mark: DayMark? = null, holidayTitle: String? = null)`; тег `day_header_<ISO-дата>.<МЕТКА>`.

- [ ] **Step 1: Тесты шапки дня**

Добавить в `DayViewHolidayUiTest.kt` три теста и импорты `com.example.mycalendar.presentation.components.DAY_HEADER_TAG`. Импорт `androidx.compose.ui.test.assertDoesNotExist` не нужен: `assertDoesNotExist()` — тот же метод `SemanticsNodeInteraction`, что и `assertExists()` (в `ui-test-android:1.12.1` имя встречается только в `SemanticsNodeInteraction.class`, верхнеуровневой функции нет; `EventCardUiTest.kt:92` и `AppUiTest.kt:294` зовут его без импорта):

```kotlin
    @Test
    fun dayView_showsHolidayTitleAndTint() {
        val res = composeRule.activity.resources
        goToMonth(victoryDay)
        val holidayTag = dayMarkTag(MONTH_CELL_TAG, victoryDay, DayMark.HOLIDAY)
        awaitTag(holidayTag)
        composeRule.onNode(hasTestTag(holidayTag)).performClick()
        switchToView(res.getString(R.string.view_day))

        composeRule.waitUntil(10_000) {
            nodesWithText(res.getString(R.string.holiday_victory_day)).fetchSemanticsNodes().isNotEmpty()
        }
        awaitTag(dayMarkTag(DAY_HEADER_TAG, victoryDay, DayMark.HOLIDAY))
    }

    @Test
    fun dayView_ordinaryWeekendHasTintButNoTitle() {
        val res = composeRule.activity.resources
        goToMonth(plainWeekend)
        val weekendTag = dayMarkTag(MONTH_CELL_TAG, plainWeekend, DayMark.WEEKEND)
        awaitTag(weekendTag)
        composeRule.onNode(hasTestTag(weekendTag)).performClick()
        switchToView(res.getString(R.string.view_day))
        awaitTag(dayMarkTag(DAY_HEADER_TAG, plainWeekend, DayMark.WEEKEND))
        composeRule.onNode(hasText(res.getString(R.string.holiday_victory_day), substring = false))
            .assertDoesNotExist()
    }

    @Test
    fun monthView_headerUnderGridHasTintButNoHolidayTitle() {
        // Спека §2 и уточнение 6 плана: название праздника — только в виде «День».
        // Под сеткой месяца шапка тонирована, но строки названия не получает.
        val res = composeRule.activity.resources
        goToMonth(victoryDay)
        val holidayTag = dayMarkTag(MONTH_CELL_TAG, victoryDay, DayMark.HOLIDAY)
        awaitTag(holidayTag)
        composeRule.onNode(hasTestTag(holidayTag)).performClick()
        // Тон шапки под сеткой проверяется тегом: он обязан появиться, иначе «нет строки»
        // означает просто неотработавшее тонирование.
        awaitTag(dayMarkTag(DAY_HEADER_TAG, victoryDay, DayMark.HOLIDAY))
        composeRule.onNode(hasText(res.getString(R.string.holiday_victory_day), substring = false))
            .assertDoesNotExist()
    }
```

- [ ] **Step 2: Прогнать, убедиться, что тесты падают**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.DayViewHolidayUiTest`
Expected: FAIL — узла с тегом `day_header_…` нет, строки праздника нет.

- [ ] **Step 3: `DayHeader` получает метку и название**

Заменить компонент целиком (`app/src/main/java/com/example/mycalendar/presentation/components/DayHeader.kt`):

```kotlin
/**
 * Заголовок дня: «Сегодня, 5 ноября» или «вторник, 5 ноября».
 * Точка акцентного цвета — единственный признак сегодняшнего дня, чтобы текст не красился
 * акцентом и оставался в контрасте AA. Тон и название праздника приходят параметрами:
 * компонент не знает про реестр и про ресурсы названий.
 */
@Composable
fun DayHeader(
    date: LocalDate,
    modifier: Modifier = Modifier,
    isToday: Boolean = date == LocalDate.now(),
    mark: DayMark? = null,
    holidayTitle: String? = null,
) {
    val title = if (isToday) {
        stringResource(R.string.today) + ", " + date.format(dayTitleFormatter)
    } else {
        date.format(weekdayTitleFormatter).replaceFirstChar { it.uppercase(russianLocale) }
    }
    Column(
        modifier = modifier
            .padding(vertical = AppSpacing.headerVPad)
            .background(
                color = dayMarkColor(mark) ?: Color.Transparent,
                shape = AppShapes.calendarCell,
            )
            .testTag(dayMarkTag(DAY_HEADER_TAG, date, mark))
            .padding(horizontal = AppSpacing.cardHPad),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.rowGap),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.rowGap),
            // minTouch высота не нужна: заголовок не кликабелен
        ) {
            if (isToday) {
                Spacer(
                    modifier = Modifier
                        .size(AppSizes.dot)
                        .background(MaterialTheme.colorScheme.primary, AppShapes.dayMarker),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.heightIn(min = AppSizes.dayHeaderMinHeight),
            )
        }
        if (holidayTitle != null) {
            Text(
                text = holidayTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

Импорты: `androidx.compose.foundation.layout.Column`, `androidx.compose.ui.graphics.Color`, `androidx.compose.ui.platform.testTag`, `com.example.mycalendar.domain.holiday.DayMark`, `com.example.mycalendar.presentation.theme.dayMarkColor`.

- [ ] **Step 4: Виды передают метку**

`CalendarScreen.kt`, `DayView` — вызов шапки на строке 348:

```kotlin
        item(key = "header_${state.selectedDate}") {
            DayHeader(
                date = state.selectedDate,
                mark = state.dayMarks[state.selectedDate],
                holidayTitle = state.holidayTitles[state.selectedDate]
                    ?.let { stringResource(it) },
            )
        }
```

`CalendarScreen.kt`, шапка под сеткой месяца (строки 286–289) — тон есть, названия нет:

```kotlin
        DayHeader(
            date = state.selectedDate,
            modifier = Modifier.padding(horizontal = AppSpacing.screen),
            mark = state.dayMarks[state.selectedDate],
        )
```

- [ ] **Step 5: Прогнать connected и JVM**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.DayViewHolidayUiTest`
Expected: PASS, 6 тестов (3 теста месяца + 3 теста шапки), 0 падений.

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 116 тестов, 0 падений.

- [ ] **Step 6: Коммит**

Только с явного разрешения пользователя.

```bash
git add app/src/main/java/com/example/mycalendar/presentation/components/DayHeader.kt app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt app/src/androidTest/java/com/example/mycalendar/DayViewHolidayUiTest.kt
git commit -m "feat(calendar): шапка дня получает тон и название праздника"
```

---

### Task 8: Полные прогоны и счётчики в документах

**Files:**
- Modify: `README.md` (строки 165 и 168 — счётчики; абзац про визуальный язык, около строки 101)
- Modify: `docs/how-to-verify.md` (строки 156–157, 167–172, состав наборов около строки 179)

**Interfaces:**
- Consumes: всё, что сделано в Tasks 1–7.
- Produces: ничего для кода; измеренные счётчики прогонов в документах.

- [ ] **Step 1: Полный JVM-прогон**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 0 падений; количество тестов — 116 (101 + 6 реестр + 2 тон + 3 контраст + 4 границы). В документы пишется измеренное число, а не прогноз: если отчёт показал другое, писать надо отчёт.

- [ ] **Step 2: Линт**

Run: `./gradlew :app:lintDebug`
Expected: 0 ошибок, 11 предупреждений. Новый предупреждающий элемент (например неиспользуемый импорт после правок) — починить, а не заносить в счётчик.

- [ ] **Step 3: Полный connected-набор**

Run: `./gradlew :app:connectedDebugAndroidTest`
Expected: 0 падений; 62 теста (55 + 6 в `DayViewHolidayUiTest`: 3 теста месяца из Task 5 и 3 теста Task 7 — два про вид «День» и один про шапку под сеткой месяца, + 1 новый в `WeekViewCardsUiTest`). Сверять по JUnit-XML в `app/build/outputs/androidTest-results/connected/debug/`.

После прогона приложение снято с устройства, база и тема сброшены — вход в Яндекс придётся повторять (см. `docs/how-to-verify.md`).

- [ ] **Step 4: Обновить документы**

В `docs/how-to-verify.md`: строки 156–157 и 167–172 — заменить на измеренные счётчики; в перечне наборов добавить `DayViewHolidayUiTest` (месяц и день: тон клетки, маркер праздника, название в шапке) и заметку, что тесты вида ездят по месяцам через `testTag` стрелок периодов (`period_prev`, `period_next`), поэтому переименование этих тегов ломает их.

В `README.md`: строки 165 и 168 — счётчики; в абзаце про визуальный язык (около строки 101) дописать, что тон выходного и праздника отличается оттенком, а праздник — ещё и формой-маркером, и что пары контраста зафиксированы тестом `PaletteContrastTest`.

Отдельной строкой зафиксировать факт: скриншоты README сняты до тонирования, пересъёмка требует запущенного интерфейса и из агента этой машины не выполняется.

- [ ] **Step 5: Проверить чистоту диффа**

Run: `git status --short`
Expected: только `README.md` и `docs/how-to-verify.md`.

Run: `git diff --stat`
Expected: два документа.

- [ ] **Step 6: Коммит**

Только с явного разрешения пользователя.

```bash
git add README.md docs/how-to-verify.md
git commit -m "docs: счётчики тестов после тонирования выходных и праздников"
```

---

## Self-Review

**Покрытие спеки.** §1 границы — реестр не трогает переносы, пасхальный круг и лунные даты (Task 1), настройки страны нет. §2 таблица и 13 строк — Task 1. §3 модель — Task 1, ровно три метода, как в спеке. §4 палитра, замеры и форма-признак — Tasks 2 и 4. §5 три вида и обводка точек — Tasks 4, 5, 6, 7. §6 поток данных — Task 3 (замены `combine` на `map` и `rangeMillis` на `gridBounds` объяснены в уточнениях 2 и 7). §7 деградация — ключ `MonthDay` в Task 1 и `?: surface`/`?: surfaceVariant` в Tasks 5–7. §8 тесты — `HolidayRegistryTest` (Task 1), `PaletteContrastTest` (Task 2), соответствие палитре (Task 2 вместо правки `ThemePaletteTest` — уточнение 8), `DayViewHolidayUiTest` (Tasks 5 и 7), `WeekViewCardsUiTest` (Task 6), счётчики (Task 8). §9 список файлов — покрыт целиком, кроме `AppSizes.holidayMarker` (уточнение 1) и `ThemePaletteTest` (уточнение 8).

**Заглушки.** Нет TBD, «добавить обработку», «аналогично Task N»; каждый листинг кода и теста приведён целиком, каждая команда — с ожидаемым результатом.

**Совпадение типов.** `DayMark`, `HolidayRegistry.markFor/holidayTitleRes/marksBetween` — Task 1; потребители в Tasks 3, 5, 6, 7 и в androidTest используют ровно эти имена. `dayMarkContainer(mark, dark)` и `dayMarkColor(mark)` — Task 2; вызовы в Tasks 5–7 только `dayMarkColor`. `dayMarkTag(prefix, date, mark)`, `MONTH_CELL_TAG`, `WEEK_CARD_TAG`, `DAY_HEADER_TAG`, `HOLIDAY_MARKER_TAG`, `HolidayMarker` — Task 4; вызовы в Tasks 5, 6, 7. `monthWeeks` (`internal`) — Task 3; читают `CalendarScreen` и `CalendarUiState.gridBounds()`. `AppSizes.gridDot`, `AppSizes.detailsDot`, `AppSpacing.markInset`, `ProviderDot` — Task 4. Поля `dayMarks`/`holidayTitles` и метод `gridBounds()` — Task 3.
