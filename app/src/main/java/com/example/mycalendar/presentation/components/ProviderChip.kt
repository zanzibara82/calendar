package com.example.mycalendar.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.example.mycalendar.presentation.theme.AppSizes
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
