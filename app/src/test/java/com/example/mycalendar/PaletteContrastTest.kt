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
