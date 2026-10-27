package com.example.mycalendar.presentation.event

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.mycalendar.R
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.presentation.common.fullDateTimeFormatter
import com.example.mycalendar.presentation.common.russianLocale
import com.example.mycalendar.presentation.components.providerColor
import java.time.Instant
import java.time.ZoneId

/**
 * Экран деталей события: полная информация, текстовая заметка,
 * плеер голосовой заметки, действия Изменить/Удалить.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailsScreen(
    eventId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: EventDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    val event = state.event

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.event_details)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onEdit, enabled = event != null) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
                    }
                    IconButton(onClick = { showDeleteDialog = true }, enabled = event != null) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (event == null) {
            // Событие удалено или не существует
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.no_events))
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Заголовок + маркер провайдера
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(providerColor(event.calendarProvider), CircleShape),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
            }

            // Время
            DetailsBlock(title = stringResource(R.string.event_start)) {
                Text(formatDateTime(event.startTime), style = MaterialTheme.typography.bodyLarge)
            }
            DetailsBlock(title = stringResource(R.string.event_end)) {
                Text(formatDateTime(event.endTime), style = MaterialTheme.typography.bodyLarge)
            }

            // Повтор
            if (event.repeatRule != null) {
                DetailsBlock(title = stringResource(R.string.event_repeat)) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(repeatLabel(event.repeatRule))) },
                    )
                }
            }

            // Напоминания
            if (event.reminderMinutes.isNotEmpty()) {
                DetailsBlock(title = stringResource(R.string.event_reminders)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        event.reminderMinutes.sorted().forEach { minutes ->
                            AssistChip(
                                onClick = {},
                                label = { Text(humanizeReminder(minutes)) },
                            )
                        }
                    }
                }
            }

            // Описание
            event.description?.takeIf { it.isNotBlank() }?.let { description ->
                DetailsBlock(title = stringResource(R.string.event_description_hint)) {
                    Text(description, style = MaterialTheme.typography.bodyLarge)
                }
            }

            // Метки (День рождения / Свидание / Кино)
            if (event.tags.isNotEmpty()) {
                DetailsBlock(title = stringResource(R.string.event_tags)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        event.tags.forEach { tag ->
                            AssistChip(
                                onClick = {},
                                label = { Text(stringResource(tagLabel(tag))) },
                            )
                        }
                    }
                }
            }

            // Голосовая заметка: плеер
            event.voiceNotePath?.let { path ->
                DetailsBlock(title = stringResource(R.string.voice_note)) {
                    VoicePlayerRow(
                        isPlaying = state.isPlaying,
                        positionMs = state.playPositionMs,
                        durationMs = state.playDurationMs,
                        onToggle = viewModel::togglePlayback,
                        onSeek = viewModel::seekTo,
                        onDelete = viewModel::deleteVoiceNote,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Диалог подтверждения удаления
    if (showDeleteDialog && event != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_event_title)) },
            text = { Text(stringResource(R.string.delete_event_text, event.title)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteEvent(onDeleted)
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun VoicePlayerRow(
    isPlaying: Boolean,
    positionMs: Int,
    durationMs: Int,
    onToggle: () -> Unit,
    onSeek: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            val duration = durationMs.coerceAtLeast(1)
            Slider(
                value = positionMs.toFloat().coerceIn(0f, duration.toFloat()) / duration.toFloat(),
                onValueChange = { fraction -> onSeek((fraction * duration).toInt()) },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatMillis(positionMs),
                style = MaterialTheme.typography.labelMedium,
            )
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete_voice_note),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DetailsBlock(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        content()
    }
}

// ---------- Форматирование ----------

private fun formatDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(fullDateTimeFormatter)

private fun formatMillis(millis: Int): String =
    "%d:%02d".format(russianLocale, millis / 60000, (millis / 1000) % 60)

private fun humanizeReminder(minutes: Int): String = when {
    minutes >= 1440 -> "%d д".format(russianLocale, minutes / 1440)
    minutes >= 60 -> "%d ч".format(russianLocale, minutes / 60)
    else -> "%d мин".format(russianLocale, minutes)
}

@Composable
private fun repeatLabel(rule: RepeatRule): Int = when (rule) {
    RepeatRule.DAILY -> R.string.repeat_daily
    RepeatRule.WEEKLY -> R.string.repeat_weekly
    RepeatRule.MONTHLY -> R.string.repeat_monthly
    RepeatRule.YEARLY -> R.string.repeat_yearly
}

@Composable
private fun tagLabel(tag: EventTag): Int = when (tag) {
    EventTag.BIRTHDAY -> R.string.tag_birthday
    EventTag.DATE -> R.string.tag_date
    EventTag.CINEMA -> R.string.tag_cinema
}
