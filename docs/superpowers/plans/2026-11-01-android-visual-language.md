# Визуальный язык SyncPlanner для Android — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Перекрасить Android-приложение в тёмно-синий визуальный язык макетов SyncPlanner и показать недельный вид карточками событий вместо списка, не меняя структуру экранов.

**Architecture:** Новый слой токенов (`Palette.kt`, `Tokens.kt`, `CardTypography`) питает `MaterialTheme`; один компонент `EventCard` рендерит событие во всех трёх видах календаря, а виды различаются только группировкой. Material You (динамические цвета) вырезается целиком — от переключателя в настройках до ключа DataStore, — поэтому палитра детерминирована и её числа можно перенести в Windows-версию 1:1.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3 (BOM 2026.09.00), Hilt, JUnit4 (JVM-тесты и instrumented-тесты на Compose Rule), Gradle 9.6, AGP 9.4.1, KSP.

**Spec:** [docs/superpowers/specs/2026-11-01-android-visual-language-design.md](../specs/2026-11-01-android-visual-language-design.md)

## Global Constraints

- compileSdk 37, targetSdk 37, minSdk 26, JVM 17; новые зависимости запрещены (библиотеки не добавляем, свои шрифт-файлы не приносим).
- Версию не трогаем: `versionCode = 5`, `versionName = "1.1.3"` остаются как есть — релиз это отдельное решение пользователя.
- Тесты гоняем только через `./gradlew`; **одновременно может идти только один прогон Gradle** (параллельные сборки на этой машине конфликтуют).
- Коммит по шагу «Commit» входит в задачу; `git push`, теги и публикации на GitHub — только по отдельному явному разрешению.
- Никаких секретов и токенов в коде, тестах и сообщениях коммитов. `keystore.properties` не трогаем.
- Тексты интерфейса — по-русски; в новых строках и в комментариях эмодзи не используем.
- Git config не меняем, force-push не делаем.
- `./gradlew :app:connectedDebugAndroidTest` снимает приложение и стирает его данные: вход в Яндекс и подключённые календари после прогона приходится повторять, а сами `@HiltAndroidTest`-тесты этому не мешают (они сбрасывают онбординг и провайдеры сами).
- Ожидаемый зелёный результат suite: JVM 97 + новые тесты, lint 0 ошибок (10 предупреждений), connected — одно падение `SchedulerAndMediaTest.voiceRecorder_recordsM4aFile` (`AssumptionViolatedException`, нет микрофона).
- Структура экранов, навигация, ViewModel'и и слои данных не меняются: правим только presentation и связанные с ним строки/настройки.

---

### Task 1: Палитра и токены (файлы-константы + тест контраста)

**Files:**
- Create: `app/src/main/java/com/example/mycalendar/presentation/theme/Palette.kt`
- Create: `app/src/main/java/com/example/mycalendar/presentation/theme/Tokens.kt`
- Create: `app/src/test/java/com/example/mycalendar/PaletteContrastTest.kt`

**Interfaces:**
- Consumes: ничего (первые файлы).
- Produces:
  - `Palette.kt` — `val DarkBackground/DarkSurface/DarkCard/DarkOnSurface/DarkOnSurfaceVariant/DarkPrimary/DarkOnPrimary/DarkPrimaryContainer/DarkOnPrimaryContainer/DarkOutline/DarkError/DarkSecondary/DarkSecondaryContainer/DarkTertiary: Color`, симметричный набор `Light…`, и `EventLocalColorDark/EventGoogleColorDark/EventYandexColorDark/EventLocalColorLight/EventGoogleColorLight/EventYandexColorLight: Color`.
  - `Tokens.kt` — `object AppShapes { card, chip, calendarCell, dayMarker: Shape }`, `object AppSpacing { screen, cardGap, dayGap, cardHPad, cardVPad, providerStripe: Dp }`, `object AppSizes { timeColumn, segmentedHeight, minTouch, iconSmall, iconMedium, dayHeaderMinHeight, stroke: Dp }`.
  - `PaletteContrastTest` — ничего в продакшен-код не экспортирует: проверка чисел палитры на этапе сборки.

- [ ] **Step 1: Написать падающий тест контраста**

Создать `app/src/test/java/com/example/mycalendar/PaletteContrastTest.kt`:

```kotlin
package com.example.mycalendar

import androidx.compose.ui.graphics.Color
import com.example.mycalendar.presentation.theme.DarkBackground
import com.example.mycalendar.presentation.theme.DarkCard
import com.example.mycalendar.presentation.theme.DarkOnPrimary
import com.example.mycalendar.presentation.theme.DarkOnPrimaryContainer
import com.example.mycalendar.presentation.theme.DarkOnSurface
import com.example.mycalendar.presentation.theme.DarkOnSurfaceVariant
import com.example.mycalendar.presentation.theme.DarkPrimary
import com.example.mycalendar.presentation.theme.DarkPrimaryContainer
import com.example.mycalendar.presentation.theme.LightBackground
import com.example.mycalendar.presentation.theme.LightCard
import com.example.mycalendar.presentation.theme.LightOnPrimary
import com.example.mycalendar.presentation.theme.LightOnPrimaryContainer
import com.example.mycalendar.presentation.theme.LightOnSurface
import com.example.mycalendar.presentation.theme.LightOnSurfaceVariant
import com.example.mycalendar.presentation.theme.LightPrimary
import com.example.mycalendar.presentation.theme.LightPrimaryContainer
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Контраст новой палитры по WCAG 2.1 (relative luminance).
 *
 * Проверяем пары «текст на фоне», которые видит пользователь: текст карточки на фоне
 * карточки, подписи на фоне экрана, текст на акценте. Числа — из спека, секция 1.
 */
class PaletteContrastTest {

    @Test
    fun `dark text pairs meet AA`() {
        assertAtLeast(AA, DarkOnSurface, DarkCard)
        assertAtLeast(AA, DarkOnSurfaceVariant, DarkCard)
        assertAtLeast(AA, DarkOnSurfaceVariant, DarkBackground)
        assertAtLeast(AA, DarkOnPrimary, DarkPrimary)
        assertAtLeast(AA, DarkOnPrimaryContainer, DarkPrimaryContainer)
    }

    @Test
    fun `light text pairs meet AA`() {
        assertAtLeast(AA, LightOnSurface, LightCard)
        assertAtLeast(AA, LightOnSurfaceVariant, LightCard)
        assertAtLeast(AA, LightOnSurfaceVariant, LightBackground)
        assertAtLeast(AA, LightOnPrimary, LightPrimary)
        assertAtLeast(AA, LightOnPrimaryContainer, LightPrimaryContainer)
    }

    @Test
    fun `accent stays readable as text on the card`() {
        // Заголовок «сегодня» и ссылки пишутся акцентом прямо на фоне карточки.
        assertAtLeast(AA, DarkPrimary, DarkCard)
        assertAtLeast(AA, LightPrimary, LightCard)
    }

    private fun assertAtLeast(min: Double, fg: Color, bg: Color) {
        val ratio = wcagRatio(fg, bg)
        assertTrue("Контраст $ratio < $min: fg=$fg bg=$bg", ratio >= min)
    }

    private companion object {
        const val AA = 4.5
    }
}

/** Контраст fg/bg по формуле WCAG 2.1; значения 0..1, как в Compose Color. */
private fun wcagRatio(fg: Color, bg: Color): Double {
    val l1 = luminance(fg)
    val l2 = luminance(bg)
    return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
}

private fun luminance(color: Color): Double {
    fun channel(value: Float): Double {
        val s = value.toDouble()
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}
```

- [ ] **Step 2: Убедиться, что тест не компилируется**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.mycalendar.PaletteContrastTest"`
Expected: FAILURE, `Unresolved reference: DarkBackground` (и остальные импорты палитры).

Если сборка тестов падает с «Unresolved reference: androidx.compose.ui.graphics» — Compose не на classpath JVM-тестов: добавить в `app/build.gradle.kts` в блок `// Тесты` после `testImplementation(libs.kxml2)` строки

```kotlin
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.graphics)
```

и повторить шаг. (На `implementation`-зависимостях приложения JVM-тесты обычно собираются и без этого.)

- [ ] **Step 3: Создать `Palette.kt`**

```kotlin
package com.example.mycalendar.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Палитра SyncPlanner. Тёмная схема — эталон (тёмно-синий из макетов), светлая —
 * те же роли в тех же пропорциях. Значения подобраны по WCAG AA: см. PaletteContrastTest.
 *
 * Эти же числа переносит Windows-версия — менять их можно только вместе со спеком.
 */

// ---------- Тёмная схема (эталон) ----------

val DarkBackground = Color(0xFF0B1220)
val DarkSurface = Color(0xFF101B2E)
val DarkCard = Color(0xFF1B2A44)
val DarkOnSurface = Color(0xFFE8EEF9)
val DarkOnSurfaceVariant = Color(0xFF9FB0CC)
val DarkPrimary = Color(0xFF3D8BFF)
val DarkOnPrimary = Color(0xFF04122B)
val DarkPrimaryContainer = Color(0xFF16345F)
val DarkOnPrimaryContainer = Color(0xFFBBD8FF)
val DarkSecondary = Color(0xFFA9B7CF)
val DarkSecondaryContainer = Color(0xFF24334D)
val DarkTertiary = Color(0xFF9C8CFF)
val DarkOutline = Color(0xFF2C3C58)
val DarkError = Color(0xFFFFB4AB)

// ---------- Светлая схема (производная) ----------

val LightBackground = Color(0xFFF4F7FC)
val LightSurface = Color(0xFFFFFFFF)
val LightCard = Color(0xFFEAF0FA)
val LightOnSurface = Color(0xFF0F1B2D)
val LightOnSurfaceVariant = Color(0xFF4A5A73)
val LightPrimary = Color(0xFF2766D6)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFDCE8FF)
val LightOnPrimaryContainer = Color(0xFF06234F)
val LightSecondary = Color(0xFF4A5A73)
val LightSecondaryContainer = Color(0xFFDCE6F5)
val LightTertiary = Color(0xFF5B44D6)
val LightOutline = Color(0xFFC6D2E4)
val LightError = Color(0xFFB3261E)

// ---------- Провайдеры: полоса и точка карточки ----------
// Разведены относительно primary, чтобы полоса не читалась как «выбрано».

val EventLocalColorDark = Color(0xFF7C5CFF)
val EventGoogleColorDark = Color(0xFF2E8DF5)
val EventYandexColorDark = Color(0xFFFF5252)

val EventLocalColorLight = Color(0xFF5B44D6)
val EventGoogleColorLight = Color(0xFF1A73E8)
val EventYandexColorLight = Color(0xFFDB3A22)
```

- [ ] **Step 4: Создать `Tokens.kt`**

```kotlin
package com.example.mycalendar.presentation.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Геометрия интерфейса. Единственное место, где в UI стоят числа: экраны и компоненты
 * обязаны брать значения отсюда, иначе Windows-версия не сможет скопировать макет 1:1.
 */

object AppShapes {
    val card: Shape = RoundedCornerShape(18.dp)
    // Пилюля: 50 % высоты, а не фиксированный радиус — чип растёт вместе с текстом.
    val chip: Shape = RoundedCornerShape(percent = 50)
    val calendarCell: Shape = RoundedCornerShape(12.dp)
    val dayMarker: Shape = CircleShape
}

object AppSpacing {
    val screen: Dp = 16.dp
    val cardGap: Dp = 10.dp
    val dayGap: Dp = 20.dp
    val cardHPad: Dp = 14.dp
    val cardVPad: Dp = 12.dp
    val providerStripe: Dp = 4.dp
}

object AppSizes {
    val timeColumn: Dp = 56.dp
    val segmentedHeight: Dp = 40.dp
    val minTouch: Dp = 48.dp
    val iconSmall: Dp = 16.dp
    val iconMedium: Dp = 18.dp
    val dayHeaderMinHeight: Dp = 28.dp
    val stroke: Dp = 1.dp
}
```

- [ ] **Step 5: Прогнать тест**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.mycalendar.PaletteContrastTest"`
Expected: PASS, 3 теста. Реальные отношения (посчитаны при проектировании, тест их подтверждает): тёмная 12.34 / 6.54 / 8.52 / 5.62 / 8.51, светлая 15.10 / 6.11 / 6.52 / 5.31 / 12.51, акцент на карточке 4.59 и 4.63.

- [ ] **Step 6: Убедиться, что остальная сборка не сломана, и закоммитить**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (новые файлы пока никем не используются; `Color.kt` с индиго ещё на месте и тема ещё не переведена — это Task 2).

```bash
git add app/src/main/java/com/example/mycalendar/presentation/theme/Palette.kt \
        app/src/main/java/com/example/mycalendar/presentation/theme/Tokens.kt \
        app/src/test/java/com/example/mycalendar/PaletteContrastTest.kt \
        docs/superpowers/specs/2026-11-01-android-visual-language-design.md
git commit -m "feat(theme): палитра navy и токены геометрии с проверкой контраста по WCAG"
```

---

### Task 2: Тема подключена к палитре, Material You вырезан целиком

**Files:**
- Modify: `app/src/main/java/com/example/mycalendar/presentation/theme/Theme.kt`
- Delete: `app/src/main/java/com/example/mycalendar/presentation/theme/Color.kt`
- Modify: `app/src/main/java/com/example/mycalendar/presentation/components/EventListItem.kt:31-51` (импорты + `providerColor`)
- Modify: `app/src/main/java/com/example/mycalendar/MainActivity.kt:52-56`
- Modify: `app/src/main/java/com/example/mycalendar/presentation/settings/SettingsScreen.kt:150-169`
- Modify: `app/src/main/java/com/example/mycalendar/presentation/settings/SettingsViewModel.kt:70-77,163-195`
- Modify: `app/src/main/java/com/example/mycalendar/domain/repository/SettingsRepository.kt:12,25`
- Modify: `app/src/main/java/com/example/mycalendar/data/preferences/SettingsRepositoryImpl.kt:26-51,69-71`
- Modify: `app/src/androidTest/java/com/example/mycalendar/SyncConflictEndToEndTest.kt:652,658`
- Modify: `app/src/main/res/values/strings.xml:95-96`
- Create: `app/src/androidTest/java/com/example/mycalendar/ThemePaletteTest.kt`

**Interfaces:**
- Consumes: `Palette.kt` из Task 1 (все `Dark*`/`Light*`/`Event*Color*`).
- Produces:
  - `@Composable fun MyCalendarTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit)` — параметр `dynamicColor` исчезает.
  - `val LocalIsDarkTheme: StaticCompositionLocal<Boolean>` — правда ли сейчас выбрана тёмная схема (нужен для границы карточки и цвета провайдера).
  - `@Composable fun providerColor(provider: CalendarProvider): Color` — стал `@Composable`, читает `LocalIsDarkTheme`; сигнатура вызова для экранов не меняется.

- [ ] **Step 1: Написать падающий instrumented-тест темы**

Создать `app/src/androidTest/java/com/example/mycalendar/ThemePaletteTest.kt`:

```kotlin
package com.example.mycalendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ThemeMode
import com.example.mycalendar.presentation.components.providerColor
import com.example.mycalendar.presentation.theme.DarkCard
import com.example.mycalendar.presentation.theme.DarkOnSurface
import com.example.mycalendar.presentation.theme.DarkOnSurfaceVariant
import com.example.mycalendar.presentation.theme.DarkPrimary
import com.example.mycalendar.presentation.theme.EventGoogleColorDark
import com.example.mycalendar.presentation.theme.EventGoogleColorLight
import com.example.mycalendar.presentation.theme.EventLocalColorDark
import com.example.mycalendar.presentation.theme.EventLocalColorLight
import com.example.mycalendar.presentation.theme.EventYandexColorDark
import com.example.mycalendar.presentation.theme.EventYandexColorLight
import com.example.mycalendar.presentation.theme.LightCard
import com.example.mycalendar.presentation.theme.LightOnSurface
import com.example.mycalendar.presentation.theme.MyCalendarTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Тема собирает схему из палитры и не поддакивает системным обоям:
 * Material You вырезан, поэтому набор цветов детерминирован.
 */
@RunWith(AndroidJUnit4::class)
class ThemePaletteTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `dark mode uses the navy palette`() {
        rule.setContent {
            MyCalendarTheme(themeMode = ThemeMode.DARK) {
                assertEquals(DarkPrimary, MaterialTheme.colorScheme.primary)
                assertEquals(DarkCard, MaterialTheme.colorScheme.surfaceVariant)
                assertEquals(DarkOnSurface, MaterialTheme.colorScheme.onSurface)
                assertEquals(DarkOnSurfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                assertTrue(LocalIsDarkThemeFlag())
            }
        }
    }

    @Test
    fun `light mode uses the derived palette`() {
        rule.setContent {
            MyCalendarTheme(themeMode = ThemeMode.LIGHT) {
                assertEquals(LightCard, MaterialTheme.colorScheme.surfaceVariant)
                assertEquals(LightOnSurface, MaterialTheme.colorScheme.onSurface)
                assertFalse(LocalIsDarkThemeFlag())
            }
        }
    }

    @Test
    fun `provider color follows the scheme`() {
        rule.setContent {
            MyCalendarTheme(themeMode = ThemeMode.DARK) {
                assertEquals(EventLocalColorDark, providerColor(CalendarProvider.LOCAL))
                assertEquals(EventGoogleColorDark, providerColor(CalendarProvider.GOOGLE))
                assertEquals(EventYandexColorDark, providerColor(CalendarProvider.YANDEX))
            }
            MyCalendarTheme(themeMode = ThemeMode.LIGHT) {
                assertEquals(EventLocalColorLight, providerColor(CalendarProvider.LOCAL))
                assertEquals(EventGoogleColorLight, providerColor(CalendarProvider.GOOGLE))
                assertEquals(EventYandexColorLight, providerColor(CalendarProvider.YANDEX))
            }
        }
    }

    /** Проверяет, что тема сама проставляет флаг схемы через CompositionLocal. */
    @Composable
    private fun LocalIsDarkThemeFlag(): Boolean =
        com.example.mycalendar.presentation.theme.LocalIsDarkTheme.current
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.ThemePaletteTest`
Expected: FAILURE на компиляции androidTest — `Unresolved reference: LocalIsDarkTheme` (в `main` его ещё нет).

- [ ] **Step 3: Переписать `Theme.kt`**

Полное содержимое:

```kotlin
package com.example.mycalendar.presentation.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.mycalendar.domain.model.ThemeMode

/** Светлая схема — производная от эталонной тёмной по тем же ролям. */
private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    secondaryContainer = LightSecondaryContainer,
    tertiary = LightTertiary,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightCard,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = LightError,
)

/** Тёмная схема — эталон визуального языка (navy из макетов). */
private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    secondaryContainer = DarkSecondaryContainer,
    tertiary = DarkTertiary,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkCard,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = DarkError,
)

/**
 * выбрана ли сейчас тёмная схема. Нужен компонентам, у которых внешний вид зависит от
 * схемы, а не только от ролей `ColorScheme`: граница карточки есть только в светлой.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * Тема приложения. Схема цветов детерминирована палитрой: динамические цвета обоев
 * (Material You) сознательно убраны — они разрушали бы визуальный язык, общий с Windows-версией.
 */
@Composable
fun MyCalendarTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkColors else LightColors

    // Цвет иконок статус-бара — по контрасту со схемой
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
```

Удалить `Color.kt`: `git rm app/src/main/java/com/example/mycalendar/presentation/theme/Color.kt`.

- [ ] **Step 4: Перевести `providerColor` на схему**

В `components/EventListItem.kt` удалить импорты `EventGoogleColor`/`EventLocalColor`/`EventYandexColor` (строки 31-33) и заменить функцию:

```kotlin
/** Цвет провайдера: полоса и точка карточки. Берётся из схемы, поэтому composable. */
@Composable
fun providerColor(provider: CalendarProvider): Color = when (provider) {
    CalendarProvider.LOCAL -> if (LocalIsDarkTheme.current) EventLocalColorDark else EventLocalColorLight
    CalendarProvider.GOOGLE -> if (LocalIsDarkTheme.current) EventGoogleColorDark else EventGoogleColorLight
    CalendarProvider.YANDEX -> if (LocalIsDarkTheme.current) EventYandexColorDark else EventYandexColorLight
}
```

Импорты: `com.example.mycalendar.presentation.theme.*` (шесть `Event*Color*`), `androidx.compose.runtime.Composable`.
Точки вызова `providerColor` остаются в composable-контексте — их три: `CalendarScreen.kt:244`, `EventListItem.kt:78`, `EventDetailsScreen.kt:136`; после правки они компилируются без изменений, и `grep -rn "providerColor" app/src` должен показать ровно эти три вызова плюс объявление.

- [ ] **Step 5: Вырезать цепочку Material You**

- `MainActivity.kt` — вызов темы без аргумента:

```kotlin
            MyCalendarTheme(themeMode = settings.themeMode) {
```

- `SettingsScreen.kt:150-169` — удалить весь `Row { … Switch(checked = state.dynamicColor, …) }` вместе с пустой строкой-разделителем перед `HorizontalDivider()`.
- `SettingsViewModel.kt` — в `SettingsUiState` удалить `val dynamicColor: Boolean = true`; в `combine(...)` убрать `settingsRepository.dynamicColor` и соответствующий параметр лямбды (было `themeMode, dynamicColor, connections, syncTimes, flags`, станет `themeMode, connections, syncTimes, flags`), удалить `dynamicColor = dynamicColor,` и всю функцию `setDynamicColor`.
- `domain/repository/SettingsRepository.kt` — удалить `val dynamicColor: Flow<Boolean>` и `suspend fun setDynamicColor(enabled: Boolean)`; в KDoc класса слева ничего не менять.
- `data/preferences/SettingsRepositoryImpl.kt` — удалить `val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")`, `override val dynamicColor`, `override suspend fun setDynamicColor`; в KDoc класса (строка 18) заменить «(тема, динамические цвета, время последней синхронизации)» на «(тема и время последней синхронизации)»; импорт `booleanPreferencesKey` оставить — он нужен `NOTIFICATIONS_ASKED`/`ONBOARDING_COMPLETED`.
- `SyncConflictEndToEndTest.kt:652,658` — удалить `override val dynamicColor: Flow<Boolean> = flowOf(false)` и `override suspend fun setDynamicColor(enabled: Boolean) {}`.
- `res/values/strings.xml:95-96` — удалить `dynamic_color` и `dynamic_color_desc`.

- [ ] **Step 6: Проверить, что упоминаний не осталось**

Run: `grep -rn "dynamicColor\|dynamic_color\|setDynamicColor\|dynamicDarkColorScheme" app/src docs --include=*.kt --include=*.xml --include=*.md`
Expected: пусто. Если найдётся — это недостающая правка шага 5, а не «легаси».

- [ ] **Step 7: JVM-тесты + сборка**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 100 тестов (было 97, +3 из `PaletteContrastTest`).

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Прогнать тест темы**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.ThemePaletteTest`
Expected: PASS, 3 теста.

- [ ] **Step 9: Commit**

```bash
git add -A app/src docs
git commit -m "feat(theme): тема собрана из палитры navy, динамические цвета убраны до DataStore"
```

---

### Task 3: Карточка события, чипы и заголовок дня

**Files:**
- Create: `app/src/main/java/com/example/mycalendar/presentation/components/EventCard.kt`
- Create: `app/src/main/java/com/example/mycalendar/presentation/components/ProviderChip.kt`
- Create: `app/src/main/java/com/example/mycalendar/presentation/components/DayHeader.kt`
- Create: `app/src/main/java/com/example/mycalendar/presentation/common/EventTagLabels.kt`
- Modify: `app/src/main/java/com/example/mycalendar/presentation/components/EventListItem.kt` (удалить composable `EventListItem`, оставить `EventOccurrence`/`providerColor`/`formatTimeRange`/`EmptyState`)
- Modify: `app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt:55,302,340,361` (вызовы)
- Modify: `app/src/main/java/com/example/mycalendar/presentation/theme/Type.kt`
- Modify: `app/src/main/res/values/strings.xml` (короткие имена провайдеров)
- Create: `app/src/androidTest/java/com/example/mycalendar/EventCardUiTest.kt`

**Interfaces:**
- Consumes: `AppShapes/AppSpacing/AppSizes` (Task 1), `LocalIsDarkTheme` и `providerColor(provider)` (Task 2), `EventOccurrence` и `formatTimeRange` (текущий `EventListItem.kt`).
- Produces:
  - `@Composable fun EventCard(occurrence: EventOccurrence, onClick: () -> Unit, modifier: Modifier = Modifier, showProviderChip: Boolean = true)`
  - `@Composable fun ProviderChip(provider: CalendarProvider, modifier: Modifier = Modifier)`
  - `@Composable fun TagChip(label: String, modifier: Modifier = Modifier)`
  - `@Composable fun DayHeader(date: LocalDate, isToday: Boolean = date == LocalDate.now(), modifier: Modifier = Modifier)`
  - `object CardTypography { val title: TextStyle; val time: TextStyle; val chip: TextStyle }` в `Type.kt`
  - `val EventTag.labelRes: Int` в `presentation/common/EventTagLabels.kt`
  - строки `provider_short_local` («Локальный»), `provider_short_google` («Google»), `provider_short_yandex` («Яндекс»).

- [ ] **Step 1: Написать падающий компонентный тест**

Создать `app/src/androidTest/java/com/example/mycalendar/EventCardUiTest.kt`:

```kotlin
package com.example.mycalendar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.model.ThemeMode
import com.example.mycalendar.presentation.components.EventCard
import com.example.mycalendar.presentation.components.EventOccurrence
import com.example.mycalendar.presentation.theme.MyCalendarTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Карточка события — один компонент на все виды календаря.
 * Проверяем, что в неё попадает из модели и что семантика остаётся «кликабельный узел
 * с названием»: на этом держится eventCard() в AppUiTest.
 */
@RunWith(AndroidJUnit4::class)
class EventCardUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val day: LocalDate = LocalDate.of(2026, 11, 5)

    private fun millis(hour: Int, minute: Int): Long =
        day.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun occurrence(
        title: String = "Созвон с подрядчиком",
        provider: CalendarProvider = CalendarProvider.YANDEX,
        reminders: List<Int> = listOf(15),
        repeat: RepeatRule? = null,
        tags: List<EventTag> = listOf(EventTag.BIRTHDAY),
        voice: String? = null,
        note: String? = null,
    ) = EventOccurrence(
        event = Event(
            id = "card-$title",
            title = title,
            startTime = millis(9, 30),
            endTime = millis(10, 15),
            reminderMinutes = reminders,
            repeatRule = repeat,
            tags = tags,
            voiceNotePath = voice,
            textNote = note,
            calendarProvider = provider,
        ),
        startMillis = millis(9, 30),
        endMillis = millis(10, 15),
    )

    private fun show(card: EventOccurrence) {
        rule.setContent {
            MyCalendarTheme(themeMode = ThemeMode.DARK) {
                EventCard(occurrence = card, onClick = { })
            }
        }
    }

    @Test
    fun `card shows time, title and provider chip`() {
        show(occurrence())
        rule.onNodeWithText("Созвон с подрядчиком").assertExists()
        rule.onNodeWithText("09:30").assertExists()
        rule.onNodeWithText("10:15").assertExists()
        rule.onNodeWithText("Яндекс").assertExists()
    }

    @Test
    fun `card renders tag chips`() {
        show(occurrence(tags = listOf(EventTag.BIRTHDAY, EventTag.CINEMA)))
        rule.onNodeWithText("День рождения").assertExists()
        rule.onNodeWithText("Кино").assertExists()
    }

    @Test
    fun `no tags means no tag chips`() {
        show(occurrence(tags = emptyList()))
        rule.onNodeWithText("День рождения").assertDoesNotExist()
    }

    @Test
    fun `title stays single line and card keeps click action`() {
        show(occurrence(title = "Очень длинное название события, которое не влезает в одну строку"))
        val card = rule.onNode(hasText("Очень длинное название события", substring = true) and hasClickAction())
        card.assertExists()
        assertTrue(card.fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
    }

    @Test
    fun `card is exactly one merged clickable node`() {
        show(occurrence())
        val nodes = rule.onAllNodes(hasText("Созвон с подрядчиком") and hasClickAction())
            .fetchSemanticsNodes()
        assertEquals(1, nodes.size)
    }
}
```

- [ ] **Step 2: Убедиться, что тест не компилируется**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.EventCardUiTest`
Expected: FAILURE, `Unresolved reference: EventCard`.

- [ ] **Step 3: Строки и подписи меток**

В `res/values/strings.xml` сразу после `provider_local` (строка 113) добавить:

```xml
    <string name="provider_short_local">Локальный</string>
    <string name="provider_short_google">Google</string>
    <string name="provider_short_yandex">Яндекс</string>
```

Создать `presentation/common/EventTagLabels.kt`:

```kotlin
package com.example.mycalendar.presentation.common

import androidx.annotation.StringRes
import com.example.mycalendar.R
import com.example.mycalendar.domain.model.EventTag

/** Подпись метки события: чипы в карточке, детали и редактор. */
@get:StringRes
val EventTag.labelRes: Int
    get() = when (this) {
        EventTag.BIRTHDAY -> R.string.tag_birthday
        EventTag.DATE -> R.string.tag_date
        EventTag.CINEMA -> R.string.tag_cinema
    }
```

- [ ] **Step 4: Типографика карточки**

В `theme/Type.kt` правки в `Typography` (`titleMedium` 15sp, `bodyMedium` 13sp, `headlineMedium` 26sp) и новый объект:

```kotlin
/** Стили карточки события: название, колонка времени, чип. */
object CardTypography {
    val title: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )

    // Табличные цифры: без tnum колонка времени «прыгает» и карточки не выравниваются.
    val time: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontFeatureSettings = "tnum",
    )

    val chip: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    )
}
```

И заменить значения в существующей шкале: `headlineMedium` → `fontSize = 26.sp, lineHeight = 34.sp`; `titleMedium` → `fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp`; `bodyMedium` → `fontSize = 13.sp, lineHeight = 18.sp`.

- [ ] **Step 5: Чипы**

Создать `components/ProviderChip.kt`:

```kotlin
package com.example.mycalendar.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.mycalendar.R
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.presentation.theme.AppShapes
import com.example.mycalendar.presentation.theme.CardTypography

@Composable
private fun providerShortName(provider: CalendarProvider): String = stringResource(
    when (provider) {
        CalendarProvider.LOCAL -> R.string.provider_short_local
        CalendarProvider.GOOGLE -> R.string.provider_short_google
        CalendarProvider.YANDEX -> R.string.provider_short_yandex
    },
)

/** Чип провайдера: точка в его цвете + короткое имя. */
@Composable
fun ProviderChip(provider: CalendarProvider, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(AppShapes.chip)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Spacer(
            modifier = Modifier
                .size(6.dp)
                .background(providerColor(provider), AppShapes.dayMarker),
        )
        Text(
            text = providerShortName(provider),
            style = CardTypography.chip,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Чип метки события — тот же контур, но нейтральный фон. */
@Composable
fun TagChip(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = CardTypography.chip,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(AppShapes.chip)
            .background(MaterialTheme.colorScheme.surface)
            .border(AppSizes.stroke, MaterialTheme.colorScheme.outline, AppShapes.chip)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
```

Импорты в `ProviderChip.kt`: `androidx.compose.foundation.border`, `com.example.mycalendar.presentation.theme.AppSizes` (`AppSizes.stroke` добавлен в `Tokens.kt` на Task 1).

- [ ] **Step 6: Карточка**

Создать `components/EventCard.kt`:

```kotlin
package com.example.mycalendar.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.mycalendar.R
import com.example.mycalendar.presentation.common.labelRes
import com.example.mycalendar.presentation.common.timeFormatter
import com.example.mycalendar.presentation.theme.AppShapes
import com.example.mycalendar.presentation.theme.AppSizes
import com.example.mycalendar.presentation.theme.AppSpacing
import com.example.mycalendar.presentation.theme.CardTypography
import com.example.mycalendar.presentation.theme.LocalIsDarkTheme
import java.time.Instant
import java.time.ZoneId

/**
 * Карточка события: полоса провайдера, колонка времени, название, чипы и индикаторы.
 * Единый компонент для видов Месяц/Неделя/День — они различаются только группировкой.
 */
@Composable
fun EventCard(
    occurrence: EventOccurrence,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showProviderChip: Boolean = true,
) {
    val event = occurrence.event
    val dark = LocalIsDarkTheme.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = AppShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (dark) {
            null
        } else {
            BorderStroke(AppSizes.stroke, MaterialTheme.colorScheme.outline)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(AppSpacing.providerStripe)
                    .background(providerColor(event.calendarProvider)),
            )
            Column(
                modifier = Modifier.width(AppSizes.timeColumn),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = timeText(occurrence.startMillis),
                    style = CardTypography.time,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = timeText(occurrence.endMillis),
                    style = CardTypography.time,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = AppSpacing.cardHPad, vertical = AppSpacing.cardVPad),
            ) {
                Text(
                    text = event.title,
                    style = CardTypography.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showProviderChip) ProviderChip(event.calendarProvider)
                    event.tags.forEach { tag ->
                        TagChip(stringResource(tag.labelRes))
                    }
                }
            }
            Row(
                modifier = Modifier.padding(end = AppSpacing.cardHPad),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (event.reminderMinutes.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppSizes.iconMedium),
                    )
                }
                if (occurrence.isRecurring) {
                    Icon(
                        imageVector = Icons.Filled.Repeat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppSizes.iconMedium),
                    )
                }
                if (event.voiceNotePath != null) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppSizes.iconMedium),
                    )
                }
                if (!event.textNote.isNullOrBlank()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.StickyNote2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppSizes.iconMedium),
                    )
                }
            }
        }
    }
}

private fun timeText(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFormatter)
```

Импорты, которые здесь легко забыть: `androidx.compose.ui.unit.dp`, `androidx.compose.foundation.BorderStroke`.

- [ ] **Step 7: Заголовок дня**

Создать `components/DayHeader.kt`:

```kotlin
package com.example.mycalendar.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.mycalendar.R
import com.example.mycalendar.presentation.common.dayTitleFormatter
import com.example.mycalendar.presentation.common.russianLocale
import com.example.mycalendar.presentation.theme.AppShapes
import com.example.mycalendar.presentation.theme.AppSizes
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Заголовок дня в недельном виде: «Сегодня, 5 ноября» или «вторник, 5 ноября».
 * Точка акцентного цвета — единственный признак сегодняшнего дня, чтобы
 * текст не красился акцентом и оставался в контрасте AA.
 */
@Composable
fun DayHeader(
    date: LocalDate,
    modifier: Modifier = Modifier,
    isToday: Boolean = date == LocalDate.now(),
) {
    val title = if (isToday) {
        stringResource(R.string.today) + ", " + date.format(dayTitleFormatter)
    } else {
        date.format(weekdayTitleFormatter).replaceFirstChar { it.uppercase(russianLocale) }
    }
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        // minTouch высота не нужна: заголовок не кликабелен
    ) {
        if (isToday) {
            Spacer(
                modifier = Modifier
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, AppShapes.dayMarker),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.height(AppSizes.dayHeaderMinHeight),
        )
    }
}
```

Импорт `weekdayTitleFormatter` добавить (`com.example.mycalendar.presentation.common.weekdayTitleFormatter`).

- [ ] **Step 8: Удалить старый компонент и подключить новый**

В `components/EventListItem.kt` удалить composable `EventListItem` (строки 53-133) и ставшие ненужными импорты (`Card`, `CardDefaults`, `Icons`, `Icon`, `CircleShape`, `background`, `clickable`, `Text` — проверить по остатку файла). `EmptyState`, `EventOccurrence`, `providerColor`, `formatTimeRange` остаются (`formatTimeRange` остаётся, потому что его печатают детали события; если после правки Task 6 останется ноль вызовов — удалить).
В `presentation/calendar/CalendarScreen.kt`: импорт `EventListItem` → `EventCard` (строка 55), вызовы в `WeekView:302`, `DayView:340`, `EventList:361` — `EventCard(occurrence = occurrence, onClick = { onEventClick(occurrence.event.id) })`.

- [ ] **Step 9: Прогнать тесты**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.EventCardUiTest`
Expected: PASS, 5 тестов.

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (100 тестов).

- [ ] **Step 10: Commit**

```bash
git add app/src docs
git commit -m "feat(ui): карточка события с полосой провайдера, чипами и заголовком дня"
```

---

### Task 4: Недельный вид — карточки по дням

**Files:**
- Modify: `app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt:267-307` (`WeekView`)
- Create: `app/src/androidTest/java/com/example/mycalendar/WeekViewCardsUiTest.kt`

**Interfaces:**
- Consumes: `EventCard`, `DayHeader`, `AppSpacing.dayGap`, `AppSpacing.screen` (Task 1/3).
- Produces: `WeekView` с ключами элементов `header_<date>`, `empty_<date>`, `<eventId>_<startMillis>` — на них опирается тест из шага 1.

- [ ] **Step 1: Написать падающий тест недельного вида**

Создать `app/src/androidTest/java/com/example/mycalendar/WeekViewCardsUiTest.kt`:

```kotlin
package com.example.mycalendar

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * Недельный вид показывает карточки по дням, а не плоский список: заголовок дня,
 * карточка события, тихая строка в пустой день.
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WeekViewCardsUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var eventRepository: EventRepository

    private val suffix = System.nanoTime().toString()
    private val title = "Недельная карточка $suffix"
    private val eventId = "week-card-$suffix"

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

        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        runBlocking {
            eventRepository.saveFromUser(
                Event(
                    id = eventId,
                    title = title,
                    startTime = start,
                    endTime = start + 45 * 60 * 1_000L,
                    reminderMinutes = listOf(15),
                    calendarProvider = CalendarProvider.LOCAL,
                )
            )
        }
    }

    private fun nodesWithText(text: String, substring: Boolean = true) =
        composeRule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true)

    private fun reachCalendar() {
        val res = composeRule.activity.resources
        val enter = res.getString(R.string.enter_app)
        composeRule.waitUntil(20_000) {
            nodesWithText(enter).fetchSemanticsNodes().isNotEmpty() ||
                nodesWithText(res.getString(R.string.view_month)).fetchSemanticsNodes().isNotEmpty()
        }
        if (nodesWithText(enter).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText(enter).performClick()
        }
        composeRule.waitUntil(20_000) {
            nodesWithText(res.getString(R.string.view_month)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `week view shows day header and event card`() {
        val res = composeRule.activity.resources
        reachCalendar()
        composeRule.onNodeWithText(res.getString(R.string.view_week)).performClick()

        // Сегодняшний заголовок: «Сегодня, 5 ноября»
        composeRule.waitUntil(10_000) {
            nodesWithText("Сегодня", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Карточка события — кликабельный узел с названием
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText(title) and hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Карточка несёт время и провайдера
        assertTrue(nodesWithText("09:30").fetchSemanticsNodes().isNotEmpty())
        assertTrue(nodesWithText(res.getString(R.string.provider_short_local))
            .fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `empty day shows a quiet line instead of a card`() {
        val res = composeRule.activity.resources
        reachCalendar()
        composeRule.onNodeWithText(res.getString(R.string.view_week)).performClick()
        composeRule.waitUntil(10_000) {
            nodesWithText(res.getString(R.string.no_events)).fetchSemanticsNodes().isNotEmpty()
        }
        // «Нет событий» — текст без действия клика
        composeRule.onAllNodes(hasText(res.getString(R.string.no_events)) and hasClickAction())
            .assertCountEquals(0)
    }
}
```

Импорты для `assertCountEquals`/`onAllNodes`: `androidx.compose.ui.testassertCountEquals`, `androidx.compose.ui.test.onAllNodes`.

- [ ] **Step 2: Убедиться, что тест падает**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.WeekViewCardsUiTest`
Expected: FAILURE — «Сегодня» не находится (в `WeekView` сейчас `shortDateTitle(date)` без слова «Сегодня»).

- [ ] **Step 3: Переписать группировку в `WeekView`**

Заменить тело `WeekView` (строки 267-307) на:

```kotlin
@Composable
private fun WeekView(
    state: CalendarUiState,
    occurrences: List<EventOccurrence>,
    onEventClick: (String) -> Unit,
    eventsOn: (LocalDate, List<EventOccurrence>) -> List<EventOccurrence>,
) {
    val weekStart = state.selectedDate.with(DayOfWeek.MONDAY)
    val days = remember(weekStart) { (0..6).map { weekStart.plusDays(it.toLong()) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = AppSpacing.screen,
            vertical = 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.cardGap),
    ) {
        days.forEach { date ->
            val dayEvents = eventsOn(date, occurrences)
            item(key = "header_$date") {
                DayHeader(
                    date = date,
                    modifier = Modifier.padding(top = AppSpacing.dayGap - AppSpacing.cardGap),
                )
            }
            if (dayEvents.isEmpty()) {
                item(key = "empty_$date") {
                    Text(
                        text = stringResource(R.string.no_events),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(dayEvents, key = { "${it.event.id}_${it.startMillis}" }) { occurrence ->
                    EventCard(
                        occurrence = occurrence,
                        onClick = { onEventClick(occurrence.event.id) },
                    )
                }
            }
        }
    }
}
```

Добавить импорты: `com.example.mycalendar.presentation.components.DayHeader`, `com.example.mycalendar.presentation.theme.AppSpacing`. Удалить ставший неиспользуемым `shortDateTitle` (строка 395), если больше нигде не вызывается (`grep -n shortDateTitle` по файлу должен дать 0 совпадений).

- [ ] **Step 4: Прогнать тест недели**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.WeekViewCardsUiTest`
Expected: PASS, 2 теста.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat(calendar): недельный вид собран из заголовков дня и карточек событий"
```

---

### Task 5: Месяц, день и шапка по токенам

**Files:**
- Modify: `app/src/main/java/com/example/mycalendar/presentation/calendar/CalendarScreen.kt:78-143` (шапка, сегменты, стрелки, FAB), `:174-263` (`MonthView`), `:311-365` (`DayView`, `EventList`)

**Interfaces:**
- Consumes: `EventCard`, `DayHeader` (Task 3/4), `AppShapes/AppSpacing/AppSizes`, `LocalIsDarkTheme`.
- Produces: ничего нового для других задач; поведение `viewModel::selectDate/goToday/goNext/goPrevious` не меняется.

- [ ] **Step 1: Сегменты, стрелки и FAB по токенам**

`SingleChoiceSegmentedButtonRow` (строки 109-126): `contentPadding` → `horizontal = AppSpacing.screen, vertical = 8.dp`, внутрь `SegmentedButton` добавить `modifier = Modifier.height(AppSizes.segmentedHeight)`.
`ExtendedFloatingActionButton` (95-100): `shape = AppShapes.card`.
Row со стрелками (129-143): `horizontal = 8.dp` остаётся, `IconButton`'ы не трогать (их размер 48 dp даёт Material).
`TopAppBar` (80-93): `containerColor = MaterialTheme.colorScheme.surface`, заголовок — `style = MaterialTheme.typography.headlineMedium` внутри `Text(text = headerTitle(state), style = …)`.

- [ ] **Step 2: Сетка месяца**

В `MonthView` заменить оформление клетки (строки 210-224, то есть модификатор `Column` вместе с `.clickable`/`.padding`) на:

```kotlin
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp)
                            .background(
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surface
                                },
                                shape = AppShapes.calendarCell,
                            )
                            .border(
                                width = if (isToday && !isSelected) AppSizes.stroke else 0.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = AppShapes.calendarCell,
                            )
                            .clickable { onDayClick(date) }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
```

`isToday`, `isSelected` и `dayEvents` уже объявлены в строках 205-208 — переиспользуем, не объявляя повторно. Текст дня внутри той же `Column`:

```kotlin
                        Text(
                            text = date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
```

Точки событий остаются `5.dp` и `CircleShape` (это геометрия клетки сетки, не дизайн-токен), но цвет берётся без полностью квалифицированного имени:

```kotlin
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .background(
                                            providerColor(occurrence.event.calendarProvider),
                                            CircleShape,
                                        ),
                                )
```

Импорты: `androidx.compose.foundation.border`, `com.example.mycalendar.presentation.components.providerColor`, `com.example.mycalendar.presentation.theme.AppShapes`, `AppSizes`, `AppSpacing`.

Заголовок выбранного дня (строки 256-260) заменяется целиком:

```kotlin
        DayHeader(date = state.selectedDate)
```

`fullDateTitle` остаётся в файле — его вызывают `headerTitle` и `DayView`.
Заголовок выбранного дня (256-260): `DayHeader(date = state.selectedDate)` вместо `Text(fullDateTitle(...))`; `fullDateTitle` остаётся — его использует `headerTitle` и `DayView`.
Импорт `androidx.compose.foundation.border`.

- [ ] **Step 3: Список под сеткой и вид «День»**

`EventList` (348-365): `contentPadding` горизонталь → `AppSpacing.screen`, `verticalArrangement = Arrangement.spacedBy(AppSpacing.cardGap)`; вызов `EventCard` уже стоит после Task 3.

`DayView` (311-344): `contentPadding` горизонталь → `AppSpacing.screen`, `verticalArrangement = Arrangement.spacedBy(AppSpacing.cardGap)`; первый `item` с `Text(headerTitle(state), style = titleLarge)` заменяется на один `DayHeader` — две строки даты над списком не нужны:

```kotlin
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = AppSpacing.screen,
            vertical = 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.cardGap),
    ) {
        item(key = "header_${state.selectedDate}") {
            DayHeader(date = state.selectedDate)
        }
        if (dayEvents.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.no_events),
                    subtitle = stringResource(R.string.no_events_hint),
                )
            }
        } else {
            items(dayEvents, key = { "${it.event.id}_${it.startMillis}" }) { occurrence ->
                EventCard(
                    occurrence = occurrence,
                    onClick = { onEventClick(occurrence.event.id) },
                )
            }
        }
    }
```

Чип провайдера в дне оставляем (`showProviderChip` по умолчанию `true`): больше о происхождении события в этом виде сказать нечего, а шапка экрана показывает только дату. `EmptyState` в пустом дне остаётся.

- [ ] **Step 4: Регрессия UI-теста календаря**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.mycalendar.AppUiTest,com.example.mycalendar.WeekViewCardsUiTest`
Expected: PASS (7 + 2 теста). Именно здесь ловится, что FAB/сегменты остались кликабельными, а `calendar_fabPrefillsSelectedDay` находит выбранную дату.

- [ ] **Step 5: Живой просмотр**

Run: `./gradlew :app:installDebug`
Затем на `emulator-5554` открыть приложение как гость и посмотреть Месяц/Неделя/День в обеих схемах (Настройки → Тёмная/Светлая); снять скриншоты:

```bash
MSYS_NO_PATHCONV=1 "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s emulator-5554 exec-out screencap -p > /c/Users/obogr/AppData/Local/Temp/redesign-month.png
```

(так же `redesign-week.png`, `redesign-day.png`, плюс по одному кадру светлой схемы). Открыть снимки через Read и проверить: карточки читаются, время выровнено в колонку, пустой день — тихая строка, ничего не обрезано.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(calendar): месяц, день и шапка переведены на токены новой палитры"
```

---

### Task 6: Остальные экраны и линт

**Files:**
- Modify: `app/src/main/java/com/example/mycalendar/presentation/event/EventDetailsScreen.kt:249`
- Modify: `app/src/main/java/com/example/mycalendar/presentation/settings/SettingsScreen.kt:551`
- Modify: экраны `auth/`, `conflict/` — только если `grep` найдёт в них жёсткие цвета или `RoundedCornerShape(<число>)`

**Interfaces:**
- Consumes: `AppShapes`, `AppSpacing`, `AppSizes`, `CardTypography`, `LocalIsDarkTheme`.
- Produces: ничего для других задач.

- [ ] **Step 1: Найти все жёсткие значения вне токенов**

Run: `grep -rn "Color(0x\|RoundedCornerShape([0-9]\|\.dp)" app/src/main/java/com/example/mycalendar/presentation app/src/main/java/com/example/mycalendar/notifications | grep -v "presentation/theme" | head -40`
Expected: список правок. `presentation/components/ProviderChip.kt` и `EventCard.kt` уже используют токены; внутренние `8.dp` чипов и отступы Material-компонентов правке не подлежат — это часть компонента, а не дизайн-токен.

- [ ] **Step 2: Карточка деталей события**

`EventDetailsScreen.kt:249` — заменить на:

```kotlin
    Card(
        shape = AppShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (LocalIsDarkTheme.current) {
            null
        } else {
            BorderStroke(AppSizes.stroke, MaterialTheme.colorScheme.outline)
        },
    ) {
```

Импорты: `AppShapes`, `AppSizes`, `LocalIsDarkTheme`, `androidx.compose.material3.CardDefaults`, `androidx.compose.foundation.BorderStroke`, `androidx.compose.ui.unit.dp`.

- [ ] **Step 3: Белый цвет в настройках**

`SettingsScreen.kt:551` — `color = Color.White` → `color = MaterialTheme.colorScheme.onPrimary`. Если рядом жёсткий фон `Color(0x…)`, заменить на `MaterialTheme.colorScheme.primary`.

- [ ] **Step 4: Один источник подписей меток**

Три экрана держали свой `when` по `EventTag` — сводим к `labelRes` из Task 3, не меняя поведения.

`presentation/event/EventDetailsScreen.kt:317-322` — тело функции становится однострочным:

```kotlin
@Composable
private fun tagLabel(tag: EventTag): Int = tag.labelRes
```

`presentation/conflict/ConflictDetailScreen.kt:409-414` — то же:

```kotlin
@Composable
private fun tagLabel(tag: EventTag): Int = tag.labelRes
```

`presentation/event/CreateEditEventScreen.kt:93-98` — список чекбоксов строится из общего источника:

```kotlin
/** Метки события: чекбоксы День рождения / Свидание / Кино. */
private val tagOptions: List<Pair<EventTag, Int>> = EventTag.entries.map { it to it.labelRes }
```

Импорт `com.example.mycalendar.presentation.common.labelRes` добавить во все три файла. `@StringRes`-аннотацию у приватных `tagLabel` оставить как есть (она уже проставлена в этих файлах).

- [ ] **Step 5: Линт и JVM**

Run: `./gradlew :app:lintDebug`
Expected: 0 ошибок, 10 предупреждений (как до redesign). Удалённые `dynamic_color*` строки не должны оставить неиспользуемых ресурсов.

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "fix(ui): карточки деталей и настроек сняты с жёстких цветов в пользу токенов"
```

---

### Task 7: Полный прогон, счётчики и скриншоты

**Files:**
- Modify: `README.md`
- Modify: `docs/how-to-verify.md`
- Create: `docs/screenshots/` (пересъёмка) — путь тот же, что уже используют ссылки в README

**Interfaces:**
- Consumes: всё, что сделано в Task 1-6.
- Produces: задокументированное состояние для Windows-ветки.

- [ ] **Step 1: Полный прогон**

Run: `./gradlew :app:testDebugUnitTest :app:lintDebug`
Expected: JVM PASS, 100 тестов (было 97, +3 контраста из Task 1; остальные новые тесты — instrumented). Lint: 0 ошибок, 10 предупреждений.

Run: `./gradlew :app:connectedDebugAndroidTest`
Expected: **51 тест** (41 прежних + 3 `ThemePaletteTest` + 5 `EventCardUiTest` + 2 `WeekViewCardsUiTest`). Число сверить с отчётом `app/build/reports/androidTests/connected/index.html` и записать фактическое; падает только `SchedulerAndMediaTest.voiceRecorder_recordsM4aFile` (assumption).
Помнить: прогон снимает приложение и стирает данные — после него вход в Яндекс и подключённые календари пользователь восстанавливает сам.

- [ ] **Step 2: Пересъёмка скриншотов README**

`./gradlew :app:installDebug`, затем гостевой режим: Месяц (тёмная), Неделя (тёмная), День (тёмная), Месяц (светлая), Настройки (тёмная) — команды `adb exec-out screencap` как в Task 5 шаг 5, файлы в `docs/screenshots/` с теми же именами, что ссылает `README.md` (проверить список: `grep -n "screenshots/" README.md`).

- [ ] **Step 3: Обновить текст и счётчики**

В `README.md` — счётчики тестов и одна строка о новом визуальном языке («тёмно-синяя тема, карточки событий во всех видах; палитра и токены — единый источник для Windows-версии»). В `docs/how-to-verify.md` — блок «Визуальный язык»: команды прогонов, ожидаемые числа, ссылка на спец, и напоминание, что connected-прогон стирает данные.

- [ ] **Step 4: Проверить цифры в доках against фактического вывода**

Run: `grep -n "тест" README.md docs/how-to-verify.md | head -20`
Expected: каждая строка со счётчиком совпадает с отчётом из шага 1; расхождение — править док, а не память.

- [ ] **Step 5: Commit**

```bash
git add README.md docs
git commit -m "docs: визуальный язык navy — скриншоты и актуальные счётчики тестов"
```

## Самопроверка плана по спеку

- Раздел 1 (палитра, цвета провайдеров, контраст) → Task 1 + Task 2; значение светлого `primary` поправлено по замеру, сноска в спеке — Task 1 шаг 6.
- Раздел 2 (токены, типографика, `tnum`) → Task 1 шаг 4 и Task 3 шаг 4.
- Раздел 3 (`EventCard`, `DayHeader`, `ProviderChip`, сохранение semantics для `eventCard()` в `AppUiTest`) → Task 3; проверка мержа — тест `card is one merged clickable node`.
- Раздел 4, `CalendarScreen` (неделя карточками, месяц/день/шапка по токенам) → Task 4 + Task 5.
- Раздел 4, прочие экраны (`EventDetailsScreen.kt:249`, `SettingsScreen.kt:551`, подписи меток) → Task 6.
- Раздел 4, таблица вырезания Material You (8 мест) → Task 2 шаг 5, контроль — `grep` шага 6.
- Раздел 4, проверка (JVM / lint / connected / живой прогон / контраст / доки) → шаги 4-5 Task 5, шаг 5 Task 6, весь Task 7.
- «Вне объёма» спека не нарушен: нижняя навигация, новые экраны и 7-колоночная сетка в задачах отсутствуют.
