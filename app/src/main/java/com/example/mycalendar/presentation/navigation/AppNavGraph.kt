package com.example.mycalendar.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.mycalendar.presentation.auth.AuthScreen
import com.example.mycalendar.presentation.calendar.CalendarScreen
import com.example.mycalendar.presentation.event.CreateEditEventScreen
import com.example.mycalendar.presentation.event.EventDetailsScreen
import com.example.mycalendar.presentation.permissions.PermissionsScreen
import com.example.mycalendar.presentation.settings.SettingsScreen
import com.example.mycalendar.presentation.splash.SplashScreen
import kotlinx.coroutines.flow.first

/**
 * Граф навигации приложения.
 *
 * @param startEventId id события для deep-link из уведомления/аларм-экрана.
 *  Навигация на детали откладывается до тех пор, пока [Screen.Splash] не
 *  завершит определение стартового экрана — иначе `popUpTo(Splash){inclusive=true}`
 *  в SplashScreen уничтожит только что добавленный EventDetails.
 */
@Composable
fun AppNavGraph(
    modifier: Modifier = Modifier,
    startEventId: String? = null,
    onEventOpened: () -> Unit = {},
) {
    val navController: NavHostController = rememberNavController()

    // Deep link из уведомления-напоминания: открываем детали события,
    // но только когда Splash уже определил реальный стартовый экран.
    // currentBackStackEntryFlow — это Flow, который эмитит при КАЖДОЙ смене
    // back stack (в отличие от currentDestination, который не является
    // Compose State и для snapshotFlow не отслеживается).
    LaunchedEffect(startEventId) {
        val id = startEventId ?: return@LaunchedEffect
        val currentRoute = navController.currentBackStackEntryFlow
            .first { it.destination.route != Screen.Splash.route }
            .destination.route
        if (currentRoute == Screen.Calendar.route) {
            navController.navigate(Screen.EventDetails.create(id))
            onEventOpened()
        }
        // Если пользователь ещё не залогинился (AuthScreen), deep-link
        // откладываем: после успешного входа Splash->Auth направит
        // его на Calendar, и повторный клик по уведомлению сработает.
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route,
        modifier = modifier,
    ) {
        composable(Screen.Splash.route) {
            SplashScreen(
                onGoToAuth = {
                    navController.navigate(Screen.Auth.route) { popUpTo(Screen.Splash.route) { inclusive = true } }
                },
                onGoToCalendar = {
                    navController.navigate(Screen.Calendar.route) { popUpTo(Screen.Splash.route) { inclusive = true } }
                },
            )
        }

        composable(Screen.Auth.route) {
            AuthScreen(
                onSignedIn = {
                    navController.navigate(Screen.Calendar.route) { popUpTo(Screen.Auth.route) { inclusive = true } }
                },
                onOpenPermissions = {
                    navController.navigate(Screen.Permissions.route)
                },
            )
        }

        composable(Screen.Calendar.route) {
            CalendarScreen(
                onCreateEvent = { navController.navigate(Screen.CreateEditEvent.create()) },
                onEventClick = { eventId -> navController.navigate(Screen.EventDetails.create(eventId)) },
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
            )
        }

        composable(
            route = Screen.CreateEditEvent.route,
            arguments = listOf(navArgument("eventId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getString("eventId")
            CreateEditEventScreen(
                eventId = eventId,
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
                onOpenPermissions = { navController.navigate(Screen.Permissions.route) },
            )
        }

        composable(
            route = Screen.EventDetails.route,
            arguments = listOf(navArgument("eventId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getString("eventId") ?: return@composable
            EventDetailsScreen(
                eventId = eventId,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(Screen.CreateEditEvent.create(eventId)) },
                onDeleted = { navController.popBackStack() },
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenPermissions = { navController.navigate(Screen.Permissions.route) },
            )
        }

        composable(Screen.Permissions.route) {
            PermissionsScreen(onBack = { navController.popBackStack() })
        }
    }
}
