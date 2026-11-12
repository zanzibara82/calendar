package com.example.mycalendar.desktop.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.mycalendar.desktop.theme.Palette
import com.example.mycalendar.desktop.theme.dayContainer
import com.example.mycalendar.domain.calendar.monthWeeks
import com.example.mycalendar.domain.holiday.DayMark
import com.example.mycalendar.domain.holiday.HolidayKey
import com.example.mycalendar.domain.holiday.HolidayRegistry
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val RU = Locale.forLanguageTag("ru")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)

/** Месячный вид: сетка Пн–Вс, тон выходного и праздника из общего домена. */
@Composable
fun MonthView() {
    var visibleMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    val registry = remember { HolidayRegistry() }

    val weeks = remember(visibleMonth) { monthWeeks(visibleMonth.atDay(1)) }
    val marks = remember(weeks) { registry.marksBetween(weeks.first().first(), weeks.last().last()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Header(
            month = visibleMonth,
            onPrevious = { visibleMonth = visibleMonth.minusMonths(1) },
            onNext = { visibleMonth = visibleMonth.plusMonths(1) },
            onToday = {
                val today = LocalDate.now()
                visibleMonth = YearMonth.from(today)
                selectedDate = today
            },
        )
        WeekdayHeader()
        weeks.forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                week.forEach { date ->
                    DayCell(
                        date = date,
                        inMonth = YearMonth.from(date) == visibleMonth,
                        mark = marks[date],
                        isToday = date == LocalDate.now(),
                        isSelected = date == selectedDate,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = { selectedDate = date },
                    )
                }
            }
        }
        Footer(date = selectedDate, holidayKey = registry.holidayKey(selectedDate))
    }
}

@Composable
private fun Header(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = monthTitle(month),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onPrevious) { Text("‹") }
        TextButton(onClick = onToday) { Text("Сегодня") }
        TextButton(onClick = onNext) { Text("›") }
    }
}

@Composable
private fun WeekdayHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DayOfWeek.entries.sortedBy { it.value }.forEach { day ->
            Text(
                text = capitalize(day.getDisplayName(TextStyle.SHORT_STANDALONE, RU)),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                color = Palette.OnSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    mark: DayMark?,
    isToday: Boolean,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val borderColor = when {
        isSelected -> Palette.Primary
        isToday -> Palette.Outline
        else -> Palette.Background
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(dayContainer(mark))
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            color = if (inMonth) Palette.OnSurface else Palette.OnSurfaceVariant,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (mark == DayMark.HOLIDAY) {
            // Форма-маркер: по одному тону праздник не отличить от выходного (WCAG 1.4.1).
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Palette.Primary),
            )
        }
    }
}

@Composable
private fun Footer(date: LocalDate, holidayKey: HolidayKey?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val title = holidayKey?.let(::holidayTitle)
        Text(
            text = if (title != null) "${date.format(DATE_FORMAT)} — $title" else date.format(DATE_FORMAT),
            color = Palette.OnSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        LegendItem(Palette.WeekendContainer, "Выходной")
        LegendItem(Palette.HolidayContainer, "Праздник")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, color = Palette.OnSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

private fun monthTitle(month: YearMonth): String =
    capitalize(month.month.getDisplayName(TextStyle.FULL_STANDALONE, RU)) + " " + month.year

private fun capitalize(text: String): String =
    text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
