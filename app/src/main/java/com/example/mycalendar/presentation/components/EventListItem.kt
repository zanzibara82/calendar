package com.example.mycalendar.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.presentation.theme.AppShapes
import com.example.mycalendar.presentation.theme.AppSizes
import com.example.mycalendar.presentation.theme.EventGoogleColorDark
import com.example.mycalendar.presentation.theme.EventGoogleColorLight
import com.example.mycalendar.presentation.theme.EventLocalColorDark
import com.example.mycalendar.presentation.theme.EventLocalColorLight
import com.example.mycalendar.presentation.theme.EventYandexColorDark
import com.example.mycalendar.presentation.theme.EventYandexColorLight
import com.example.mycalendar.presentation.theme.LocalIsDarkTheme

/** Одно конкретное вхождение события (для повторяющихся — вычисленное вхождение). */
data class EventOccurrence(
    val event: com.example.mycalendar.domain.model.Event,
    val startMillis: Long,
    val endMillis: Long,
) {
    val isRecurring: Boolean get() = event.repeatRule != null
}

/** Цвет провайдера: полоса и точка карточки. Берётся из схемы, поэтому composable. */
@Composable
fun providerColor(provider: CalendarProvider): Color = when (provider) {
    CalendarProvider.LOCAL -> if (LocalIsDarkTheme.current) EventLocalColorDark else EventLocalColorLight
    CalendarProvider.GOOGLE -> if (LocalIsDarkTheme.current) EventGoogleColorDark else EventGoogleColorLight
    CalendarProvider.YANDEX -> if (LocalIsDarkTheme.current) EventYandexColorDark else EventYandexColorLight
}

/**
 * Точка провайдера. Обводка нужна потому, что на тонированном фоне выходного или праздника
 * часть цветов не держит порог графики 3.0: у локального календаря в тёмной схеме — 2.58 на
 * бирюзовом и 2.86 на песочном, тогда как остальные провайдеры падают лишь до 3.03.
 * Обводка стоит на всех фонах, а не только на тонированных: вид точек меняется
 * единообразно, и компоненте не нужно знать, какой под ним тон.
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

/** Пустое состояние списка. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.padding(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
