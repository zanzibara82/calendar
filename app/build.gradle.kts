import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.mycalendar"
    // AndroidX 1.17/1.11 требуют compileSdk >= 36
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mycalendar"
        minSdk = 26          // по требованиям ТЗ
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "com.example.mycalendar.HiltTestRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // ВРЕМЕННО: release подписан debug-ключом, чтобы сборку можно было
        // установить на эмулятор и замерить холодный старт. Для публикации
        // замените на реальный keystore (versionCode уже пора поднять выше 1).
        getByName("debug")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        // MigrationTestHelper читает экспортированные схемы из ассетов теста
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    // Экспорт схемы Room для миграций и тестов
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // Ядро AndroidX + splash screen
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose + Material 3
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Навигация
    implementation(libs.androidx.navigation.compose)

    // Room (локальная БД)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore (настройки: тема и т.д.)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Установка Baseline Profile при первом запуске (Android 8 и ниже + cloud-профили)
    implementation(libs.androidx.profileinstaller)

    // WorkManager (напоминания + фоновая синхронизация)
    implementation(libs.androidx.work.runtime.ktx)

    // Hilt (DI)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Сеть (Яндекс REST). Google идёт через системный календарь — OAuth не нужен,
    // поэтому play-services-auth в проекте больше нет.
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Корутины
    implementation(libs.kotlinx.coroutines.android)

    // Тесты
    testImplementation(libs.junit)
    // XML-pull парсер (org.xmlpull.v1) на classpath JVM-тестов: CalDavClient парсит
    // multistatus через XmlPullParser (на Android это фреймворк, на JVM юнит-тестах
    // его нет — kxml2 даёт совместимую реализацию). Только для тестов, в APK не попадает.
    testImplementation(libs.kxml2)

    // Instrumented-тесты (androidTest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso)
    // MigrationTestHelper сверяет SQL миграции с экспортированными схемами (app/schemas)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
