package com.example.mycalendar.presentation.conflict

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.repository.ConflictRepository
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.usecase.DeletionChoice
import com.example.mycalendar.domain.usecase.ResolveConflictUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Разбор одного конфликта: по каждому спорному полю — локальное или облачное значение. */
data class ConflictDetailState(
    val conflict: SyncConflict? = null,
    /** Текущая локальная запись — «локальная карточка» в сравнении. */
    val local: Event? = null,
    /** Поля, где выбрано облачное значение; по умолчанию выбрано локальное. */
    val remoteChosen: Set<SyncField> = emptySet(),
    /** Конфликт разобран или исчез: экран закрывается. */
    val isResolved: Boolean = false,
)

@HiltViewModel
class ConflictDetailViewModel @Inject constructor(
    private val conflictRepository: ConflictRepository,
    private val eventRepository: EventRepository,
    private val resolveConflict: ResolveConflictUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val conflictId: String = savedStateHandle.get<String>("conflictId").orEmpty()

    private val _uiState = MutableStateFlow(ConflictDetailState())
    val uiState: StateFlow<ConflictDetailState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    /**
     * Перечитать конфликт и строку: очередь перезаписывает конфликт на месте, а локальную
     * запись могли удалить, пока экран был закрыт.
     */
    private suspend fun reload() {
        val conflict = conflictRepository.get(conflictId)
        if (conflict == null) {
            _uiState.update { it.copy(isResolved = true) }
            return
        }
        val local = eventRepository.getEvent(conflict.eventId)
        _uiState.update {
            it.copy(
                conflict = conflict,
                local = local,
                // Прежний выбор мог относиться к другому набору спорных полей.
                remoteChosen = it.remoteChosen intersect conflict.contested,
            )
        }
    }

    fun onChoose(field: SyncField, fromCloud: Boolean) {
        _uiState.update { state ->
            // Неспорное поле облаком не перезаписывается (§7) — выбор по нему не принимаем.
            if (field !in (state.conflict?.contested ?: emptySet())) return@update state
            state.copy(
                remoteChosen = if (fromCloud) state.remoteChosen + field
                else state.remoteChosen - field,
            )
        }
    }

    /**
     * Запись по полям. Отказ разбора (вид конфликта или строка успели измениться) закрывает
     * экран: повторять выбор на устаревших данных нельзя, актуальный конфликт покажет очередь.
     */
    fun apply() {
        val state = _uiState.value
        val conflict = state.conflict ?: return
        viewModelScope.launch {
            try {
                resolveConflict(conflict, state.remoteChosen)
            } catch (stale: IllegalArgumentException) {
                // R72: очередь уже описывает другое состояние — экран закрывается ниже.
            }
            _uiState.update { it.copy(isResolved = true) }
        }
    }

    /** Исход конфликта-удаления. */
    fun chooseDeletion(choice: DeletionChoice) {
        val conflict = _uiState.value.conflict ?: return
        viewModelScope.launch {
            try {
                resolveConflict.resolveDeletion(conflict, choice)
            } catch (stale: IllegalArgumentException) {
                // R72: тот же случай, что в apply().
            }
            _uiState.update { it.copy(isResolved = true) }
        }
    }
}
