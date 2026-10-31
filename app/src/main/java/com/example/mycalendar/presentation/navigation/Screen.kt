package com.example.mycalendar.presentation.navigation

import java.time.LocalDate

/**
 * Маршруты приложения (Navigation Compose).
 */
sealed class Screen(val route: String) {

    data object Splash : Screen("splash")
    data object Auth : Screen("auth")
    data object Calendar : Screen("calendar")
    data object Settings : Screen("settings")
    data object Permissions : Screen("permissions")

    /**
     * Создание/редактирование события. eventId = null — создание нового.
     *
     * @param date день, выбранный в календаре: подставляется в даты начала и
     *  конца нового события (ISO-8601, в маршруте безопасен).
     */
    data object CreateEditEvent : Screen("edit_event?eventId={eventId}&date={date}") {
        fun create(eventId: String? = null, date: LocalDate? = null): String {
            val params = listOfNotNull(
                eventId?.let { "eventId=$it" },
                date?.let { "date=$it" },
            )
            return if (params.isEmpty()) "edit_event" else "edit_event?" + params.joinToString("&")
        }
    }

    /** Детали события. */
    data object EventDetails : Screen("event_details/{eventId}") {
        fun create(eventId: String): String = "event_details/$eventId"
    }

    /** Очередь открытых конфликтов синхронизации. */
    data object Conflicts : Screen("conflicts")

    /** Разбор одного конфликта. */
    data object ConflictDetail : Screen("conflict/{conflictId}") {
        fun create(conflictId: String): String = "conflict/$conflictId"
    }
}
