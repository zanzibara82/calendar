package com.example.mycalendar.presentation.navigation

/**
 * Маршруты приложения (Navigation Compose).
 */
sealed class Screen(val route: String) {

    data object Splash : Screen("splash")
    data object Auth : Screen("auth")
    data object Calendar : Screen("calendar")
    data object Settings : Screen("settings")
    data object Permissions : Screen("permissions")

    /** Создание/редактирование события. eventId = null — создание нового. */
    data object CreateEditEvent : Screen("edit_event?eventId={eventId}") {
        fun create(eventId: String? = null): String =
            if (eventId == null) "edit_event" else "edit_event?eventId=$eventId"
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
