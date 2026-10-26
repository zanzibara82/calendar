package com.example.mycalendar.presentation.conflict

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mycalendar.R
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.usecase.DeletionChoice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Разбор конфликта: по полям — локальное или облачное значение каждого спорного поля;
 * у конфликта-удаления — два исхода, «удалить везде» или «вернуть из облака».
 *
 * Применённое состояние становится локальной записью и новым предком; в облако оно уходит
 * при следующей синхронизации. Локальное значение по умолчанию — молча ничего не меняем.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictDetailScreen(
    onBack: () -> Unit,
    viewModel: ConflictDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.isResolved) {
        if (state.isResolved) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflict_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val conflict = state.conflict
        val local = state.local
        if (conflict == null || local == null) {
            Text(
                text = stringResource(R.string.conflict_no_longer_relevant),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            )
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
            Text(
                text = kindHelp(conflict.kind),
                style = MaterialTheme.typography.bodyMedium,
            )

            // R71: ветвление по виду конфликта. Пустое `contested` у удаления не признак.
            if (conflict.kind.isDeletion) {
                DeletionComparison(conflict = conflict, local = local)
                Button(
                    onClick = { viewModel.chooseDeletion(DeletionChoice.DELETE_EVERYWHERE) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.conflict_delete_everywhere)) }
                OutlinedButton(
                    onClick = { viewModel.chooseDeletion(DeletionChoice.RESTORE_FROM_CLOUD) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.conflict_restore_from_cloud)) }
            } else {
                SyncField.entries.filter { it in conflict.contested }.forEach { field ->
                    FieldRow(
                        field = field,
                        local = local,
                        conflict = conflict,
                        chosenFromCloud = field in state.remoteChosen,
                        onChoose = { fromCloud -> viewModel.onChoose(field, fromCloud) },
                    )
                }
                Button(
                    onClick = viewModel::apply,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.conflict_apply)) }
            }
        }
    }
}

@Composable
private fun kindHelp(kind: ConflictKind): String = stringResource(
    when (kind) {
        ConflictKind.CONTENT -> R.string.conflict_content_help
        ConflictKind.REMOTE_DELETED -> R.string.conflict_remote_deleted
        ConflictKind.LOCAL_DELETED -> R.string.conflict_local_deleted
    }
)

/** Сравнение двух версий для конфликта-удаления: спорных полей там нет, выбор один из двух. */
@Composable
private fun DeletionComparison(conflict: SyncConflict, local: Event) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.conflict_local_version),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(local.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = formatRange(local.startTime, local.endTime),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.conflict_cloud_version),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(conflict.remote.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = formatRange(conflict.remote.startTime, conflict.remote.endTime),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Одна строка сравнения: чипы выбора и оба значения. */
@Composable
private fun FieldRow(
    field: SyncField,
    local: Event,
    conflict: SyncConflict,
    chosenFromCloud: Boolean,
    onChoose: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(fieldLabel(field), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !chosenFromCloud,
                    onClick = { onChoose(false) },
                    label = { Text(stringResource(R.string.conflict_use_local)) },
                )
                FilterChip(
                    selected = chosenFromCloud,
                    onClick = { onChoose(true) },
                    label = { Text(stringResource(R.string.conflict_use_cloud)) },
                )
            }
            Text(
                text = stringResource(R.string.conflict_value_local, localValue(field, local)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.conflict_value_cloud, cloudValue(field, conflict)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun fieldLabel(field: SyncField): String = stringResource(
    when (field) {
        SyncField.TITLE -> R.string.field_title
        SyncField.DESCRIPTION -> R.string.field_description
        SyncField.START_TIME -> R.string.field_start_time
        SyncField.END_TIME -> R.string.field_end_time
        SyncField.REMINDER_MINUTES -> R.string.field_reminders
        SyncField.REPEAT_RULE -> R.string.field_repeat_rule
    }
)

@Composable
private fun localValue(field: SyncField, local: Event): String = when (field) {
    SyncField.TITLE -> local.title
    SyncField.DESCRIPTION -> local.description.orEmpty()
    SyncField.START_TIME -> formatDateTime(local.startTime)
    SyncField.END_TIME -> formatDateTime(local.endTime)
    SyncField.REMINDER_MINUTES -> remindersLabel(local.reminderMinutes)
    SyncField.REPEAT_RULE -> repeatLabel(local.repeatRule)
}

@Composable
private fun cloudValue(field: SyncField, conflict: SyncConflict): String = when (field) {
    SyncField.TITLE -> conflict.remote.title
    SyncField.DESCRIPTION -> conflict.remote.description.orEmpty()
    SyncField.START_TIME -> formatDateTime(conflict.remote.startTime)
    SyncField.END_TIME -> formatDateTime(conflict.remote.endTime)
    SyncField.REMINDER_MINUTES -> remindersLabel(conflict.remote.reminderMinutes)
    SyncField.REPEAT_RULE -> repeatLabel(conflict.remote.repeatRule)
}

@Composable
private fun repeatLabel(rule: RepeatRule?): String = stringResource(
    when (rule) {
        null -> R.string.repeat_none
        RepeatRule.DAILY -> R.string.repeat_daily
        RepeatRule.WEEKLY -> R.string.repeat_weekly
        RepeatRule.MONTHLY -> R.string.repeat_monthly
        RepeatRule.YEARLY -> R.string.repeat_yearly
    }
)

@Composable
private fun remindersLabel(minutes: List<Int>): String {
    if (minutes.isEmpty()) return stringResource(R.string.conflict_no_reminders)
    // `map` — inline, поэтому `stringResource` в его лямбде допустим; `joinToString` форматирование
    // не принимает вовсе, поэтому сначала собираем подписи, потом склеиваем.
    return minutes.sorted()
        .map { stringResource(R.string.minutes_short, it) }
        .joinToString(", ")
}

// ---------- Форматирование (своё: в EventDetailsScreen они приватные) ----------

private val ruLocale = Locale.forLanguageTag("ru")
private val dateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", ruLocale)

private fun formatDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateTimeFormatter)

private fun formatRange(start: Long, end: Long): String =
    "${formatDateTime(start)} — ${formatDateTime(end)}"
