package com.example.mycalendar.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Локальная база данных приложения.
 */
@Database(
    entities = [EventEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class MyCalendarDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        const val DB_NAME = "my_calendar.db"

        /** v2: добавлена колонка tags (метки события: День рождения/Свидание/Кино). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
