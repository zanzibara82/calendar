package com.example.mycalendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ThemeMode
import com.example.mycalendar.presentation.components.providerColor
import com.example.mycalendar.presentation.theme.DarkBackground
import com.example.mycalendar.presentation.theme.DarkCard
import com.example.mycalendar.presentation.theme.DarkOnSurface
import com.example.mycalendar.presentation.theme.DarkOnSurfaceVariant
import com.example.mycalendar.presentation.theme.DarkOutline
import com.example.mycalendar.presentation.theme.DarkPrimary
import com.example.mycalendar.presentation.theme.DarkSurface
import com.example.mycalendar.presentation.theme.EventGoogleColorDark
import com.example.mycalendar.presentation.theme.EventGoogleColorLight
import com.example.mycalendar.presentation.theme.EventLocalColorDark
import com.example.mycalendar.presentation.theme.EventLocalColorLight
import com.example.mycalendar.presentation.theme.EventYandexColorDark
import com.example.mycalendar.presentation.theme.EventYandexColorLight
import com.example.mycalendar.presentation.theme.LightBackground
import com.example.mycalendar.presentation.theme.LightCard
import com.example.mycalendar.presentation.theme.LightOnSurface
import com.example.mycalendar.presentation.theme.LightOnSurfaceVariant
import com.example.mycalendar.presentation.theme.LightOutline
import com.example.mycalendar.presentation.theme.LightPrimary
import com.example.mycalendar.presentation.theme.LightSurface
import com.example.mycalendar.presentation.theme.LocalIsDarkTheme
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
    fun theme_darkModeUsesNavyPalette() {
        rule.setContent {
            MyCalendarTheme(themeMode = ThemeMode.DARK) {
                assertEquals(DarkPrimary, MaterialTheme.colorScheme.primary)
                assertEquals(DarkCard, MaterialTheme.colorScheme.surfaceVariant)
                assertEquals(DarkOnSurface, MaterialTheme.colorScheme.onSurface)
                assertEquals(DarkOnSurfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                // Роль surface нужна уже не чипу метки (тот перешёл на surfaceVariant), а ячейкам
                // сетки месяца: карточка и чипы живут на surfaceVariant, фон и сетка — на этих трёх.
                assertEquals(DarkBackground, MaterialTheme.colorScheme.background)
                assertEquals(DarkSurface, MaterialTheme.colorScheme.surface)
                assertEquals(DarkOutline, MaterialTheme.colorScheme.outline)
                assertTrue(LocalIsDarkTheme.current)
            }
        }
    }

    @Test
    fun theme_lightModeUsesDerivedPalette() {
        rule.setContent {
            MyCalendarTheme(themeMode = ThemeMode.LIGHT) {
                assertEquals(LightPrimary, MaterialTheme.colorScheme.primary)
                assertEquals(LightCard, MaterialTheme.colorScheme.surfaceVariant)
                assertEquals(LightOnSurface, MaterialTheme.colorScheme.onSurface)
                assertEquals(LightOnSurfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                assertEquals(LightBackground, MaterialTheme.colorScheme.background)
                assertEquals(LightSurface, MaterialTheme.colorScheme.surface)
                assertEquals(LightOutline, MaterialTheme.colorScheme.outline)
                assertFalse(LocalIsDarkTheme.current)
            }
        }
    }

    @Test
    fun theme_providerColorFollowsScheme() {
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
}
