package com.example.mycalendar.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mycalendar.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    /** Пользователь нажал «Войти» — пора открывать календарь. */
    val entered: Boolean = false,
)

/**
 * ViewModel стартового экрана.
 *
 * Вход в ПРИЛОЖЕНИЕ больше не связан с Google/Яндекс: это просто локальный старт.
 * Календари подключаются отдельно, в настройках — там же и синхронизация.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /**
     * Запрашивали ли уже разрешение на уведомления при первом запуске.
     * Системный диалог POST_NOTIFICATIONS показывается один раз при первом входе.
     */
    val notificationsAsked: StateFlow<Boolean> = settingsRepository.notificationsAsked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Отметить: диалог разрешения на уведомления уже показывали. */
    fun onNotificationsResult() {
        viewModelScope.launch { settingsRepository.setNotificationsAsked() }
    }

    /**
     * Вход в приложение (локальный). Флаг onboardingCompleted нужен, чтобы при
     * следующих запусках сплеш-экран сразу открывал календарь.
     */
    fun enter() {
        viewModelScope.launch {
            settingsRepository.setOnboardingCompleted()
            _uiState.update { it.copy(entered = true) }
        }
    }
}
