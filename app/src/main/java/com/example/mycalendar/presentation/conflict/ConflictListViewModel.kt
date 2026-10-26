package com.example.mycalendar.presentation.conflict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.repository.ConflictRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Очередь открытых конфликтов как поток состояния экрана. */
@HiltViewModel
class ConflictListViewModel @Inject constructor(
    conflictRepository: ConflictRepository,
) : ViewModel() {

    val uiState: StateFlow<List<SyncConflict>> = conflictRepository.observeConflicts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
