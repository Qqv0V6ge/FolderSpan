import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinx.serialization)
}

fun iosNativeTargetSuffix(targetName: String): String =
    targetName.replaceFirstChar { it.uppercase() }

fun iosNativeEnvName(prefix: String, targetName: String): String? = when (targetName) {
    "iosArm64" -> "${prefix}_IOS_ARM64"
    "iosSimulatorArm64" -> "${prefix}_IOS_SIMULATOR_ARM64"
    else -> null
}

fun iosNativeRoots(targetName: String): List<File> {
    val suffix = iosNativeTargetSuffix(targetName)
    return listOfNotNull(
        providers.gradleProperty("iosNativeLibsRoot$suffix").orNull,
        iosNativeEnvName("IOS_NATIVE_LIBS_ROOT", targetName)?.let { providers.environmentVariable(it).orNull },
        providers.gradleProperty("iosNativeLibsRoot").orNull,
        providers.environmentVariable("IOS_NATIVE_LIBS_ROOT").orNull,
        providers.gradleProperty("iosNativeXcframeworksRoot$suffix").orNull,
        iosNativeEnvName("IOS_NATIVE_XCFRAMEWORKS_ROOT", targetName)?.let { providers.environmentVariable(it).orNull },
        providers.gradleProperty("iosNativeXcframeworksRoot").orNull,
        providers.environmentVariable("IOS_NATIVE_XCFRAMEWORKS_ROOT").orNull,
        rootProject.file("app/iosApp/Pods/WebRTC-SDK")
            .takeIf { it.exists() }
            ?.absolutePath,
        file("${System.getProperty("user.home")}/ios-native-xcframeworks")
            .takeIf { it.exists() }
            ?.absolutePath,
    )
        .map(::file)
        .filter(File::exists)
        .distinctBy(File::getAbsolutePath)
}

fun iosNativeLinkerOpts(targetName: String): List<String> = iosNativeRoots(targetName)
    .flatMap { root ->
        val xcframeworks = if (root.name.endsWith(".xcframework")) {
            listOf(root)
        } else {
            root.listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith(".xcframework") }
        }
        val rootLibraryOpts = root.resolve("lib")
            .takeIf(File::exists)
            ?.let { listOf("-L${it.absolutePath}") }
            .orEmpty()
        rootLibraryOpts + xcframeworks.mapNotNull { xcframework ->
            val slices = xcframework.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("ios-") }
            val candidates = if (targetName == "iosSimulatorArm64") {
                slices.filter { it.name.contains("simulator") }
            } else {
                slices.filterNot { it.name.contains("simulator") }
            }
            candidates.firstOrNull { it.name.contains("arm64") } ?: candidates.firstOrNull()
        }
            .flatMap { slice ->
                val files = slice.listFiles().orEmpty()
                buildList {
                    if (files.any { it.isFile && (it.extension == "a" || it.extension == "dylib") }) {
                        add("-L${slice.absolutePath}")
                    }
                    if (files.any { it.isDirectory && it.name.endsWith(".framework") }) {
                        add("-F${slice.absolutePath}")
                    }
                }
            }
    }
    .distinct()

fun iosNativeFrameworks(targetName: String): List<File> = iosNativeRoots(targetName)
    .flatMap { root ->
        val xcframeworks = if (root.name.endsWith(".xcframework")) {
            listOf(root)
        } else {
            root.listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith(".xcframework") }
        }
        xcframeworks.mapNotNull { xcframework ->
            val slices = xcframework.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("ios-") }
            val candidates = if (targetName == "iosSimulatorArm64") {
                slices.filter { it.name.contains("simulator") }
            } else {
                slices.filterNot { it.name.contains("simulator") }
            }
            candidates.firstOrNull { it.name.contains("arm64") } ?: candidates.firstOrNull()
        }
            .flatMap { slice ->
                slice.listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith(".framework") }
            }
    }
    .distinctBy(File::getAbsolutePath)

kotlin {
    android {
        namespace = "com.folderspan.pro"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        val nativeLibraryOpts = iosNativeLinkerOpts(iosTarget.name)
        iosTarget.binaries.all {
            linkerOpts(*nativeLibraryOpts.toTypedArray())
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        compilerOptions {
            freeCompilerArgs.add("-Xwasm-use-new-exception-proposal")
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain = getByName("commonMain")
        val commonTest = getByName("commonTest")
        val androidMain = getByName("androidMain")
        val jvmTest = getByName("jvmTest")

        commonMain.kotlin.srcDir("kotlin")

        commonMain.dependencies {
            api(projects.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material3WindowSizeClass)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.image.loader)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.paging.common)
            implementation(libs.androidx.paging.compose)
            implementation(libs.androidx.navigation3.runtime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.multiplatform.settings)
            implementation(libs.korlibs.crypto)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.multiplatform.settings.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.koin.test)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.mock)
            implementation(libs.sqldelight.driver.sqlite)
        }
    }
}

val copyIosSimulatorWebRtcFrameworkForTests =
    tasks.register<Sync>("copyIosSimulatorWebRtcFrameworkForTests") {
        description = "Copies the WebRTC framework into the iOS simulator test bundle."
        val iosSimulatorWebRtcFramework = iosNativeFrameworks("iosSimulatorArm64")
            .firstOrNull { it.name == "WebRTC.framework" }
        enabled = iosSimulatorWebRtcFramework != null
        iosSimulatorWebRtcFramework?.let { framework ->
            from(framework)
            into(layout.buildDirectory.dir("bin/iosSimulatorArm64/debugTest/Frameworks/WebRTC.framework"))
        }
        dependsOn("linkDebugTestIosSimulatorArm64")
    }

tasks.matching { it.name == "iosSimulatorArm64Test" }.configureEach {
    dependsOn(copyIosSimulatorWebRtcFrameworkForTests)
}
