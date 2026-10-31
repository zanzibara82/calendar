package com.example.mycalendar

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.MainActivity
import com.example.mycalendar.data.auth.OAuthConfig
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Сквозной UI-тест на Compose: вход как гость -> календарь -> создание события ->
 * детали -> удаление -> настройки/тема. Выполняется на каждой версии Android
 * матрицы (API 28..37) через connectedDebugAndroidTest.
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var eventRepository: EventRepository

    @Before
    fun wakeScreenUp() {
        // HiltAndroidRule НЕ инжектит поля теста автоматически — нужен явный
        // вызов inject(), иначе @Inject lateinit-поля останутся неинициализированными.
        hiltRule.inject()

        // На headless-эмуляторе перед первым касанием нужно разбудить экран
        // и снять keyguard, иначе инжект тачей падает
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            ui.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            ui.executeShellCommand("svc power stayon true")
            ui.executeShellCommand("wm dismiss-keyguard")
            // Автовыдача уведомлений (Android 13+), чтобы системный диалог
            // первого запуска не перекрывал UI-тесты
            if (Build.VERSION.SDK_INT >= 33) {
                ui.executeShellCommand(
                    "pm grant ${InstrumentationRegistry.getInstrumentation().targetContext.packageName}" +
                        " android.permission.POST_NOTIFICATIONS"
                )
            }
        }
        // Сбрасываем флаг онбординга перед КАЖДЫМ тестом: DataStore общий для
        // процесса, и после клика «Войти» в предыдущем тесте флаг остаётся true,
        // из-за чего Splash пропускает экран входа и тесты, ждущие «Войти»,
        // падают с таймаутом. Сброс гарантирует детерминированный старт.
        runCatching { runBlocking { settingsRepository.setOnboardingCompleted(false) } }
        // DataStore-аккаунты тоже общие для процесса: если перед прогоном кто-то
        // (другой тест или ручная проверка) подключил Google/Яндекс, карточки
        // перестают показывать «Подключить» и счётчики в settings_showsCalendarConnections
        // расходятся. Принудительно отключаем все провайдеры.
        runCatching { runBlocking { authRepository.signOutAll() } }
        Thread.sleep(1_000)
    }

    /** Поиск по тексту в unmerged tree: FAB и сегментные кнопки не всегда мерджат текст. */
    private fun nodesWithText(text: String) =
        composeRule.onAllNodesWithText(text, useUnmergedTree = true)

    private fun nodeWithText(text: String) =
        composeRule.onNodeWithText(text, useUnmergedTree = true)

    /** Клик с повторными попытками: первый инжект тача может быть отклонён системой. */
    private fun clickWithRetry(text: String, attempts: Int = 3, useUnmerged: Boolean = true) {
        var lastError: Throwable? = null
        repeat(attempts) {
            try {
                composeRule.onNodeWithText(text, useUnmergedTree = useUnmerged).performClick()
                return
            } catch (e: AssertionError) {
                lastError = e
                Thread.sleep(800)
            }
        }
        throw lastError ?: AssertionError("Не удалось кликнуть: $text")
    }

    /** Ожидание выбора сегментной кнопки (обновление приходит через DataStore асинхронно). */
    private fun waitForSelected(text: String) {
        repeat(20) {
            try {
                composeRule.onNodeWithText(text).assertIsSelected()
                return
            } catch (e: AssertionError) {
                Thread.sleep(500)
            }
        }
        composeRule.onNodeWithText(text).assertIsSelected()
    }

    /**
     * Гарантированно попасть на экран календаря из любого стартового состояния.
     *
     * Activity в тест-классе ПЕРЕИСПОЛЬЗУЕТСЯ между методами (ActivityTestRule), поэтому
     * после предыдущего теста мы можем уже быть на календаре — тогда кнопка «Войти»
     * отсутствует и ждать её нельзя. Кликаем «Войти» только если он есть.
     */
    private fun reachCalendar(res: android.content.res.Resources) {
        composeRule.waitUntil(20_000) {
            nodesWithText(res.getString(R.string.enter_app)).fetchSemanticsNodes().isNotEmpty() ||
                nodesWithText(res.getString(R.string.view_month)).fetchSemanticsNodes().isNotEmpty()
        }
        if (nodesWithText(res.getString(R.string.enter_app)).fetchSemanticsNodes().isNotEmpty()) {
            clickWithRetry(res.getString(R.string.enter_app))
        }
        composeRule.waitUntil(20_000) {
            nodesWithText(res.getString(R.string.view_month)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Гарантированно попасть на экран настроек из любого состояния.
     * Учитывает переиспользование Activity: можем уже быть на календаре, настройках
     * или стартовом экране.
     */
    private fun reachSettings(res: android.content.res.Resources) {
        // Уже на настройках?
        if (nodesWithText(res.getString(R.string.calendars_section)).fetchSemanticsNodes().isNotEmpty()) {
            return
        }
        reachCalendar(res)
        composeRule.onNodeWithContentDescription(res.getString(R.string.settings)).performClick()
        composeRule.waitUntil(20_000) {
            nodesWithText(res.getString(R.string.calendars_section)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Ввод текста во ВСЕ текстовые поля экрана (title + description):
     * порядок unmerged-нод не гарантирован, но title гарантированно получит текст.
     */
    private fun typeIntoAllFields(text: String) {
        val interactions = composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        repeat(interactions.fetchSemanticsNodes().size) { index ->
            interactions[index].performTextInput(text)
        }
    }

    /** Видна ли панель IME — по WindowInsets, а не по флагам InputMethodManager. */
    private fun isImeVisible(): Boolean {
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val decor = composeRule.activity.window.decorView
            visible = ViewCompat.getRootWindowInsets(decor)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    /**
     * Спрятать клавиатуру, не трогая навигацию (иначе перекрывает кнопку «Сохранить»).
     *
     * KEYCODE_BACK здесь — лотерея: на targetSdk 36+ predictive back включён по
     * умолчанию, и если IME в этот момент ещё не показан, событие уходит в
     * OnBackInvokedCallback приложения и закрывает экран создания.
     */
    private fun hideKeyboard() {
        // IME поднимается асинхронно: дожидаемся её, иначе запрос скрытия уйдёт в пустоту.
        val deadline = System.currentTimeMillis() + 3_000
        while (!isImeVisible() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        if (!isImeVisible()) return
        composeRule.activityRule.scenario.onActivity { activity ->
            WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        // Пока IME на экране, клик по кнопке под ней попадёт в окно клавиатуры.
        composeRule.waitUntil(5_000) { !isImeVisible() }
    }

    /** Карточка события «Test event» в календаре (уникальный merged-кликабельный узел). */
    private fun eventCard() =
        composeRule.onNode(
            hasText("Test event") and hasClickAction(),
            useUnmergedTree = false,
        )

    @Test
    fun launch_showsAuthScreenWithGuestOption() {
        // Гарантируем старт именно с экрана входа (переиспользование Activity
        // и общий DataStore могут оставить флаг онбординга = true от прошлых тестов)
        runBlocking { settingsRepository.setOnboardingCompleted(false) }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            nodesWithText(composeRule.activity.getString(R.string.enter_app))
                .fetchSemanticsNodes().isNotEmpty()
        }
        nodeWithText(composeRule.activity.getString(R.string.enter_app)).assertExists()
    }

    @Test
    fun guestFlow_createEvent_viewDetails_deleteEvent() {
        val res = composeRule.activity.resources

        // 1-2. Стартовый экран -> Календарь (учитывает переиспользование Activity)
        reachCalendar(res)

        // 3. Создание события
        composeRule.waitUntil(10_000) {
            nodesWithText(res.getString(R.string.new_event)).fetchSemanticsNodes().isNotEmpty()
        }
        clickWithRetry(res.getString(R.string.new_event))
        composeRule.waitUntil(10_000) {
            nodesWithText(res.getString(R.string.event_title_hint))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Вводим текст во все поля (title гарантированно получит), прячем клавиатуру,
        // докручиваем до кнопки «Сохранить» (экран длинный, кнопка может быть за пределами)
        typeIntoAllFields("Test event")
        hideKeyboard()
        composeRule.onAllNodes(hasScrollAction(), useUnmergedTree = true)[0]
            .performScrollToNode(hasText(res.getString(R.string.save)))
        clickWithRetry(res.getString(R.string.save))

        // 4. Вернулись в календарь (сегмент «Месяц» есть только там); событие появилось в списке
        composeRule.waitUntil(20_000) {
            nodesWithText(res.getString(R.string.view_month)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(15_000) {
            runCatching { eventCard().assertExists() }.isSuccess
        }

        // 5. Открытие деталей: карточка — merged-нода с текстом и click-action
        eventCard().performClick()
        composeRule.waitUntil(10_000) {
            nodesWithText(res.getString(R.string.event_details))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 6. Удаление: иконка Delete -> подтверждение в диалоге
        composeRule.onNodeWithContentDescription(res.getString(R.string.delete))
            .performClick()
        composeRule.waitUntil(10_000) {
            nodesWithText(res.getString(R.string.delete_event_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        nodesWithText(res.getString(R.string.delete))[0].performClick()

        // 7. Снова календарь, событие исчезло
        composeRule.waitUntil(15_000) {
            nodesWithText(res.getString(R.string.view_month)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(10_000) {
            runCatching { eventCard().assertDoesNotExist() }.isSuccess
        }
    }

    /**
     * Тап по дню в сетке выбирает его, а FAB «Новое событие» открывает редактор,
     * где этот день уже стоит в полях начала и конца.
     */
    @Test
    fun calendar_fabPrefillsSelectedDay() {
        val res = composeRule.activity.resources
        reachCalendar(res)

        // Activity переиспользуется между тестами: возвращаем текущий месяц,
        // чтобы сетка гарантированно содержала дни 11..24.
        composeRule.onNodeWithContentDescription(res.getString(R.string.today)).performClick()

        // Дни 11..24 в сетке уникальны: соседние месяцы дают только хвост 1..10
        // и начало 25..31, поэтому клик попадёт именно в день текущего месяца.
        val today = LocalDate.now()
        val selected = today.withDayOfMonth((11..24).first { it != today.dayOfMonth })

        clickWithRetry(selected.dayOfMonth.toString())
        clickWithRetry(res.getString(R.string.new_event))
        composeRule.waitUntil(10_000) {
            nodesWithText(res.getString(R.string.event_title_hint))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Поля дат два — начало и конец; оба заполнены выбранным днём,
        // а не сегодняшней датой по умолчанию.
        val expected = selected.format(
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ru"))
        )
        nodesWithText(expected).assertCountEquals(2)

        // Возвращаемся в календарь: следующие тесты стартуют с reachCalendar.
        composeRule.onNodeWithContentDescription(res.getString(R.string.back)).performClick()
        composeRule.waitUntil(10_000) {
            nodesWithText(res.getString(R.string.view_month)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun settings_showsCalendarConnections() {
        val res = composeRule.activity.resources
        // reachSettings уже открывает экран настроек из любого состояния
        reachSettings(res)

        // Видимые карточки календарей: Google всегда, Яндекс — только когда
        // в OAuthConfig задан YANDEX_CLIENT_ID (иначе его OAuth вернёт HTTP 400).
        nodeWithText(res.getString(R.string.provider_google)).assertExists()
        val expectedCards = if (OAuthConfig.isYandexConfigured) 2 else 1
        if (OAuthConfig.isYandexConfigured) {
            nodeWithText(res.getString(R.string.provider_yandex)).assertExists()
        }
        nodesWithText(res.getString(R.string.connect)).assertCountEquals(expectedCards)

        // Карточка обновлений: раздел, версия установленного APK и кнопка проверки.
        nodeWithText(res.getString(R.string.updates_section)).assertExists()
        nodeWithText(res.getString(R.string.update_check)).assertExists()
    }

    /**
     * Регресс: кнопка «Открыть событие» в окне напоминания ничего не делала.
     *
     * Причина была в цепочке доставки deep-link: `MainActivity.onNewIntent`
     * не вызывал `setIntent(intent)`, поэтому `EXTRA_EVENT_ID` терялся, а
     * `AppNavGraph` навигировал на детали слишком рано — Splash затем
     * вычищал их через `popUpTo(Splash){inclusive=true}`.
     *
     * Тест фиксирует, что intent с `EXTRA_EVENT_ID` реально открывает карточку
     * нужного события.
     */
    @Test
    fun deepLink_opensEventDetails() {
        // Arrange: событие создаём напрямую в репозитории — так мы точно знаем его id
        val now = System.currentTimeMillis()
        val event = Event(
            id = "deeplink-${System.nanoTime()}",
            title = "Deep link event ${System.currentTimeMillis() % 100_000}",
            description = "Событие для проверки deep-link из напоминания",
            startTime = now + 60 * 60 * 1000,
            endTime = now + 2 * 60 * 60 * 1000,
            calendarProvider = CalendarProvider.LOCAL,
        )
        runBlocking { eventRepository.saveFromUser(event) }

        try {
            val res = composeRule.activity.resources
            // Deep-link обрабатывается только после того, как Splash ушёл
            // на календарь — поэтому сначала гарантированно попадаем туда.
            reachCalendar(res)

            // ВАЖНО: ActivityScenario отождествляет Activity по intent.
            // Наш onNewIntent вызывает setIntent(deepLinkIntent) — это и есть
            // исправление бага, но после этого сценарий перестаёт узнавать
            // Activity ("intent does not match") и игнорирует её lifecycle,
            // падая на teardown с "never becomes DESTROYED".
            // Поэтому сохраняем исходный intent и возвращаем его в finally.
            val originalIntent = composeRule.activity.intent

            try {
                // Act: доставляем deep-link intent в живой MainActivity — то же,
                // что делает фреймворк при запуске из ReminderActivity/уведомления.
                // callActivityOnNewIntent (а не startActivity) — чтобы не менять
                // жизненный цикл Activity, за которой следит сценарий.
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                composeRule.activityRule.scenario.onActivity { activity ->
                    instrumentation.callActivityOnNewIntent(
                        activity,
                        MainActivity.createIntent(activity, event.id),
                    )
                }

                // Assert: открылась именно карточка этого события
                composeRule.waitUntil(15_000) {
                    nodesWithText(res.getString(R.string.event_details))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                nodeWithText(event.title).assertExists()
            } finally {
                runCatching {
                    composeRule.activityRule.scenario.onActivity { it.intent = originalIntent }
                }
            }
        } finally {
            // Не оставляем мусор в БД: остальные тесты считают события в календаре
            runCatching { runBlocking { eventRepository.hardDelete(event.id) } }
        }
    }

    @Test
    fun settings_openAndSwitchTheme() {
        val res = composeRule.activity.resources
        reachCalendar(res)

        // Настройки через иконку в тулбаре
        composeRule.onNodeWithContentDescription(res.getString(R.string.settings)).performClick()
        composeRule.waitUntil(10_000) {
            nodesWithText(res.getString(R.string.appearance)).fetchSemanticsNodes().isNotEmpty()
        }

        // Переключение на тёмную тему: выбор применяется асинхронно через DataStore.
        // Клик по MERGED-ноде сегмента (она несёт Role/Selected и OnClick)
        clickWithRetry(res.getString(R.string.theme_dark), useUnmerged = false)
        waitForSelected(res.getString(R.string.theme_dark))
        clickWithRetry(res.getString(R.string.theme_light), useUnmerged = false)
        waitForSelected(res.getString(R.string.theme_light))
    }
}
