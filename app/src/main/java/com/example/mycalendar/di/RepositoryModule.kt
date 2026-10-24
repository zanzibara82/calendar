package com.example.mycalendar.di

import com.example.mycalendar.data.audio.VoiceNoteStorage
import com.example.mycalendar.data.auth.AccessTokenProvider
import com.example.mycalendar.data.auth.YandexAuthDataSource
import com.example.mycalendar.data.preferences.SettingsRepositoryImpl
import com.example.mycalendar.data.repository.AuthRepositoryImpl
import com.example.mycalendar.data.repository.ConflictRepositoryImpl
import com.example.mycalendar.data.repository.EventRepositoryImpl
import com.example.mycalendar.data.repository.SyncRepositoryImpl
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.ConflictRepository
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import com.example.mycalendar.domain.repository.SyncRepository
import com.example.mycalendar.domain.repository.VoiceNoteCleanup
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Связывание доменных интерфейсов репозиториев с data-реализациями. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindEventRepository(impl: EventRepositoryImpl): EventRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(impl: SyncRepositoryImpl): SyncRepository

    @Binds
    @Singleton
    abstract fun bindConflictRepository(impl: ConflictRepositoryImpl): ConflictRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    /** Токен Яндекса как testable-интерфейс (без Android Keystore в юнит-тестах). */
    @Binds
    @Singleton
    abstract fun bindAccessTokenProvider(impl: YandexAuthDataSource): AccessTokenProvider

    /** Чистка файла голосовой заметки: репозиторий зависит от контракта, а не от Android-реализации. */
    @Binds
    @Singleton
    abstract fun bindVoiceNoteCleanup(impl: VoiceNoteStorage): VoiceNoteCleanup
}
