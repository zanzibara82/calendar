package com.example.mycalendar

import androidx.compose.ui.graphics.Color
import com.example.mycalendar.domain.holiday.DayMark
import com.example.mycalendar.presentation.theme.DarkBackground
import com.example.mycalendar.presentation.theme.DarkCard
import com.example.mycalendar.presentation.theme.DarkOnPrimary
import com.example.mycalendar.presentation.theme.DarkOnPrimaryContainer
import com.example.mycalendar.presentation.theme.DarkOnSurface
import com.example.mycalendar.presentation.theme.DarkOnSurfaceVariant
import com.example.mycalendar.presentation.theme.DarkPrimary
import com.example.mycalendar.presentation.theme.DarkPrimaryContainer
import com.example.mycalendar.presentation.theme.EventGoogleColorDark
import com.example.mycalendar.presentation.theme.EventGoogleColorLight
import com.example.mycalendar.presentation.theme.EventLocalColorDark
import com.example.mycalendar.presentation.theme.EventLocalColorLight
import com.example.mycalendar.presentation.theme.EventYandexColorDark
import com.example.mycalendar.presentation.theme.EventYandexColorLight
import com.example.mycalendar.presentation.theme.LightBackground
import com.example.mycalendar.presentation.theme.LightCard
import com.example.mycalendar.presentation.theme.LightOnPrimary
import com.example.mycalendar.presentation.theme.LightOnPrimaryContainer
import com.example.mycalendar.presentation.theme.LightOnSurface
import com.example.mycalendar.presentation.theme.LightOnSurfaceVariant
import com.example.mycalendar.presentation.theme.LightPrimary
import com.example.mycalendar.presentation.theme.LightPrimaryContainer
import com.example.mycalendar.presentation.theme.dayBorder
import com.example.mycalendar.presentation.theme.dayMarkContainer
import com.example.mycalendar.presentation.theme.weekdayContainer
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Контраст новой палитры по WCAG 2.1 (relative luminance).
 *
 * Проверяем пары «текст на фоне», которые видит пользователь: текст карточки на фоне
 * карточки, подписи на фоне экрана, текст на акценте. Отдельная проверка — плашка провайдера:
 * она графика, поэтому у неё порог 3:1, а не AA. Числа — из спека, секция 1.
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

    @Test
    fun `provider plaque letter meets graphics contrast`() {
        // Буква на плашке дублирует название провайдера в соседней строке, поэтому плашка —
        // графика, а не текст: порог 3:1. Ниже AA эта пара сознательно, иначе единственный
        // выход — двигать цвет провайдера, а он калиброван под полосу карточки события.
        assertAtLeast(GRAPHICS, DarkOnPrimary, EventLocalColorDark)
        assertAtLeast(GRAPHICS, DarkOnPrimary, EventGoogleColorDark)
        assertAtLeast(GRAPHICS, DarkOnPrimary, EventYandexColorDark)
        assertAtLeast(GRAPHICS, LightOnPrimary, EventLocalColorLight)
        assertAtLeast(GRAPHICS, LightOnPrimary, EventGoogleColorLight)
        assertAtLeast(GRAPHICS, LightOnPrimary, EventYandexColorLight)
    }

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

    @Test
    fun `weekday tone keeps day text at AA`() {
        assertAtLeast(AA, DarkOnSurface, weekdayContainer(dark = true))
        assertAtLeast(AA, LightOnSurface, weekdayContainer(dark = false))
        assertAtLeast(AA, DarkOnSurfaceVariant, weekdayContainer(dark = true))
        assertAtLeast(AA, LightOnSurfaceVariant, weekdayContainer(dark = false))
    }

    @Test
    fun `day border stays visible on every day tone`() {
        // Рамка — не текст, поэтому порог ниже AA, но линия, совпадающая с тоном,
        // не отделяет клетки вовсе (у прежней рамки desktop разница была около 1.0).
        for (dark in listOf(true, false)) {
            val border = dayBorder(dark)
            listOf(
                weekdayContainer(dark),
                dayMarkContainer(DayMark.WEEKEND, dark),
                dayMarkContainer(DayMark.HOLIDAY, dark),
            ).forEach { tone ->
                assertAtLeast(BORDER_VISIBILITY, border, tone)
            }
        }
    }

    private fun assertAtLeast(min: Double, fg: Color, bg: Color) {
        val ratio = wcagRatio(fg, bg)
        assertTrue("Контраст $ratio < $min: fg=$fg bg=$bg", ratio >= min)
    }

    private companion object {
        const val AA = 4.5
        const val GRAPHICS = 3.0
        const val BORDER_VISIBILITY = 1.3
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
