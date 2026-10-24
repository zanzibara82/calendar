package com.example.mycalendar.data.system

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.mycalendar.data.remote.RemoteCalendarDataSource
import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.di.IoDispatcher
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** Описание календаря из системного CalendarProvider. */
data class SystemCalendar(
    val id: Long,
    /** Имя аккаунта-владельца (обычно e-mail). */
    val accountName: String,
    val accountType: String,
    val displayName: String,
    val isPrimary: Boolean,
)

/**
 * Реализация [RemoteCalendarDataSource] поверх системного календаря Android
 * ([CalendarContract]) — БЕЗ OAuth и регистрации в Google Cloud Console.
 *
 * ПОЧЕМУ ТАК: аккаунт Google уже добавлен на телефон, а его календарь уже
 * синхронизируется системой в локальный CalendarProvider. Значит, приложению
 * достаточно runtime-разрешения READ/WRITE_CALENDAR, чтобы читать и писать
 * события этого аккаунта — никаких client_id, SHA-1 и consent-экранов.
 *
 * ОСОБЕННОСТИ (важно для синхронизации):
 *  - у [CalendarContract.Events] НЕТ колонки «время изменения», поэтому
 *    [hasReliableUpdatedAt] = false и инкрементальный pull по updatedMin
 *    невозможен: каждая синхронизация читает все события выбранного календаря,
 *    а решение о слиянии сравнивает снимок-предок (`SyncConflictResolver.decide`),
 *    а не время изменения;
 *  - удалённые события приходят со флагом DELETED=1: строка вычищается локально
 *    только когда неотправленных правок на ней нет, иначе удаление становится
 *    конфликтом;
 *  - запись идёт через CalendarProvider: выставленный им DIRTY-флаг заставляет
 *    системный sync adapter отправить событие в Google самостоятельно.
 */
@Singleton
class SystemCalendarDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : RemoteCalendarDataSource {

    private object Keys {
        val CALENDAR_ID = longPreferencesKey("system_calendar_id")
        val CALENDAR_ACCOUNT = stringPreferencesKey("system_calendar_account")
    }

    private companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        const val DEFAULT_DURATION_MILLIS = 3_600_000L
        const val NO_PERMISSION = "Нет доступа к календарю телефона. " +
            "Разрешите чтение и запись календаря при подключении Google Календаря."
    }

    /** Системный календарь не отдаёт время изменения события. */
    override val hasReliableUpdatedAt: Boolean get() = false

    // ---------- Выбор календаря ----------

    /**
     * Выбрать календарь для синхронизации и запомнить его навсегда
     * (чтобы после перезапуска процесса синхронизация шла в тот же календарь).
     */
    suspend fun selectPrimaryCalendar(): SystemCalendar = withContext(ioDispatcher) {
        ensurePermission()
        resolveAndPersist(accountName = null)
    }

    /** Все календари, в которые можно писать (для диагностики и будущего выбора). */
    suspend fun writableCalendars(): List<SystemCalendar> = withContext(ioDispatcher) {
        ensurePermission()
        queryCalendars()
    }

    /** Признак «разрешение на календарь выдано». */
    fun hasCalendarPermission(): Boolean = checkPermission()

    private suspend fun currentCalendar(): SystemCalendar {
        val saved = dataStore.data.first()[Keys.CALENDAR_ID]
        if (saved != null) {
            queryCalendars().firstOrNull { it.id == saved }?.let { return it }
        }
        return resolveAndPersist(dataStore.data.first()[Keys.CALENDAR_ACCOUNT])
    }

    private suspend fun resolveAndPersist(accountName: String?): SystemCalendar {
        val calendars = queryCalendars()
        if (calendars.isEmpty()) {
            error("На телефоне нет календарей, доступных для записи")
        }
        // Порядок важен: сначала точное совпадение аккаунта, затем Google-календари
        // (именно они синхронизируются с Google), затем всё остальное.
        val chosen = calendars.firstOrNull { it.accountName == accountName }
            ?: calendars.firstOrNull { it.isPrimary && it.accountType == GOOGLE_ACCOUNT_TYPE }
            ?: calendars.firstOrNull { it.accountType == GOOGLE_ACCOUNT_TYPE }
            ?: calendars.firstOrNull { it.isPrimary }
            ?: calendars.first()
        dataStore.edit { prefs ->
            prefs[Keys.CALENDAR_ID] = chosen.id
            prefs[Keys.CALENDAR_ACCOUNT] = chosen.accountName
        }
        return chosen
    }

    // ---------- RemoteCalendarDataSource ----------

    override suspend fun fetchUpdatedSince(sinceMillis: Long): List<RemoteEvent> =
        withContext(ioDispatcher) {
            ensurePermission()
            val calendar = currentCalendar()
            val observedAt = System.currentTimeMillis()

            val events = queryEvents(calendar.id, observedAt)
            val reminders = queryReminders(events.mapNotNull { it.remoteId.toLongOrNull() }.toSet())
            events.map { event ->
                val id = event.remoteId.toLongOrNull() ?: return@map event
                val minutes = reminders[id] ?: return@map event
                event.copy(reminderMinutes = minutes)
            }
        }

    override suspend fun insert(event: Event): String = withContext(ioDispatcher) {
        ensurePermission()
        val calendar = currentCalendar()
        val uri = context.contentResolver.insert(
            CalendarContract.Events.CONTENT_URI,
            event.toContentValues(calendar.id),
        ) ?: error("Не удалось создать событие в системном календаре")
        val id = ContentUris.parseId(uri)
        writeReminders(id, event.reminderMinutes)
        id.toString()
    }

    override suspend fun update(remoteId: String, event: Event) {
        withContext(ioDispatcher) {
            ensurePermission()
            val calendar = currentCalendar()
            val id = remoteId.toLongOrNull() ?: error("Некорректный id системного календаря: $remoteId")
            val updated = context.contentResolver.update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                event.toContentValues(calendar.id),
                null,
                null,
            )
            if (updated <= 0) error("Событие $remoteId не найдено в системном календаре")
            writeReminders(id, event.reminderMinutes)
        }
    }

    override suspend fun delete(remoteId: String) {
        withContext(ioDispatcher) {
            ensurePermission()
            val id = remoteId.toLongOrNull() ?: return@withContext
            context.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                null,
                null,
            )
        }
    }

    // ---------- Чтение ----------

    private fun queryCalendars(): List<SystemCalendar> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val result = mutableListOf<SystemCalendar>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            val iId = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val iName = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            val iType = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
            val iTitle = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val iAccess = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            val iPrimary = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
            while (cursor.moveToNext()) {
                // Меньше CONTRIBUTOR — календарь только для чтения
                if (cursor.getInt(iAccess) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                result += SystemCalendar(
                    id = cursor.getLong(iId),
                    accountName = cursor.getString(iName).orEmpty(),
                    accountType = cursor.getString(iType).orEmpty(),
                    displayName = cursor.getString(iTitle).orEmpty(),
                    isPrimary = cursor.getInt(iPrimary) == 1,
                )
            }
        }
        return result
    }

    private fun queryEvents(calendarId: Long, observedAt: Long): List<RemoteEvent> {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.DELETED,
        )
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ?"
        val events = mutableListOf<RemoteEvent>()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            arrayOf(calendarId.toString()),
            null,
        )?.use { cursor ->
            val iId = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
            val iTitle = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val iDescr = cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
            val iStart = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
            val iEnd = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
            val iDuration = cursor.getColumnIndexOrThrow(CalendarContract.Events.DURATION)
            val iAllDay = cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
            val iRrule = cursor.getColumnIndexOrThrow(CalendarContract.Events.RRULE)
            val iDeleted = cursor.getColumnIndexOrThrow(CalendarContract.Events.DELETED)
            while (cursor.moveToNext()) {
                val start = cursor.getLong(iStart)
                // DURATION вместо DTEND у событий с длительностью (RFC2445)
                val end = cursor.getLong(iEnd).takeIf { it > 0 }
                    ?: parseDuration(cursor.getString(iDuration), start)
                events += RemoteEvent(
                    remoteId = cursor.getLong(iId).toString(),
                    title = cursor.getString(iTitle)?.takeIf { it.isNotBlank() } ?: "(без названия)",
                    description = cursor.getString(iDescr),
                    startTime = start,
                    endTime = end,
                    reminderMinutes = emptyList(), // заполняются отдельным запросом
                    repeatRule = cursor.getString(iRrule)?.toRepeatRule(),
                    // У системного календаря нет времени изменения: фиксируем
                    // момент наблюдения (на сравнение это не влияет — см. `SyncConflictResolver.decide`)
                    updatedAt = observedAt,
                    deleted = cursor.getInt(iDeleted) == 1,
                )
            }
        }
        return events
    }

    /** Напоминания событий: [CalendarContract.Reminders] — отдельная таблица. */
    private fun queryReminders(eventIds: Set<Long>): Map<Long, List<Int>> {
        if (eventIds.isEmpty()) return emptyMap()
        val projection = arrayOf(
            CalendarContract.Reminders.EVENT_ID,
            CalendarContract.Reminders.MINUTES,
        )
        val result = mutableMapOf<Long, MutableList<Int>>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val iEvent = cursor.getColumnIndexOrThrow(CalendarContract.Reminders.EVENT_ID)
                val iMinutes = cursor.getColumnIndexOrThrow(CalendarContract.Reminders.MINUTES)
                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(iEvent)
                    if (eventId !in eventIds) continue
                    val minutes = cursor.getInt(iMinutes)
                    if (minutes >= 0) result.getOrPut(eventId) { mutableListOf() }.add(minutes)
                }
            }
        }
        return result
    }

    // ---------- Запись ----------

    private fun Event.toContentValues(calendarId: Long): ContentValues = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.DESCRIPTION, description)
        put(CalendarContract.Events.DTSTART, startTime)
        put(CalendarContract.Events.DTEND, endTime)
        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        put(CalendarContract.Events.ALL_DAY, 0)
        put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
        put(CalendarContract.Events.HAS_ALARM, if (reminderMinutes.isEmpty()) 0 else 1)
        repeatRule?.let { put(CalendarContract.Events.RRULE, "FREQ=${it.name}") }
    }

    /** Полная перезапись напоминаний события (CalendarProvider не умеет update). */
    private fun writeReminders(eventId: Long, minutes: List<Int>) {
        runCatching {
            context.contentResolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
            )
        }
        val unique = minutes.distinct().filter { it >= 0 }
        if (unique.isEmpty()) return
        val values = unique.map { m ->
            ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, m)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
        }
        runCatching {
            context.contentResolver.bulkInsert(
                CalendarContract.Reminders.CONTENT_URI,
                values.toTypedArray(),
            )
        }
    }

    // ---------- Разрешения ----------

    private fun ensurePermission() {
        if (!checkPermission()) error(NO_PERMISSION)
    }

    private fun checkPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    // ---------- Маппинг ----------

    /** RFC2445-длительность ("PT1H", "P3600S") -> millis; при неудаче — +1 час. */
    private fun parseDuration(raw: String?, start: Long): Long {
        val value = raw ?: return start + DEFAULT_DURATION_MILLIS
        var millis = 0L
        Regex("(\\d+)W").find(value)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?.let { millis += it * 7 * 86_400_000L }
        Regex("(\\d+)D").find(value)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?.let { millis += it * 86_400_000L }
        val time = value.substringAfter('T', "")
        Regex("(\\d+)H").find(time)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?.let { millis += it * 3_600_000L }
        Regex("(\\d+)M").find(time)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?.let { millis += it * 60_000L }
        Regex("(\\d+)S").find(time)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?.let { millis += it * 1_000L }
        return if (millis > 0) start + millis else start + DEFAULT_DURATION_MILLIS
    }

    private fun String.toRepeatRule(): RepeatRule? {
        val freq = substringAfter("FREQ=", "").substringBefore(";").uppercase()
        return runCatching { RepeatRule.valueOf(freq) }.getOrNull()
    }
}
