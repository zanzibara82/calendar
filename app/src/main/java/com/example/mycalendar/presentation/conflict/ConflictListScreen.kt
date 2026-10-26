package com.example.mycalendar.presentation.conflict

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mycalendar.R
import com.example.mycalendar.domain.model.ConflictKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Очередь конфликтов синхронизации: что столкнулось и сколько полей надо разобрать.
 * Локальная версия остаётся в силе, пока пользователь не выберет исход.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictListScreen(
    onBack: () -> Unit,
    onOpenConflict: (String) -> Unit,
    viewModel: ConflictListViewModel = hiltViewModel(),
) {
    val conflicts by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflicts_title)) },
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
        if (conflicts.isEmpty()) {
            Text(
                text = stringResource(R.string.conflicts_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(count = conflicts.size, key = { conflicts[it].id }) { index ->
                val conflict = conflicts[index]
                val title = conflict.remote.title.ifBlank { stringResource(R.string.conflict_untitled) }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenConflict(conflict.id) },
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = formatDetectedAt(conflict.detectedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = when (conflict.kind) {
                                ConflictKind.CONTENT -> pluralStringResource(
                                    R.plurals.conflict_contested_fields,
                                    conflict.contested.size,
                                    conflict.contested.size,
                                )
                                ConflictKind.REMOTE_DELETED ->
                                    stringResource(R.string.conflict_remote_deleted)
                                ConflictKind.LOCAL_DELETED ->
                                    stringResource(R.string.conflict_local_deleted)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/** Отметка обнаружения в русской форме, как на экране настроек: локаль устройства не участвует. */
private val detectedAtFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru"))

private fun formatDetectedAt(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(detectedAtFormatter)
