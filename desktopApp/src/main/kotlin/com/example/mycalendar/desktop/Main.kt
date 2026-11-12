package com.example.mycalendar.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.mycalendar.desktop.calendar.MonthView
import com.example.mycalendar.desktop.theme.DesktopTheme

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "MyCalendar",
        state = rememberWindowState(width = 1000.dp, height = 760.dp),
    ) {
        DesktopTheme { MonthView() }
    }
}
