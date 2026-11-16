package com.example.mycalendar.desktop.settings

import androidx.compose.ui.input.key.Key

private const val PAGE_FRACTION = 0.9f

/**
 * На сколько пикселей прокрутить настройки по нажатой клавише; null — клавиша не наша
 * и должна достаться элементу в фокусе.
 */
internal fun scrollDeltaFor(key: Key, viewportSize: Int, stepPx: Float): Float? = when (key) {
    Key.DirectionUp -> -stepPx
    Key.DirectionDown -> stepPx
    Key.PageUp -> -viewportSize * PAGE_FRACTION
    Key.PageDown -> viewportSize * PAGE_FRACTION
    else -> null
}
