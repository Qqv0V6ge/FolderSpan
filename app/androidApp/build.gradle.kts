import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.*

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val keystoreProperties = Properties().apply {
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(propertyName: String, environmentName: String): String? {
    val propertyValue = keystoreProperties.getProperty(propertyName)
    val environmentValue = providers.environmentVariable(environmentName).orNull
    return (environmentValue ?: propertyValue)?.takeIf { item -> item.isNotBlank() }
}

val releaseStorePath = releaseSigningValue("storeFile", "ANDROID_STORE_FILE")
val releaseStorePassword = releaseSigningValue("storePassword", "ANDROID_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "ANDROID_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "ANDROID_KEY_PASSWORD")
val releaseVersion = providers.gradleProperty("releaseVersion")
    .map { item -> item.removePrefix("v") }
    .orElse("1.0.0")
    .get()
val androidVersionCode = providers.gradleProperty("androidVersionCode")
    .map { item -> item.toInt() }
    .orElse(1)
    .get()
val androidEnableAbiSplits = providers.gradleProperty("androidEnableAbiSplits")
    .map { item -> item.toBoolean() }
    .orElse(true)
    .get()

require(Regex("""\d+\.\d+\.\d+""").matches(releaseVersion)) {
    "releaseVersion must use MAJOR.MINOR.PATCH format, but was '$releaseVersion'."
}
require(androidVersionCode in 1..2_100_000_000) {
    "androidVersionCode must be between 1 and 2100000000, but was '$androidVersionCode'."
}

val hasReleaseSigning = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { item -> !item.isNullOrBlank() }
val debugStoreFile = project.file("signing/debug.keystore")
val hasDebugSigning = debugStoreFile.exists()

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(projects.app.shared)
    implementation(projects.core)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.material3)
    implementation(libs.koin.android)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.multiplatform.settings)
    implementation(libs.napier)
    implementation(libs.compose.uiToolingPreview)

    debugImplementation(libs.compose.ui.tooling)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Keep shared-module previews available while excluding their tooling from release APKs/AABs.
configurations.matching { it.name == "releaseRuntimeClasspath" }.configureEach {
    exclude(group = "org.jetbrains.compose.ui", module = "ui-tooling")
}

android {
    namespace = "com.folderspan"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        if (hasDebugSigning) {
            create("debugCustom") {
                storeFile = debugStoreFile
                storePassword = "folderspan"
                keyAlias = "folderspan_debug"
                keyPassword = "folderspan"
            }
        }
        if (hasReleaseSigning) {
            create("release") {
                val storePath = checkNotNull(releaseStorePath)
                val resolvedStoreFile = File(storePath).let { item ->
                    if (item.isAbsolute) item else rootProject.file(storePath)
                }
                storeFile = resolvedStoreFile
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
            }
        }
    }

    defaultConfig {
        applicationId = "com.folderspan"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = androidVersionCode
        versionName = releaseVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = androidEnableAbiSplits
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES}"
            excludes += "/META-INF/INDEX.LIST"
            pickFirsts += "/META-INF/LICENSE.md"
        }
    }
    buildTypes {
        getByName("debug") {
            if (hasDebugSigning) {
                signingConfig = signingConfigs.getByName("debugCustom")
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
