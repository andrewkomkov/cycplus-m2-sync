plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Версию двигает release-please по conventional commits, руками не трогаем.
val appVersion = "0.6.1" // x-release-please-version

/** 1.4.2 -> 10402: монотонный код версии, выводимый из имени. */
fun versionCodeOf(name: String): Int {
    val (major, minor, patch) = name.split(".").map { it.toIntOrNull() ?: 0 }
    return major * 10000 + minor * 100 + patch
}

android {
    namespace = "dev.komkov.m2sync"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.komkov.m2sync"
        minSdk = 33
        targetSdk = 37
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
    }

    // Ключ приходит из секретов CI; локально релиз подписывается отладочным ключом.
    val keystorePath = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Отладочная сборка живёт рядом с релизной, а не поверх неё: ключи
            // разные, и иначе установка требовала бы снести приложение вместе
            // со всеми скачанными .fit.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Без R8 dex раздувается до 50 МБ: FIT SDK и material-icons тянут
            // тысячи неиспользуемых классов.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }

    // UI-тесты идут через Robolectric обычным `test`-таском, без эмулятора,
    // поэтому им нужны настоящие ресурсы приложения.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Расширение kotlin регистрирует сам AGP 9 (встроенный Kotlin), плагин
// org.jetbrains.kotlin.android для этого больше не нужен.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    // M3 Expressive живёт только в 1.5.0-alpha: BOM тянет стабильную 1.4.0,
    // поэтому material3 закрепляем отдельно, поверх BOM.
    implementation("androidx.compose.material3:material3:1.5.0-alpha24")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("com.garmin:fit:21.205.0")

    testImplementation("junit:junit:4.13.2")
    // Настоящий org.json: в unit-тестах андроидный — заглушка.
    testImplementation("org.json:json:20260719")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Манифест с пустой ComponentActivity — только он даёт правилу что запускать,
    // и попасть должен именно в отладочный вариант, а не в тестовый classpath.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
