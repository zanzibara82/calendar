package com.example.mycalendar.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment
import kotlin.math.min

private const val DESIRED_WIDTH_DP = 1500f
private const val DESIRED_HEIGHT_DP = 1140f
private const val SCREEN_MARGIN_DP = 64f

/**
 * Размер окна при первом запуске: полтора прежних 1000×760, но не больше экрана.
 *
 * Пересчёт из пикселей в dp идёт через масштаб системы: при зуме 125% экран 1920×1080
 * даёт 1536×864 логических точек, и без подгонки низ окна ушёл бы за границу.
 */
internal fun initialWindowSize(
    screenWidthPx: Int,
    screenHeightPx: Int,
    scaleX: Float = 1f,
    scaleY: Float = scaleX,
): DpSize = DpSize(
    width = min(DESIRED_WIDTH_DP, screenWidthPx / scaleX - SCREEN_MARGIN_DP).dp,
    height = min(DESIRED_HEIGHT_DP, screenHeightPx / scaleY - SCREEN_MARGIN_DP).dp,
)

/** Экран, на котором откроется окно: рабочий стол по умолчанию. */
internal fun defaultWindowSize(): DpSize {
    val configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice
        .defaultConfiguration
    val transform = configuration.defaultTransform
    return initialWindowSize(
        screenWidthPx = configuration.bounds.width,
        screenHeightPx = configuration.bounds.height,
        scaleX = transform.scaleX.toFloat(),
        scaleY = transform.scaleY.toFloat(),
    )
}
