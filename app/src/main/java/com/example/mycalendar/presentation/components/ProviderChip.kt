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
import com.example.mycalendar.R
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.presentation.theme.AppShapes
import com.example.mycalendar.presentation.theme.AppSizes
import com.example.mycalendar.presentation.theme.AppSpacing
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
            .padding(horizontal = AppSpacing.chipHPad, vertical = AppSpacing.chipVPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.dotGap),
    ) {
        Spacer(
            modifier = Modifier
                .size(AppSizes.dot)
                .background(providerColor(provider), AppShapes.dayMarker),
        )
        Text(
            text = providerShortName(provider),
            style = CardTypography.chip,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
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
        maxLines = 1,
        modifier = modifier
            .clip(AppShapes.chip)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(AppSizes.stroke, MaterialTheme.colorScheme.outline, AppShapes.chip)
            .padding(horizontal = AppSpacing.chipHPad, vertical = AppSpacing.chipVPad),
    )
}
