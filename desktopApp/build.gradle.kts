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
    implementation(libs.jna.platform)

    testImplementation(libs.junit)
    testImplementation(compose.uiTest)
}

compose.desktop {
    application {
        mainClass = "com.example.mycalendar.desktop.MainKt"

        nativeDistributions {
            // MSI упаковывается WiX 3 (candle.exe/light.exe): плагин сам кладёт его в
            // ~/.gradle/compose-jb/wix311.zip при первом packageMsi, поэтому сети требует
            // только первый прогон. WiX 4 не подойдёт — jpackage знает лишь WiX 3.
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "MyCalendar"
            packageVersion = "0.1.0"
            vendor = "zanzibara82"
            windows {
                menuGroup = "MyCalendar"
                menu = true
                shortcut = true
                perUserInstall = false
                // Без стабильного UUID каждый MSI получает новый UpgradeCode и не
                // обновляет прошлую версию, а ставится рядом. Менять нельзя.
                upgradeUuid = "722176b4-b448-4855-b564-a27ebb339e37"
            }
            // Без jdk.localedata в jlink-образе нет русских данных CLDR, и месяцы
            // с днями недели молча печатаются по-английски, хотя в коде локаль ru.
            modules("jdk.localedata")
        }
    }
}
