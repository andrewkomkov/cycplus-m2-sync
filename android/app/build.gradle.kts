plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Версию двигает release-please по conventional commits, руками не трогаем.
val appVersion = "0.2.0" // x-release-please-version

/** 1.4.2 -> 10402: монотонный код версии, выводимый из имени. */
fun versionCodeOf(name: String): Int {
    val (major, minor, patch) = name.split(".").map { it.toIntOrNull() ?: 0 }
    return major * 10000 + minor * 100 + patch
}

android {
    namespace = "dev.komkov.m2sync"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.komkov.m2sync"
        minSdk = 33
        targetSdk = 36
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("com.garmin:fit:21.171.0")
}
