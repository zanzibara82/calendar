package com.example.mycalendar.desktop

/**
 * Версия из `nativeDistributions.packageVersion` (build.gradle передаёт её
 * свойством `mycalendar.version`). У запуска из IDE свойства нет — там `dev`.
 */
internal fun appVersion(property: String?): String =
    property?.takeIf { it.isNotBlank() } ?: "dev"
