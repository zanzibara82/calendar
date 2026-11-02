package com.example.mycalendar

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.model.ThemeMode
import com.example.mycalendar.presentation.components.EventCard
import com.example.mycalendar.presentation.components.EventOccurrence
import com.example.mycalendar.presentation.theme.MyCalendarTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Карточка события — один компонент на все виды календаря.
 * Проверяем, что в неё попадает из модели и что семантика остаётся «кликабельный узел
 * с названием»: на этом держится eventCard() в AppUiTest.
 */
@RunWith(AndroidJUnit4::class)
class EventCardUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val day: LocalDate = LocalDate.of(2026, 11, 5)

    private fun millis(hour: Int, minute: Int): Long =
        day.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun occurrence(
        title: String = "Созвон с подрядчиком",
        provider: CalendarProvider = CalendarProvider.YANDEX,
        reminders: List<Int> = listOf(15),
        repeat: RepeatRule? = null,
        tags: List<EventTag> = listOf(EventTag.BIRTHDAY),
        voice: String? = null,
        note: String? = null,
    ) = EventOccurrence(
        event = Event(
            id = "card-$title",
            title = title,
            startTime = millis(9, 30),
            endTime = millis(10, 15),
            reminderMinutes = reminders,
            repeatRule = repeat,
            tags = tags,
            voiceNotePath = voice,
            textNote = note,
            calendarProvider = provider,
        ),
        startMillis = millis(9, 30),
        endMillis = millis(10, 15),
    )

    private fun show(card: EventOccurrence) {
        rule.setContent {
            MyCalendarTheme(themeMode = ThemeMode.DARK) {
                EventCard(occurrence = card, onClick = { })
            }
        }
    }

    @Test
    fun card_showsTimeTitleAndProviderChip() {
        show(occurrence())
        rule.onNodeWithText("Созвон с подрядчиком").assertExists()
        rule.onNodeWithText("09:30").assertExists()
        rule.onNodeWithText("10:15").assertExists()
        rule.onNodeWithText("Яндекс").assertExists()
    }

    @Test
    fun card_rendersTagChips() {
        show(occurrence(tags = listOf(EventTag.BIRTHDAY, EventTag.CINEMA)))
        rule.onNodeWithText("День рождения").assertExists()
        rule.onNodeWithText("Кино").assertExists()
    }

    @Test
    fun card_withoutTagsHasNoTagChips() {
        show(occurrence(tags = emptyList()))
        rule.onNodeWithText("День рождения").assertDoesNotExist()
    }

    @Test
    fun card_longTitleIsOneLineAndCardClickable() {
        val longTitle = "Очень длинное название события, которое не влезает в одну строку"
        rule.setContent {
            MyCalendarTheme(themeMode = ThemeMode.DARK) {
                Column {
                    EventCard(occurrence = occurrence(title = "Короткое"), onClick = { })
                    EventCard(occurrence = occurrence(title = longTitle), onClick = { })
                }
            }
        }
        val short = rule.onNode(hasText("Короткое") and hasClickAction()).fetchSemanticsNode()
        val long = rule.onNode(
            hasText(longTitle, substring = true) and hasClickAction(),
        ).fetchSemanticsNode()
        // Перенос заголовка на вторую строку сделал бы карточку выше. Равные высоты — это и есть
        // проверка maxLines = 1; отдельная проверка config.contains(OnClick) была бы тавтологией:
        // матчер hasClickAction() читает ровно тот же ключ SemanticsActions.OnClick.
        assertEquals(short.boundsInRoot.height, long.boundsInRoot.height, 0.5f)
    }

    @Test
    fun card_threeTagsStillRendersProviderChip() {
        show(occurrence(tags = EventTag.entries))
        rule.onNodeWithText("Яндекс").assertExists()
        rule.onNodeWithText("День рождения").assertExists()
        rule.onNodeWithText("Свидание").assertExists()
        rule.onNodeWithText("Кино").assertExists()
    }

    @Test
    fun card_isSingleMergedClickableNode() {
        show(occurrence())
        val nodes = rule.onAllNodes(hasText("Созвон с подрядчиком") and hasClickAction())
            .fetchSemanticsNodes()
        assertEquals(1, nodes.size)
    }
}
