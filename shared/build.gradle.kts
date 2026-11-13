import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Общий JVM-код Android- и desktop-клиента: домен, правила напоминаний,
// трёхстороннее слияние, разбор iCalendar, CalDAV-клиент, движок синхронизации
// и контракт доставки напоминаний. UI и конкретные хранилища остаются у клиентов.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // MiniCalDavServer и mock-XML нужны тестам обоих клиентов — держим их фикстурами.
    id("java-test-fixtures")
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
    // Flow и @Serializable встречаются в публичных сигнатурах домена.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // @Inject/@Singleton: домен не знает, кто его собирает — Hilt в Android или руками в desktop.
    api(libs.javax.inject)
    // CalDavClient принимает OkHttpClient в публичной сигнатуре — зависимость видна клиентам.
    api(libs.okhttp)

    testImplementation(libs.junit)
}
