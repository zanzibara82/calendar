package com.example.mycalendar.desktop.settings

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.mycalendar.desktop.theme.LocalDesktopColors
import com.example.mycalendar.desktop.theme.ThemeMode
import java.net.URI

/**
 * Настройки в духе Android-приложения: оформление, напоминания и сведения о программе.
 * Календари и обновления появятся вместе с синхронизацией desktop-клиента.
 */
@Composable
fun SettingsScreen(
    settings: DesktopSettings,
    dataDir: String,
    version: String,
    onBack: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onQuietChange: (Boolean) -> Unit,
    onPickAlarm: () -> Unit,
    onResetAlarm: () -> Unit,
    onPickBirthday: () -> Unit,
    onResetBirthday: () -> Unit,
) {
    val colors = LocalDesktopColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("settings-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("settings-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Настройки", style = MaterialTheme.typography.headlineSmall)
        }

        SectionHeader("Оформление")
        Panel {
            Text("Тема", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = listOf(
                    ThemeMode.SYSTEM to "Системная",
                    ThemeMode.LIGHT to "Светлая",
                    ThemeMode.DARK to "Тёмная",
                )
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = settings.themeMode == mode,
                        onClick = { onThemeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        modifier = Modifier.testTag("settings-theme-${mode.name.lowercase()}"),
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }

        SectionHeader("Напоминания")
        Panel {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Тихий режим", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Напоминания приходят без звука — только на экране.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.quietMode,
                    onCheckedChange = onQuietChange,
                    modifier = Modifier.testTag("settings-quiet"),
                )
            }
            HorizontalDivider()
            SoundRow(
                title = "Сигнал напоминания",
                uri = settings.alarmSoundUri,
                tag = "alarm",
                onPick = onPickAlarm,
                onReset = onResetAlarm,
            )
            HorizontalDivider()
            SoundRow(
                title = "Сигнал дня рождения",
                uri = settings.birthdaySoundUri,
                tag = "birthday",
                onPick = onPickBirthday,
                onReset = onResetBirthday,
            )
        }

        SectionHeader("О приложении")
        Panel {
            Text("MyCalendar $version", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Данные: $dataDir",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.testTag("settings-data-dir"),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = LocalDesktopColors.current.onSurfaceVariant,
    )
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    val colors = LocalDesktopColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SoundRow(
    title: String,
    uri: String?,
    tag: String,
    onPick: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = soundLabel(uri),
                style = MaterialTheme.typography.bodySmall,
                color = LocalDesktopColors.current.onSurfaceVariant,
                modifier = Modifier.testTag("settings-sound-$tag"),
            )
        }
        TextButton(onClick = onPick, modifier = Modifier.testTag("settings-pick-$tag")) {
            Text("Выбрать…")
        }
        TextButton(
            onClick = onReset,
            enabled = uri != null,
            modifier = Modifier.testTag("settings-reset-$tag"),
        ) {
            Text("Сбросить")
        }
    }
}

/** «Системный» для значения по умолчанию, иначе имя выбранного файла. */
internal fun soundLabel(uri: String?): String {
    if (uri == null) return "Системный"
    val name = runCatching { URI(uri).path }.getOrNull()?.substringAfterLast('/')
    return if (name.isNullOrBlank()) uri else name
}
