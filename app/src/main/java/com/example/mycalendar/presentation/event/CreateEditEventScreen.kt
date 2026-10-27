package com.example.mycalendar.presentation.event

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.mycalendar.R
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.usecase.EventValidation
import com.example.mycalendar.presentation.common.russianLocale
import com.example.mycalendar.presentation.common.shortDateFormatter
import com.example.mycalendar.presentation.common.timeFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Доступные отступы напоминаний (минуты -> строковый ресурс). */
private val reminderOptions: List<Pair<Int, Int>> = listOf(
    5 to R.string.reminder_5m,
    10 to R.string.reminder_10m,
    15 to R.string.reminder_15m,
    30 to R.string.reminder_30m,
    60 to R.string.reminder_1h,
    1440 to R.string.reminder_1d,
)

/** Метки события: чекбоксы День рождения / Свидание / Кино. */
private val tagOptions: List<Pair<EventTag, Int>> = listOf(
    EventTag.BIRTHDAY to R.string.tag_birthday,
    EventTag.DATE to R.string.tag_date,
    EventTag.CINEMA to R.string.tag_cinema,
)

/**
 * Экран создания/редактирования события.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateEditEventScreen(
    eventId: String?,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    viewModel: CreateEditEventViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Диалоги выбора даты/времени
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    val zone = ZoneId.systemDefault()

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startVoiceRecording() else onOpenPermissions()
    }

    fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // Навигация после успешного сохранения
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    // Валидационные ошибки
    LaunchedEffect(state.validationError) {
        state.validationError?.let { reason ->
            val message = when (runCatching { EventValidation.Reason.valueOf(reason) }.getOrNull()) {
                EventValidation.Reason.EMPTY_TITLE -> context.getString(R.string.error_empty_title)
                EventValidation.Reason.END_BEFORE_START -> context.getString(R.string.error_end_before_start)
                null -> reason
            }
            snackbarHostState.showSnackbar(message)
            viewModel.dismissValidationError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isExisting) R.string.edit_event else R.string.new_event
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text(stringResource(R.string.event_title_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text(stringResource(R.string.event_description_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            // ---------- Дата и время ----------
            SectionLabel(stringResource(R.string.event_start))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateFieldButton(
                    text = formatDate(state.startDate),
                    onClick = { showStartDatePicker = true },
                    modifier = Modifier.weight(1f),
                )
                TimeFieldButton(
                    text = formatTime(state.startTime),
                    onClick = { showStartTimePicker = true },
                    modifier = Modifier.weight(1f),
                )
            }

            SectionLabel(stringResource(R.string.event_end))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateFieldButton(
                    text = formatDate(state.endDate),
                    onClick = { showEndDatePicker = true },
                    modifier = Modifier.weight(1f),
                )
                TimeFieldButton(
                    text = formatTime(state.endTime),
                    onClick = { showEndTimePicker = true },
                    modifier = Modifier.weight(1f),
                )
            }

            // ---------- Напоминания ----------
            SectionLabel(stringResource(R.string.event_reminders))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                reminderOptions.forEach { (minutes, labelRes) ->
                    FilterChip(
                        selected = minutes in state.reminders,
                        onClick = { viewModel.toggleReminder(minutes) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }

            // ---------- Повтор ----------
            SectionLabel(stringResource(R.string.event_repeat))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val repeatOptions: List<Pair<RepeatRule?, Int>> = listOf(
                    null to R.string.repeat_none,
                    RepeatRule.DAILY to R.string.repeat_daily,
                    RepeatRule.WEEKLY to R.string.repeat_weekly,
                    RepeatRule.MONTHLY to R.string.repeat_monthly,
                    RepeatRule.YEARLY to R.string.repeat_yearly,
                )
                repeatOptions.forEach { (rule, labelRes) ->
                    FilterChip(
                        selected = state.repeatRule == rule,
                        onClick = { viewModel.setRepeatRule(rule) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }

            // ---------- Метки (чекбоксы) ----------
            SectionLabel(stringResource(R.string.event_tags))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                tagOptions.forEach { (tag, labelRes) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = tag in state.tags,
                            onCheckedChange = { viewModel.toggleTag(tag) },
                        )
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { viewModel.toggleTag(tag) },
                        )
                    }
                }
            }

            // ---------- Голосовая заметка ----------
            SectionLabel(stringResource(R.string.voice_note))
            VoiceNoteSection(
                state = state,
                onStartRecording = {
                    if (hasRecordPermission()) viewModel.startVoiceRecording()
                    else recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStopRecording = viewModel::stopVoiceRecording,
                onTogglePlayback = viewModel::toggleVoicePlayback,
                onSeek = viewModel::seekVoiceTo,
                onRemove = viewModel::removeVoiceNote,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving && !state.isRecording,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.save))
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ---------- Диалоги ----------

    if (showStartDatePicker) {
        DatePickerDialogComposable(
            initialMillis = state.startDate.atStartOfDay(zone).toInstant().toEpochMilli(),
            onDismiss = { showStartDatePicker = false },
        ) { millis ->
            viewModel.setStartDate(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())
            showStartDatePicker = false
        }
    }
    if (showEndDatePicker) {
        DatePickerDialogComposable(
            initialMillis = state.endDate.atStartOfDay(zone).toInstant().toEpochMilli(),
            onDismiss = { showEndDatePicker = false },
        ) { millis ->
            viewModel.setEndDate(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())
            showEndDatePicker = false
        }
    }
    if (showStartTimePicker) {
        TimePickerDialogComposable(
            initial = state.startTime,
            onDismiss = { showStartTimePicker = false },
        ) { time ->
            viewModel.setStartTime(time)
            showStartTimePicker = false
        }
    }
    if (showEndTimePicker) {
        TimePickerDialogComposable(
            initial = state.endTime,
            onDismiss = { showEndTimePicker = false },
        ) { time ->
            viewModel.setEndTime(time)
            showEndTimePicker = false
        }
    }
}

// ---------- Секция голосовой заметки ----------

@Composable
private fun VoiceNoteSection(
    state: EditEventUiState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    when {
        // Идёт запись
        state.isRecording -> Row(verticalAlignment = Alignment.CenterVertically) {
            val color by animateColorAsState(
                targetValue = MaterialTheme.colorScheme.error,
                label = "recordDot",
            )
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(color, CircleShape),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.recording_in_progress, formatSeconds(state.recordSeconds)),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onStopRecording) {
                Text(stringResource(R.string.stop_recording))
            }
        }

        // Есть заметка: плеер
        state.voiceNotePath != null -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onTogglePlayback) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.pause else R.string.play
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                val duration = state.playDurationMs.coerceAtLeast(1)
                Slider(
                    value = state.playPositionMs.toFloat() / duration.toFloat(),
                    onValueChange = { fraction -> onSeek((fraction * duration).toInt()) },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatMillis(state.playPositionMs),
                    style = MaterialTheme.typography.labelMedium,
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.delete_voice_note),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Нет заметки: кнопка записи
        else -> OutlinedButton(onClick = onStartRecording, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Mic, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.record_voice))
        }
    }
}

// ---------- Общие элементы ----------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun DateFieldButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Filled.CalendarMonth, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
private fun TimeFieldButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Filled.Schedule, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialogComposable(
    initialMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let(onConfirm)
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialogComposable(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val timeState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(timeState.hour, timeState.minute)) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        text = { TimePicker(state = timeState) },
    )
}

// ---------- Форматирование ----------

private fun formatDate(date: LocalDate): String = date.format(shortDateFormatter)
private fun formatTime(time: LocalTime): String = time.format(timeFormatter)

private fun formatSeconds(seconds: Int): String =
    "%d:%02d".format(russianLocale, seconds / 60, seconds % 60)

private fun formatMillis(millis: Int): String =
    "%d:%02d".format(russianLocale, millis / 60000, (millis / 1000) % 60)
