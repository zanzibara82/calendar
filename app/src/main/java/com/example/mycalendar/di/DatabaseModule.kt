package com.example.mycalendar.di

import android.content.Context
import androidx.room.Room
import com.example.mycalendar.data.local.EventDao
import com.example.mycalendar.data.local.MyCalendarDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MyCalendarDatabase =
        Room.databaseBuilder(context, MyCalendarDatabase::class.java, MyCalendarDatabase.DB_NAME)
            .addMigrations(MyCalendarDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideEventDao(database: MyCalendarDatabase): EventDao = database.eventDao()
}
