package com.example.mycalendar.presentation.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mycalendar.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Splash: решает, куда идти — на стартовый экран или сразу в календарь.
 *
 * Раньше ориентировались на наличие аккаунта (Google/Яндекс), но приложение
 * больше не требует входа: критерий — заходил ли пользователь хоть раз
 * (флаг onboardingCompleted).
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** true — пользователь уже заходил, можно открывать календарь. */
    fun resolveDestination(onResult: (alreadyEntered: Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(settingsRepository.onboardingCompleted.first())
        }
    }
}
