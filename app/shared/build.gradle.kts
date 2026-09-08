import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.composeCompiler)
}

val kotlinVersion = libs.versions.kotlin.get()
val sqlDelightVersion = libs.versions.sqldelight.get()
val includeProMain = findProject(":proMain") != null

fun iosNativeTargetSuffix(targetName: String): String =
    targetName.replaceFirstChar { it.uppercase() }

fun iosNativeEnvName(prefix: String, targetName: String): String? = when (targetName) {
    "iosArm64" -> "${prefix}_IOS_ARM64"
    "iosSimulatorArm64" -> "${prefix}_IOS_SIMULATOR_ARM64"
    else -> null
}

fun composeIosNativeRoots(targetName: String): List<File> {
    val suffix = iosNativeTargetSuffix(targetName)
    val candidates = listOfNotNull(
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
            ?.absolutePath
    )
    return candidates
        .map { file(it) }
        .filter { it.exists() }
        .distinctBy { it.absolutePath }
}

fun composeIosXcframeworks(root: File): List<File> {
    return if (root.name.endsWith(".xcframework")) {
        listOf(root)
    } else {
        root.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".xcframework") }
            ?: emptyList()
    }
}

fun composeIosXcframeworkSlice(xcframework: File, targetName: String): File? {
    val slices = xcframework.listFiles()?.filter { it.isDirectory } ?: return null
    val iosSlices = slices.filter { it.name.startsWith("ios-") }
    val targetSlices = if (targetName == "iosSimulatorArm64") {
        iosSlices.filter { it.name.contains("simulator") }
    } else {
        iosSlices.filterNot { it.name.contains("simulator") }
    }
    return targetSlices.firstOrNull { it.name.contains("arm64") } ?: targetSlices.firstOrNull()
}

fun composeIosNativeLinkerOpts(targetName: String): List<String> {
    val linkerOpts = mutableListOf<String>()
    composeIosNativeRoots(targetName).forEach { root ->
        root.resolve("lib")
            .takeIf { it.exists() }
            ?.let { linkerOpts.add("-L${it.absolutePath}") }

        composeIosXcframeworks(root)
            .mapNotNull { composeIosXcframeworkSlice(it, targetName) }
            .forEach { slice ->
                val files = slice.listFiles().orEmpty()
                if (files.any { it.isFile && (it.extension == "a" || it.extension == "dylib") }) {
                    linkerOpts.add("-L${slice.absolutePath}")
                }
                if (files.any { it.isDirectory && it.name.endsWith(".framework") }) {
                    linkerOpts.add("-F${slice.absolutePath}")
                }
            }
    }
    return linkerOpts.distinct()
}

fun composeIosNativeFrameworks(targetName: String): List<File> {
    return composeIosNativeRoots(targetName)
        .flatMap { root -> composeIosXcframeworks(root) }
        .mapNotNull { xcframework -> composeIosXcframeworkSlice(xcframework, targetName) }
        .flatMap { slice ->
            slice.listFiles()
                ?.filter { it.isDirectory && it.name.endsWith(".framework") }
                ?: emptyList()
        }
        .distinctBy { it.absolutePath }
}

configurations.all {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-test:$kotlinVersion",
            "org.jetbrains.kotlin:kotlin-test-common:$kotlinVersion",
            "org.jetbrains.kotlin:kotlin-test-annotations-common:$kotlinVersion",
            "org.jetbrains.kotlin:kotlin-test-js:$kotlinVersion"
        )
    }
}

kotlin {
    android {
        namespace = "com.folderspan.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        val nativeLinkerOpts = composeIosNativeLinkerOpts(iosTarget.name)
        iosTarget.binaries.all {
            linkerOpts(
                *(
                    listOf("-lsqlite3", "-lcurl", "-lssh2", "-lsmb2", "-lssl", "-lcrypto", "-lz") +
                        nativeLinkerOpts
                    ).toTypedArray()
            )
        }
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            export(libs.kmpnotifier.core)
            export(libs.kmpnotifier.local)
            freeCompilerArgs += listOf(
                "-Xbinary=bundleId=com.folderspan",
                "-Xdisable-phases=DevirtualizationAnalysisPhase",
            )
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
        compilerOptions {
            freeCompilerArgs.add("-Xwasm-use-new-exception-proposal")
        }
    }

    applyDefaultHierarchyTemplate()

    if (!includeProMain) {
        sourceSets.configureEach {
            // Pro 文件留在原目录；关闭模块时排除接入代码，并使用本地功能入口。
            kotlin.exclude(
                "**/ProIntegration.kt",
                "**/ui/components/avatar/**",
                "**/notification/AccountNotification*.kt",
                "**/notification/AppNotificationRouteRegistry.kt",
                "**/ui/components/dialog/FeedbackAttachmentPicker*.kt",
                "**/ui/components/filter/FeedbackTicketFilterSheetProvider*.kt",
                "**/ui/components/notification/NotificationMarkdownContent*.kt",
                "**/ui/screen/main/UnifiedNotificationScreen.kt",
                "**/ui/screen/settings/AboutSoftwareScreen*.kt",
                "**/ui/screen/settings/AppUpdateHistoryScreen*.kt",
                "**/AppProAuthSessionInitializationTest.kt",
                "**/ui/components/drawer/AppDrawerAccountHeaderTest.kt",
                "**/ui/components/drawer/AppDrawerMenuTest.kt",
                "**/ui/components/drawer/AppDrawerSignInPromptTest.kt",
                "**/ui/screen/main/AccountNotificationActionRowTest.kt",
                "**/ui/screen/main/NotificationPageStateTest.kt",
                "**/ui/screen/main/NotificationRowColorsTest.kt",
                "**/ui/screen/main/NotificationScreenNavigationTest.kt",
            )
            kotlin.srcDir("src/noPro/$name/kotlin")
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain")
        val commonTest = getByName("commonTest")
        val androidMain = getByName("androidMain")
        val iosMain = getByName("iosMain")
        val jsMain = getByName("jsMain")
        val jvmMain = getByName("jvmMain")
        val jvmTest = getByName("jvmTest")
        val wasmJsMain = getByName("wasmJsMain")

        commonMain.dependencies {
            api(projects.core)
            if (includeProMain) {
                api(project(":proMain"))
            }
            api(libs.kmpnotifier.core)
            api(libs.kmpnotifier.local)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.androidx.navigation3.ui)
            implementation(libs.androidx.navigationevent.compose)
            implementation(libs.koin.compose)
            implementation(libs.koin.core)
            implementation(libs.napier)
            implementation(libs.multiplatform.settings)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.qrcoder)
            implementation(libs.compose.image.loader)
            implementation(libs.reorderable)
            implementation(libs.sqldelight.driver.adapters)
            implementation(libs.korlibs.io)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.sqldelight.driver.android)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.driver.sqlite)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
        jsMain.dependencies {
            implementation(libs.sqldelight.driver.js)
            implementation(npm("@cashapp/sqldelight-sqljs-worker", sqlDelightVersion))
            implementation(npm("sql.js", "1.8.0"))
            implementation(npm("crypto-js", "4.2.0"))
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.driver.native)
        }
        wasmJsMain.dependencies {
            implementation(libs.sqldelight.driver.js)
            implementation(npm("@cashapp/sqldelight-sqljs-worker", sqlDelightVersion))
            implementation(npm("sql.js", "1.8.0"))
            implementation(npm("crypto-js", "4.2.0"))
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

compose.resources {
    packageOfResClass = "com.folderspan.composeapp.generated.resources"
    publicResClass = true
}

val iosSimulatorWebRtcFramework = composeIosNativeFrameworks("iosSimulatorArm64")
    .firstOrNull { it.name == "WebRTC.framework" }

val copyIosSimulatorWebRtcFrameworkForTests =
    tasks.register<Sync>("copyIosSimulatorWebRtcFrameworkForTests") {
        description = "Copies the WebRTC framework into the iOS simulator test bundle."
        onlyIf { iosSimulatorWebRtcFramework != null }
        iosSimulatorWebRtcFramework?.let { framework ->
            from(framework)
            into(layout.buildDirectory.dir("bin/iosSimulatorArm64/debugTest/Frameworks/WebRTC.framework"))
        }
        dependsOn("linkDebugTestIosSimulatorArm64")
    }

tasks.matching { it.name == "iosSimulatorArm64Test" }.configureEach {
    dependsOn(copyIosSimulatorWebRtcFrameworkForTests)
}
