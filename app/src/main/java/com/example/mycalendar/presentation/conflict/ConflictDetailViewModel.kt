package com.example.mycalendar.presentation.conflict

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.diffFields
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

/** Чем закончился разбор, если решение не применилось: экран закрывается с сообщением. */
enum class ConflictNotice {
    /** Очередь уже описывает другое состояние, чем показанное на экране. */
    STALE,

    /** Локальной записи больше нет: разбирать нечего. */
    NO_LOCAL_ROW,
}

/** Разбор одного конфликта: по каждому спорному полю — локальное или облачное значение. */
data class ConflictDetailState(
    val conflict: SyncConflict? = null,
    /** Текущая локальная запись — «локальная карточка» в сравнении. */
    val local: Event? = null,
    /** Поля, где выбрано облачное значение; по умолчанию выбрано локальное. */
    val remoteChosen: Set<SyncField> = emptySet(),
    /** Идёт запись: повторные нажатия и второй исход игнорируются. */
    val isSaving: Boolean = false,
    /** Конфликт разобран или исчез: экран закрывается. */
    val isResolved: Boolean = false,
    /** Сообщение к закрытию, если решение не применено. */
    val notice: ConflictNotice? = null,
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
     * Перечитать конфликт и строку: очередь переписывает конфликт на месте, а локальную запись
     * могли удалить, пока экран был закрыт.
     */
    private suspend fun reload() {
        val conflict = conflictRepository.get(conflictId)
        if (conflict == null) {
            _uiState.update { it.copy(isResolved = true) }
            return
        }
        val local = eventRepository.getEvent(conflict.eventId)
        if (local == null) {
            // Строка исчезла между чтениями: очередь снимет её каскадом или следующий проход.
            _uiState.update { it.copy(isResolved = true, notice = ConflictNotice.NO_LOCAL_ROW) }
            return
        }
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
            if (state.isSaving) return@update state
            // Неспорное поле облаком не перезаписывается (§7) — выбор по нему не принимаем.
            if (field !in (state.conflict?.contested ?: emptySet())) return@update state
            state.copy(
                remoteChosen = if (fromCloud) state.remoteChosen + field
                else state.remoteChosen - field,
            )
        }
    }

    /** Запись по полям: локальное значение остаётся, если для поля не выбрано облачное. */
    fun apply() {
        val state = _uiState.value
        val shown = state.conflict ?: return
        val chosen = state.remoteChosen
        resolve(shown) { conflict -> resolveConflict(conflict, chosen) }
    }

    /** Исход конфликта-удаления. */
    fun chooseDeletion(choice: DeletionChoice) {
        val state = _uiState.value
        val shown = state.conflict ?: return
        resolve(shown) { conflict -> resolveConflict.resolveDeletion(conflict, choice) }
    }

    /**
     * Запись с проверкой свежести. Очередь переиспользует строку и переписывает её на месте, поэтому
     * показанные значения могли устареть, а `invoke` накладывает уже текущие облачные значения.
     * Применять выбор к тому, чего пользователь не видел, нельзя (§7): расхождение и отказ разбора
     * закрывают экран с сообщением, актуальный конфликт покажет очередь.
     */
    private fun resolve(shown: SyncConflict, action: suspend (SyncConflict) -> Unit) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val current = conflictRepository.get(shown.id)
            val notice = if (current == null || !current.showsSameState(shown)) {
                ConflictNotice.STALE
            } else {
                try {
                    action(current)
                    null
                } catch (stale: IllegalArgumentException) {
                    ConflictNotice.STALE
                }
            }
            _uiState.update { it.copy(isResolved = true, notice = notice) }
        }
    }
}

/**
 * Совпадает ли всё, что видит пользователь: вид, набор спорных полей и облачная версия.
 * Сравнение облачных значений нормализованное, как у решателя: `null` и пустая строка описания,
 * а также порядок минут одним значением не считаются.
 */
private fun SyncConflict.showsSameState(other: SyncConflict): Boolean =
    kind == other.kind &&
        contested == other.contested &&
        diffFields(remote, other.remote).isEmpty()
