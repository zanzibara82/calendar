package com.example.mycalendar.desktop.conflict

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.mycalendar.desktop.theme.LocalDesktopColors
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.usecase.DeletionChoice
import java.time.ZoneId

/**
 * Очередь конфликтов синхронизации и разбор выбранного: спорные поля — по чипам
 * «Локальное» / «Из облака», удаления — двумя исходами с подтверждением.
 *
 * Экран ничего не записывает: очередь и контроллер живут в окне, сюда приходят состояние
 * и обработчики. Локальное значение остаётся в силе, пока пользователь не выберет исход.
 */
@Composable
fun ConflictsScreen(
    conflicts: List<SyncConflict>,
    state: ConflictsController.State,
    zone: ZoneId,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onChoose: (SyncField, Boolean) -> Unit,
    onApply: () -> Unit,
    onResolveDeletion: (DeletionChoice) -> Unit,
    onDismissNotice: () -> Unit,
) {
    val colors = LocalDesktopColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("conflicts-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("conflicts-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Конфликты синхронизации", style = MaterialTheme.typography.headlineSmall)
        }

        state.notice?.let { notice ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = noticeText(notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f).testTag("conflict-notice"),
                )
                TextButton(
                    onClick = onDismissNotice,
                    modifier = Modifier.testTag("conflict-notice-dismiss"),
                ) {
                    Text("Скрыть")
                }
            }
        }

        if (conflicts.isEmpty()) {
            Text(
                text = "Открытых конфликтов нет",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.testTag("conflicts-empty"),
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                conflicts.forEach { conflict ->
                    ConflictCard(
                        conflict = conflict,
                        zone = zone,
                        selected = conflict.id == state.selected?.id,
                        onClick = { onOpen(conflict.id) },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1.2f)
                    .verticalScroll(rememberScrollState()),
            ) {
                val conflict = state.selected
                val local = state.local
                if (conflict == null || local == null) {
                    Text(
                        text = "Выберите конфликт слева",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.testTag("conflict-detail-empty"),
                    )
                } else {
                    Detail(
                        conflict = conflict,
                        local = local,
                        state = state,
                        zone = zone,
                        onChoose = onChoose,
                        onApply = onApply,
                        onResolveDeletion = onResolveDeletion,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConflictCard(
    conflict: SyncConflict,
    zone: ZoneId,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalDesktopColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("conflict-${conflict.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) colors.surface else colors.surface.copy(alpha = 0.6f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = displayTitle(conflict.remote.title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = detectedAtText(conflict.detectedAt, zone),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Text(text = conflictSummary(conflict), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Detail(
    conflict: SyncConflict,
    local: Event,
    state: ConflictsController.State,
    zone: ZoneId,
    onChoose: (SyncField, Boolean) -> Unit,
    onApply: () -> Unit,
    onResolveDeletion: (DeletionChoice) -> Unit,
) {
    val colors = LocalDesktopColors.current
    var confirmDelete by remember(conflict.id) { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить событие?") },
            text = { Text(deletionConfirmText(conflict, local)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onResolveDeletion(DeletionChoice.DELETE_EVERYWHERE)
                }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Отмена")
                }
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Разбор конфликта", style = MaterialTheme.typography.titleMedium)
            Text(
                text = conflictHelp(conflict.kind),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            HorizontalDivider()

            if (conflict.kind.isDeletion) {
                DeletionComparison(conflict = conflict, local = local, zone = zone)
                Button(
                    onClick = { confirmDelete = true },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth().testTag("conflict-delete-everywhere"),
                ) {
                    Text("Удалить везде")
                }
                OutlinedButton(
                    onClick = { onResolveDeletion(DeletionChoice.RESTORE_FROM_CLOUD) },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth().testTag("conflict-restore"),
                ) {
                    Text(
                        if (conflict.kind == ConflictKind.REMOTE_DELETED) {
                            "Создать заново в облаке"
                        } else {
                            "Вернуть из облака"
                        }
                    )
                }
            } else {
                SyncField.entries.filter { it in conflict.contested }.forEach { field ->
                    FieldRow(
                        field = field,
                        conflict = conflict,
                        local = local,
                        zone = zone,
                        chosenFromCloud = field in state.remoteChosen,
                        saving = state.saving,
                        onChoose = { fromCloud -> onChoose(field, fromCloud) },
                    )
                }
                Button(
                    onClick = onApply,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth().testTag("conflict-apply"),
                ) {
                    Text("Применить")
                }
            }
        }
    }
}

/** Одна строка сравнения: чипы выбора и оба значения. */
@Composable
private fun FieldRow(
    field: SyncField,
    conflict: SyncConflict,
    local: Event,
    zone: ZoneId,
    chosenFromCloud: Boolean,
    saving: Boolean,
    onChoose: (Boolean) -> Unit,
) {
    val colors = LocalDesktopColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(fieldLabel(field), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !chosenFromCloud,
                onClick = { onChoose(false) },
                enabled = !saving,
                label = { Text("Локальное") },
                modifier = Modifier.testTag("conflict-field-${field.name.lowercase()}-local"),
            )
            FilterChip(
                selected = chosenFromCloud,
                onClick = { onChoose(true) },
                enabled = !saving,
                label = { Text("Из облака") },
                modifier = Modifier.testTag("conflict-field-${field.name.lowercase()}-cloud"),
            )
        }
        Text(
            text = "Локальное: ${localValue(field, local, zone)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Из облака: ${cloudValue(field, conflict, zone)}",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
    }
}

/**
 * Сравнение двух версий для конфликта-удаления: спорных полей там нет, выбор один из двух.
 * Показывается всё, что пользователь потеряет, выбрав другой исход.
 */
@Composable
private fun DeletionComparison(conflict: SyncConflict, local: Event, zone: ZoneId) {
    val colors = LocalDesktopColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ваша версия", style = MaterialTheme.typography.labelLarge)
        VersionDetails(
            title = local.title,
            description = local.description,
            range = "${dateTime(local.startTime, zone)} — ${dateTime(local.endTime, zone)}",
            reminders = local.reminderMinutes,
            repeat = repeatLabel(local.repeatRule),
        )
        if (local.tags.isNotEmpty()) {
            Text(
                text = "Метки хранятся только на этом устройстве и исчезнут вместе с событием",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!local.textNote.isNullOrBlank()) {
            Text(
                text = "Текстовая заметка удаляется вместе с событием",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!local.voiceNotePath.isNullOrBlank()) {
            Text(
                text = "Голосовая запись удаляется вместе с событием",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = if (conflict.kind == ConflictKind.REMOTE_DELETED) {
                "Последняя версия из облака"
            } else {
                "Версия в облаке"
            },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        VersionDetails(
            title = conflict.remote.title,
            description = conflict.remote.description,
            range = "${dateTime(conflict.remote.startTime, zone)} — " +
                dateTime(conflict.remote.endTime, zone),
            reminders = conflict.remote.reminderMinutes,
            repeat = repeatLabel(conflict.remote.repeatRule),
        )
        Text(
            text = "Текущее состояние из облака",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

/** Версия целиком: всё, что пользователь теряет или сохраняет, выбрав исход. */
@Composable
private fun VersionDetails(
    title: String,
    description: String?,
    range: String,
    reminders: List<Int>,
    repeat: String,
) {
    val colors = LocalDesktopColors.current
    Text(displayTitle(title), style = MaterialTheme.typography.titleMedium)
    Text(range, style = MaterialTheme.typography.bodyMedium)
    Text(
        text = displayDescription(description),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
    )
    Text(
        text = "Напоминания: ${remindersLabel(reminders)}",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )
    Text(
        text = "Повтор: $repeat",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )
}

private fun deletionConfirmText(conflict: SyncConflict, local: Event): String {
    val title = displayTitle(local.title)
    return if (conflict.kind == ConflictKind.REMOTE_DELETED) {
        "«$title» будет удалено на этом устройстве. Записи в облаке уже нет — " +
            "вернуть событие будет нельзя."
    } else {
        "«$title» будет удалено в облаке вместе с описанием, напоминаниями и повтором. " +
            "Вернуть их будет нельзя."
    }
}
