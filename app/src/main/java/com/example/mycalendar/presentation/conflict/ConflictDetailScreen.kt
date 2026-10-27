package com.example.mycalendar.presentation.conflict

import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mycalendar.R
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.EventTag
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
    val context = LocalContext.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    // Решение не применилось (очередь успела измениться): экран уже закрывается, сообщение
    // переживёт переход и объяснит, почему выбор не сработал.
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        val text = when (notice) {
            ConflictNotice.STALE -> R.string.conflict_stale
            ConflictNotice.NO_LOCAL_ROW -> R.string.conflict_no_longer_relevant
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
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

        // Необратимый исход: удаление в облаке и безвозвратная потеря заметок и голосовой записи.
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.delete_event_title)) },
                text = {
                    Text(
                        stringResource(
                            if (conflict.kind == ConflictKind.REMOTE_DELETED) {
                                // Записи в облаке уже нет: удаление необратимо на этом устройстве.
                                R.string.conflict_delete_everywhere_text
                            } else {
                                // Удаление уносит облачную копию вместе с её полями.
                                R.string.conflict_delete_cloud_text
                            },
                            displayTitle(local.title),
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        viewModel.chooseDeletion(DeletionChoice.DELETE_EVERYWHERE)
                    }) {
                        Text(
                            text = stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
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
                    onClick = { confirmDelete = true },
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.conflict_delete_everywhere)) }
                OutlinedButton(
                    onClick = { viewModel.chooseDeletion(DeletionChoice.RESTORE_FROM_CLOUD) },
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (conflict.kind == ConflictKind.REMOTE_DELETED) {
                                // В облаке записи нет: исход создаёт событие заново.
                                R.string.conflict_recreate_in_cloud
                            } else {
                                R.string.conflict_restore_from_cloud
                            }
                        )
                    )
                }
            } else {
                SyncField.entries.filter { it in conflict.contested }.forEach { field ->
                    FieldRow(
                        field = field,
                        local = local,
                        conflict = conflict,
                        chosenFromCloud = field in state.remoteChosen,
                        enabled = !state.isSaving,
                        onChoose = { fromCloud -> viewModel.onChoose(field, fromCloud) },
                    )
                }
                Button(
                    onClick = viewModel::apply,
                    enabled = !state.isSaving,
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

/**
 * Сравнение двух версий для конфликта-удаления: спорных полей там нет, выбор один из двух.
 * Показывается всё, что пользователь потеряет, выбрав другой исход: метки, текстовую и
 * голосовую заметки.
 */
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
            VersionDetails(
                title = local.title,
                description = local.description,
                startTime = local.startTime,
                endTime = local.endTime,
                reminderMinutes = local.reminderMinutes,
                repeatRule = local.repeatRule,
            )
            if (local.tags.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.conflict_tags_deleted, tagsLabel(local.tags)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!local.textNote.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.conflict_text_note_deleted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!local.voiceNotePath.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.conflict_voice_note_deleted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = stringResource(
                    if (conflict.kind == ConflictKind.REMOTE_DELETED) {
                        // В облаке записи уже нет: показываем последний сохранённый снимок.
                        R.string.conflict_cloud_version_last
                    } else {
                        R.string.conflict_cloud_version
                    }
                ),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            VersionDetails(
                title = conflict.remote.title,
                description = conflict.remote.description,
                startTime = conflict.remote.startTime,
                endTime = conflict.remote.endTime,
                reminderMinutes = conflict.remote.reminderMinutes,
                repeatRule = conflict.remote.repeatRule,
            )
        }
    }
}

/** Версия целиком: всё, что пользователь теряет или сохраняет, выбрав исход. */
@Composable
private fun VersionDetails(
    title: String,
    description: String?,
    startTime: Long,
    endTime: Long,
    reminderMinutes: List<Int>,
    repeatRule: RepeatRule?,
) {
    Text(displayTitle(title), style = MaterialTheme.typography.titleMedium)
    Text(formatRange(startTime, endTime), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.field_description), style = MaterialTheme.typography.labelMedium)
    Text(displayDescription(description), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.field_reminders), style = MaterialTheme.typography.labelMedium)
    Text(remindersLabel(reminderMinutes), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.field_repeat_rule), style = MaterialTheme.typography.labelMedium)
    Text(repeatLabel(repeatRule), style = MaterialTheme.typography.bodyMedium)
}

/** Одна строка сравнения: чипы выбора и оба значения. */
@Composable
private fun FieldRow(
    field: SyncField,
    local: Event,
    conflict: SyncConflict,
    chosenFromCloud: Boolean,
    enabled: Boolean,
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
                    enabled = enabled,
                    label = { Text(stringResource(R.string.conflict_use_local)) },
                )
                FilterChip(
                    selected = chosenFromCloud,
                    onClick = { onChoose(true) },
                    enabled = enabled,
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
    SyncField.TITLE -> displayTitle(local.title)
    SyncField.DESCRIPTION -> displayDescription(local.description)
    SyncField.START_TIME -> formatDateTime(local.startTime)
    SyncField.END_TIME -> formatDateTime(local.endTime)
    SyncField.REMINDER_MINUTES -> remindersLabel(local.reminderMinutes)
    SyncField.REPEAT_RULE -> repeatLabel(local.repeatRule)
}

@Composable
private fun cloudValue(field: SyncField, conflict: SyncConflict): String = when (field) {
    SyncField.TITLE -> displayTitle(conflict.remote.title)
    SyncField.DESCRIPTION -> displayDescription(conflict.remote.description)
    SyncField.START_TIME -> formatDateTime(conflict.remote.startTime)
    SyncField.END_TIME -> formatDateTime(conflict.remote.endTime)
    SyncField.REMINDER_MINUTES -> remindersLabel(conflict.remote.reminderMinutes)
    SyncField.REPEAT_RULE -> repeatLabel(conflict.remote.repeatRule)
}

@Composable
private fun displayTitle(title: String): String =
    title.ifBlank { stringResource(R.string.conflict_untitled) }

@Composable
private fun displayDescription(description: String?): String =
    description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.conflict_no_description)

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
private fun tagsLabel(tags: List<EventTag>): String =
    tags.map { stringResource(tagLabel(it)) }.joinToString(", ")

@Composable
private fun tagLabel(tag: EventTag): Int = when (tag) {
    EventTag.BIRTHDAY -> R.string.tag_birthday
    EventTag.DATE -> R.string.tag_date
    EventTag.CINEMA -> R.string.tag_cinema
}

@Composable
private fun remindersLabel(minutes: List<Int>): String {
    if (minutes.isEmpty()) return stringResource(R.string.conflict_no_reminders)
    // `map` — inline, поэтому `stringResource` в его лямбде допустим; `joinToString` не inline,
    // поэтому форматировать в нём нельзя: сначала собираем подписи, потом склеиваем.
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
