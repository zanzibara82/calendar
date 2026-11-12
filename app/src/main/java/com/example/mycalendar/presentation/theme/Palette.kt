package com.example.mycalendar.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Палитра SyncPlanner. Тёмная схема — эталон (тёмно-синий из макетов), светлая —
 * те же роли в тех же пропорциях. Значения подобраны по WCAG AA: см. PaletteContrastTest.
 *
 * Эти же числа переносит Windows-версия — менять их можно только вместе со спеком.
 */

// ---------- Тёмная схема (эталон) ----------

val DarkBackground = Color(0xFF0B1220)
val DarkSurface = Color(0xFF101B2E)
val DarkCard = Color(0xFF1B2A44)
val DarkOnSurface = Color(0xFFE8EEF9)
val DarkOnSurfaceVariant = Color(0xFF9FB0CC)
val DarkPrimary = Color(0xFF4790FF)
val DarkOnPrimary = Color(0xFF04122B)
val DarkPrimaryContainer = Color(0xFF16345F)
val DarkOnPrimaryContainer = Color(0xFFBBD8FF)
val DarkSecondary = Color(0xFFA9B7CF)
val DarkSecondaryContainer = Color(0xFF24334D)
val DarkTertiary = Color(0xFF9C8CFF)
val DarkOutline = Color(0xFF2C3C58)
val DarkError = Color(0xFFFFB4AB)

// ---------- Светлая схема (производная) ----------

val LightBackground = Color(0xFFF4F7FC)
val LightSurface = Color(0xFFFFFFFF)
val LightCard = Color(0xFFEAF0FA)
val LightOnSurface = Color(0xFF0F1B2D)
val LightOnSurfaceVariant = Color(0xFF4A5A73)
val LightPrimary = Color(0xFF2766D6)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFDCE8FF)
val LightOnPrimaryContainer = Color(0xFF06234F)
val LightSecondary = Color(0xFF4A5A73)
val LightSecondaryContainer = Color(0xFFDCE6F5)
val LightTertiary = Color(0xFF5B44D6)
val LightOutline = Color(0xFFC6D2E4)
val LightError = Color(0xFFB3261E)

// ---------- Маркеры дня: тон карточки выходного и праздника ----------
// Различаются только оттенком (бирюзовый против янтарного): по яркости разница
// 1.06–1.11, поэтому у праздника есть ещё и форма-маркер — WCAG 1.4.1.

val DarkDayWeekendContainer = Color(0xFF1A4657)
val DarkDayHolidayContainer = Color(0xFF5A3810)
val LightDayWeekendContainer = Color(0xFFA5E2C4)
val LightDayHolidayContainer = Color(0xFFF7CF88)

// ---------- Тон буднего дня и рамка клеток ----------
// Будни голубее нейтральной поверхности, но легче по весу, чем выходные и праздники:
// текст дня держится в AA (5.2–5.6). Рамка отделяет соседние клетки при любом тоне —
// её разница с тоном не ниже 1.3, иначе линия сливается с заливкой.

val DarkDayWeekdayContainer = Color(0xFF203A63)
val LightDayWeekdayContainer = Color(0xFFDCE6F5)
val DarkDayBorder = Color(0xFF46658F)
val LightDayBorder = Color(0xFFA6BAD6)

// ---------- Провайдеры: полоса и точка карточки ----------
// Разведены относительно primary, чтобы полоса не читалась как «выбрано».

val EventLocalColorDark = Color(0xFF7C5CFF)
val EventGoogleColorDark = Color(0xFF2E8DF5)
val EventYandexColorDark = Color(0xFFFF5252)

val EventLocalColorLight = Color(0xFF5B44D6)
val EventGoogleColorLight = Color(0xFF1A73E8)
val EventYandexColorLight = Color(0xFFDB3A22)
