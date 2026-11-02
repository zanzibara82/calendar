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
import androidx.compose.ui.unit.dp
import com.example.mycalendar.R
import com.example.mycalendar.presentation.common.dayTitleFormatter
import com.example.mycalendar.presentation.common.russianLocale
import com.example.mycalendar.presentation.common.weekdayTitleFormatter
import com.example.mycalendar.presentation.theme.AppShapes
import com.example.mycalendar.presentation.theme.AppSizes
import java.time.LocalDate

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
