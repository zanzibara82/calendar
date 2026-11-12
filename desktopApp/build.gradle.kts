@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Desktop-клиент. Пока только окно: домен берётся из :shared,
// UI-слой будет переноситься с Android на Compose Multiplatform.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    testImplementation(libs.junit)
    testImplementation(compose.uiTest)
}

compose.desktop {
    application {
        mainClass = "com.example.mycalendar.desktop.MainKt"

        nativeDistributions {
            // MSI требует WiX в PATH; на старте отдаём .exe-установщик.
            targetFormats(TargetFormat.Exe)
            packageName = "MyCalendar"
            packageVersion = "0.1.0"
            // Без jdk.localedata в jlink-образе нет русских данных CLDR, и месяцы
            // с днями недели молча печатаются по-английски, хотя в коде локаль ru.
            modules("jdk.localedata")
        }
    }
}
