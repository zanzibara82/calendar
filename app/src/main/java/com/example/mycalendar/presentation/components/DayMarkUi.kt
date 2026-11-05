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
