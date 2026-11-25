import java.util.Properties

plugins {
    id("com.android.application") version "9.5.2"
    id("org.jetbrains.kotlin.android") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
    // Стиль проверяется и приводится командой ktlintFormat по правилам .editorconfig.
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

// Версия движка зафиксирована отдельно: плагин не гарантирует, что между его
// патч-версиями набор правил не поменяется.
ktlint {
    version.set("1.3.1")
}

// Версия приложения — в одном файле app/version.properties. Его же читает
// scripts/release.sh, чтобы проверки и сборка не расходились.
val versionProperties =
    Properties().also { properties ->
        val versionFile = file("version.properties")
        require(versionFile.isFile) {
            "Нет app/version.properties: это единственный источник versionCode и versionName"
        }
        versionFile.inputStream().use(properties::load)
    }
val appVersionCode = versionProperties.getProperty("versionCode").trim().toInt()
val appVersionName = versionProperties.getProperty("versionName").trim()

// Подпись release задаётся только переменными окружения: ключ не лежит в
// репозитории и не попадает в сборку из файла настроек.
// Правило «всё или ничего»: любая заданная переменная требует остальных трёх и
// существующего файла ключа. Иначе опечатка в имени переменной давала бы
// неподписанный APK, который легко принять за релиз.
val signingVariables =
    listOf(
        "ANDROID_KEYSTORE_PATH",
        "ANDROID_KEYSTORE_PASSWORD",
        "ANDROID_KEY_ALIAS",
        "ANDROID_KEY_PASSWORD",
    )
val signingValues = signingVariables.associateWith { providers.environmentVariable(it).orNull }
// Наличие переменной, а не её содержимое: пустое значение из секрета — тоже
// заявка на подпись, и она должна падать, а не собирать неподписанный APK.
val signingRequested = signingValues.values.any { it != null }
val hasReleaseKeystore = signingRequested

if (signingRequested) {
    val missing = signingVariables.filter { signingValues[it].isNullOrBlank() }
    require(missing.isEmpty()) {
        "Подпись release: задана часть переменных, нет $missing"
    }
    val keystorePath = signingValues.getValue("ANDROID_KEYSTORE_PATH")!!
    require(file(keystorePath).isFile) {
        "Подпись release: файл keystore не найден: $keystorePath"
    }
}

android {
    namespace = "ru.chekcalc"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.chekcalc"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Ошибка линтера валит make analyze, предупреждения — нет.
        warningsAsErrors = false
        abortOnError = true
        // Security-детекторы по умолчанию не спрашиваются (#2322).
        enable += listOf("Security")
    }

    // Блок signingConfigs объявлен до buildTypes: ссылка на конфигурацию внутри
    // buildTypes работает только после её создания.
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(signingValues.getValue("ANDROID_KEYSTORE_PATH")!!)
                storePassword = signingValues["ANDROID_KEYSTORE_PASSWORD"]
                keyAlias = signingValues["ANDROID_KEY_ALIAS"]
                keyPassword = signingValues["ANDROID_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
            // R8 выключен: его правила для Compose и kotlinx.serialization нельзя
            // проверить без прогона на устройстве. Это отдельная задача.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
