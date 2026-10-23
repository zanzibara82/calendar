plugins {
    // Без версии: AGP уже лежит в classpath (объявлен в корневом build.gradle.kts)
    id("com.android.test")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.example.mycalendar.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // JVM-target должен совпадать с Java, иначе Kotlin-плагин падает с
    // «Inconsistent JVM Target Compatibility».
    kotlinOptions {
        jvmTarget = "17"
    }

    // Профиль собирается для :app
    targetProjectPath = ":app"
}

/**
 * Генерация профиля выполняется вручную и только при необходимости:
 *
 *   ./gradlew :app:generateReleaseBaselineProfile
 *
 * (нужен подключённый эмулятор/устройство с API 28+). Результат сохраняется
 * в app/src/release/generated/baselineProfiles и уезжает в release-сборку.
 */

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
