package com.example.mycalendar.desktop.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.mycalendar.desktop.theme.LocalDesktopColors
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import java.time.LocalDate
import java.time.ZoneId

private val repeatOptions: List<Triple<RepeatRule?, String, String>> = listOf(
    Triple(null, "Нет", "event-repeat-none"),
    Triple(RepeatRule.DAILY, "Ежедневно", "event-repeat-daily"),
    Triple(RepeatRule.WEEKLY, "Еженедельно", "event-repeat-weekly"),
    Triple(RepeatRule.MONTHLY, "Ежемесячно", "event-repeat-monthly"),
    Triple(RepeatRule.YEARLY, "Ежегодно", "event-repeat-yearly"),
)

/**
 * Модальный редактор события поверх календаря: создание и правка.
 * Оверлей, а не отдельное окно — так диалог не отрывается от скрытого календаря
 * и его можно проверить UI-тестом в том же окне.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventEditorDialog(
    base: Event?,
    date: LocalDate,
    newId: String,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSave: (Event) -> Unit,
    onDelete: ((Event) -> Unit)? = null,
) {
    val colors = LocalDesktopColors.current
    var draft by remember(base, date) {
        mutableStateOf(base?.let { draftFromEvent(it, zone) } ?: draftForNew(date))
    }
    var errors by remember(base, date) { mutableStateOf(emptyMap<DraftField, String>()) }
    var confirmDelete by remember(base) { mutableStateOf(false) }

    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(base, date) { titleFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .testTag("event-editor-scrim"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 400.dp, max = 540.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .testTag("event-editor"),
            shape = RoundedCornerShape(18.dp),
            color = colors.surface,
        ) {
            Column(
                modifier = Modifier.padding(22.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (base == null) "Новое событие" else "Изменить событие",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                )

                DraftTextField(
                    value = draft.title,
                    label = "Название",
                    tag = "event-title",
                    error = errors[DraftField.TITLE],
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                    onValueChange = { draft = draft.copy(title = it); errors = errors - DraftField.TITLE },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DraftTextField(
                        value = draft.date,
                        label = "Дата",
                        tag = "event-date",
                        error = errors[DraftField.DATE],
                        modifier = Modifier.weight(1.4f),
                        onValueChange = { draft = draft.copy(date = it); errors = errors - DraftField.DATE },
                    )
                    DraftTextField(
                        value = draft.start,
                        label = "Начало",
                        tag = "event-start",
                        error = errors[DraftField.START],
                        modifier = Modifier.weight(1f),
                        onValueChange = { draft = draft.copy(start = it); errors = errors - DraftField.START },
                    )
                    DraftTextField(
                        value = draft.end,
                        label = "Конец",
                        tag = "event-end",
                        error = errors[DraftField.END],
                        modifier = Modifier.weight(1f),
                        onValueChange = { draft = draft.copy(end = it); errors = errors - DraftField.END },
                    )
                }

                Text(
                    text = "Повтор",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeatOptions.forEach { (rule, label, tag) ->
                        FilterChip(
                            selected = draft.repeatRule == rule,
                            onClick = { draft = draft.copy(repeatRule = rule) },
                            label = { Text(label) },
                            modifier = Modifier.testTag(tag),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (base != null) {
                        TextButton(
                            onClick = { confirmDelete = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.eventYandex),
                            modifier = Modifier.testTag("event-delete"),
                        ) {
                            Text("Удалить")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("event-cancel")) {
                        Text("Отмена")
                    }
                    Button(
                        onClick = {
                            val result = buildEvent(draft, id = newId, base = base, zone = zone)
                            errors = result.errors
                            result.event?.let(onSave)
                        },
                        modifier = Modifier.testTag("event-save"),
                    ) {
                        Text("Сохранить")
                    }
                }
            }
        }

        if (confirmDelete && base != null) {
            ConfirmDeleteDialog(
                title = base.title,
                onCancel = { confirmDelete = false },
                onConfirm = {
                    confirmDelete = false
                    onDelete?.invoke(base)
                },
            )
        }
    }
}

@Composable
private fun DraftTextField(
    value: String,
    label: String,
    tag: String,
    error: String?,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        modifier = modifier.testTag(tag),
    )
}

@Composable
fun ConfirmDeleteDialog(title: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = LocalDesktopColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 400.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Удалить событие?",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
                Text(
                    text = "«$title» исчезнет из календаря.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel, modifier = Modifier.testTag("delete-confirm-no")) {
                        Text("Отмена")
                    }
                    TextButton(
                        onClick = onConfirm,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.eventYandex),
                        modifier = Modifier.testTag("delete-confirm-yes"),
                    ) {
                        Text("Удалить")
                    }
                }
            }
        }
    }
}
