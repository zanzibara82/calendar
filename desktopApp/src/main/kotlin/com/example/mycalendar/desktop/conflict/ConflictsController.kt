package com.example.mycalendar.desktop.conflict

import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.showsSameState
import com.example.mycalendar.domain.repository.ConflictRepository
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.usecase.DeletionChoice
import com.example.mycalendar.domain.usecase.ResolveConflictUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Состояние разбора конфликта в desktop-окне. Повторяет Android-ViewModel: очередь
 * переиспользует строку и переписывает её на месте, поэтому запись начинается с чтения
 * текущего конфликта, а расхождение с показанным закрывает разбор с пояснением.
 *
 * Методы записи объявлены suspend: окно запускает их в своём scope, тесты вызывают напрямую.
 */
class ConflictsController(
    private val conflictRepository: ConflictRepository,
    private val eventRepository: EventRepository,
    private val resolveConflict: ResolveConflictUseCase,
) {

    /** Почему выбор не применился; текст показывает экран. */
    enum class Notice {
        /** Очередь уже описывает другое состояние, чем показанное на экране. */
        STALE,

        /** Локальной записи больше нет: разбирать нечего. */
        NO_LOCAL_ROW,
    }

    data class State(
        val selected: SyncConflict? = null,
        /** Текущая локальная запись — «локальная карточка» в сравнении. */
        val local: Event? = null,
        /** Поля, где выбрано облачное значение; по умолчанию выбрано локальное. */
        val remoteChosen: Set<SyncField> = emptySet(),
        /** Идёт запись: повторные нажатия игнорируются. */
        val saving: Boolean = false,
        val notice: Notice? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Открыть конфликт из очереди: локальная запись читается заново. */
    suspend fun open(id: String) {
        if (_state.value.saving) return
        val conflict = conflictRepository.get(id) ?: return
        val local = eventRepository.getEvent(conflict.eventId)
        if (local == null) {
            _state.update {
                it.copy(
                    selected = null,
                    local = null,
                    remoteChosen = emptySet(),
                    notice = Notice.NO_LOCAL_ROW,
                )
            }
            return
        }
        _state.update {
            it.copy(
                selected = conflict,
                local = local,
                // Прежний выбор мог относиться к другому набору спорных полей.
                remoteChosen = it.remoteChosen intersect conflict.contested,
                notice = null,
            )
        }
    }

    fun choose(field: SyncField, fromCloud: Boolean) {
        _state.update { state ->
            val conflict = state.selected ?: return@update state
            // Неспорное поле облаком не перезаписывается (§7) — выбор по нему не принимаем.
            if (state.saving || field !in conflict.contested) return@update state
            state.copy(
                remoteChosen = if (fromCloud) state.remoteChosen + field
                else state.remoteChosen - field,
            )
        }
    }

    /** Запись по полям: локальное значение остаётся, если для поля не выбрано облачное. */
    suspend fun apply() {
        val shown = _state.value.selected ?: return
        if (_state.value.saving) return
        val chosen = _state.value.remoteChosen
        _state.update { it.copy(saving = true) }
        finish(guarded(shown) { resolveConflict(it, chosen) })
    }

    /** Исход конфликта-удаления. */
    suspend fun resolveDeletion(choice: DeletionChoice) {
        val shown = _state.value.selected ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        finish(guarded(shown) { resolveConflict.resolveDeletion(it, choice) })
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /**
     * Запись с проверкой свежести: применяем выбор только к тому, что видел пользователь.
     * При совпадении `invoke` перечитывает конфликт ещё раз и накладывает текущие значения.
     */
    private suspend fun guarded(shown: SyncConflict, action: suspend (SyncConflict) -> Unit): Notice? {
        val current = conflictRepository.get(shown.id)
        if (current == null || !current.showsSameState(shown)) return Notice.STALE
        return try {
            action(current)
            null
        } catch (stale: IllegalArgumentException) {
            Notice.STALE
        }
    }

    /** Разбор закончен — успешно или нет: подробности экран показывает из очереди. */
    private fun finish(notice: Notice?) {
        _state.value = State(notice = notice)
    }
}
