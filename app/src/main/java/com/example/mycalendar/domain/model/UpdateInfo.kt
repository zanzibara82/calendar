package com.example.mycalendar.domain.model

/** Сведения о релизе, найденном в GitHub Releases. */
data class UpdateInfo(
    val versionName: String,
    val apkUrl: String,
    val releaseUrl: String,
    val sizeBytes: Long,
    val notes: String,
)

/** Результат проверки обновлений. */
sealed interface UpdateCheck {
    /** Установлена последняя версия. */
    data object UpToDate : UpdateCheck

    data class Available(val info: UpdateInfo) : UpdateCheck

    /** В репозитории ещё нет опубликованных релизов — штатное состояние, не ошибка. */
    data object NoReleases : UpdateCheck

    data class Failed(val message: String) : UpdateCheck
}

/**
 * Сравнивает версии по числовым сегментам.
 *
 * Строковое сравнение ошибочно: "1.10" должно быть новее "1.9", а не наоборот.
 * Нечисловые хвосты (`-beta`, `+build`) отбрасываются — для канала стабильных
 * релизов этого достаточно, а предварительные версии GitHub в `latest` не отдаёт.
 * Если числовых сегментов нет вовсе, версии считаются разными, когда строки
 * различаются: лучше предложить обновление, чем промолчать.
 */
fun isNewerVersion(candidate: String, current: String): Boolean {
    val next = numericSegments(candidate)
    val installed = numericSegments(current)
    if (next.isEmpty() || installed.isEmpty()) {
        return candidate.trim() != current.trim()
    }
    for (index in 0 until maxOf(next.size, installed.size)) {
        val a = next.getOrElse(index) { 0 }
        val b = installed.getOrElse(index) { 0 }
        if (a != b) return a > b
    }
    return false
}

private fun numericSegments(version: String): List<Int> =
    version.trim()
        .removePrefix("v")
        .removePrefix("V")
        .split('.', '-', '+', '_')
        .mapNotNull { part -> part.takeWhile(Char::isDigit).toIntOrNull() }
