package com.example.mycalendar.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Локальная база данных приложения.
 */
@Database(
    entities = [EventEntity::class, SyncConflictEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class MyCalendarDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun syncConflictDao(): SyncConflictDao

    companion object {
        const val DB_NAME = "my_calendar.db"

        /** v2: добавлена колонка tags (метки события: День рождения/Свидание/Кино). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v3: сохранность данных при конфликтах — снимок-предок syncBase и очередь
         * открытых конфликтов. SQL должен совпадать с app/schemas/.../3.json.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN syncBase TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_conflicts (" +
                        "id TEXT NOT NULL, " +
                        "eventId TEXT NOT NULL, " +
                        "provider TEXT NOT NULL, " +
                        "remoteId TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "base TEXT, " +
                        "remote TEXT NOT NULL, " +
                        "contestedFields TEXT NOT NULL, " +
                        "detectedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id), " +
                        "FOREIGN KEY(eventId) REFERENCES events(id) ON UPDATE NO ACTION ON DELETE CASCADE" +
                        ")"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_sync_conflicts_eventId " +
                        "ON sync_conflicts(eventId)"
                )
            }
        }
    }
}
