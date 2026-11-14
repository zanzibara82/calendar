package com.example.mycalendar.desktop.reminder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Плашка напоминания поверх окна: название события, время начала и три кнопки.
 * Показывается и когда окно было свёрнуто в трей — тогда окно выводится на передний план.
 */
@Composable
fun ReminderBanner(
    title: String,
    label: String,
    onOpen: () -> Unit,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .widthIn(max = 480.dp)
            .testTag("reminder-banner"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(
                onClick = onOpen,
                modifier = Modifier.testTag("reminder-open"),
            ) {
                Text("Открыть")
            }
            TextButton(
                onClick = onSnooze,
                modifier = Modifier.testTag("reminder-snooze"),
            ) {
                Text("Отложить")
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("reminder-dismiss"),
            ) {
                Text("Закрыть")
            }
        }
    }
}
