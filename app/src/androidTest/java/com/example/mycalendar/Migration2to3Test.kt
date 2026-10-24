package com.example.mycalendar

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.data.local.MyCalendarDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Миграция 2 -> 3 не должна терять события: старые строки целы, syncBase у них null,
 * таблица конфликтов создана и совпадает со схемой 3.json.
 */
@RunWith(AndroidJUnit4::class)
class Migration2to3Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MyCalendarDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun `migration keeps events and adds sync state`() {
        val dbName = "migration-2-to-3"
        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO events (id, title, description, startTime, endTime, reminderMinutes, " +
                    "repeatRule, textNote, tags, voiceNotePath, calendarProvider, remoteId, updatedAt, " +
                    "isDeleted, pendingSync) " +
                    "VALUES ('e1', 'Встреча с мамой', 'Описание', 1000, 2000, '5,15', " +
                    "NULL, 'Заметка', 'BIRTHDAY', NULL, 'google', 'r1', 100, 0, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 3, true, MyCalendarDatabase.MIGRATION_2_3)

        db.query("SELECT title, textNote, syncBase FROM events WHERE id = 'e1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Встреча с мамой", cursor.getString(0))
            assertEquals("Заметка", cursor.getString(1))
            assertTrue(cursor.isNull(2))
        }
        db.query("SELECT COUNT(*) FROM sync_conflicts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }
}
