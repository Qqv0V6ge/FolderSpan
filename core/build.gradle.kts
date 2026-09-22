import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.libres)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.build.config)
}

val appReleaseVersion = providers.gradleProperty("releaseVersion")
    .map { item -> item.removePrefix("v") }
    .orElse("1.0.0")
val debugGatewayBaseUrl = "http://10.0.0.122"
val releaseGatewayBaseUrl = "https://api.folderspan.com"
val debugHttpProxyUrl = "http://10.0.0.122:8080"
val releaseHttpProxyUrl = ""
val debugApiHostHeader = "127.0.0.1:8888"
val releaseApiHostHeader = "api.folderspan.com"
val requestedTasks = gradle.startParameter.taskNames.map(String::lowercase)
val inferredBuildType =
    if (requestedTasks.any { task -> task.contains("release") || task.contains("production") }) {
        "release"
    } else {
        "debug"
    }
val appBuildType = providers.gradleProperty("folderspanBuildType")
    .orElse(inferredBuildType)
    .get()
    .trim()
    .lowercase()
require(appBuildType == "debug" || appBuildType == "release") {
    "folderspanBuildType must be 'debug' or 'release', but was '$appBuildType'."
}
val gatewayBaseUrl = if (appBuildType == "release") releaseGatewayBaseUrl else debugGatewayBaseUrl
val httpProxyUrl = if (appBuildType == "release") releaseHttpProxyUrl else debugHttpProxyUrl
val apiHostHeader = if (appBuildType == "release") releaseApiHostHeader else debugApiHostHeader

require(Regex("""^\d+\.\d+\.\d+$""").matches(appReleaseVersion.get())) {
    "releaseVersion must use MAJOR.MINOR.PATCH format, but was '${appReleaseVersion.get()}'."
}

buildConfig {
    packageName("com.folderspan")
    className("AppBuildConfig")
    useKotlinOutput {
        internalVisibility = false
    }
    buildConfigField("APP_VERSION", appReleaseVersion)
    buildConfigField("BUILD_TYPE", appBuildType)
    buildConfigField("GATEWAY_BASE_URL", gatewayBaseUrl)
    buildConfigField("HTTP_PROXY_URL", httpProxyUrl)
    buildConfigField("API_HOST_HEADER", apiHostHeader)
}

val kotlinVersion = libs.versions.kotlin.get()
val sqlDelightVersion = libs.versions.sqldelight.get()
val webrtcJavaVersion = libs.versions.webrtc.java.get()

data class IosNativeOpts(
    val compilerOpts: List<String>,
    val linkerOpts: List<String>,
    val frameworkNames: List<String>
)

data class NativeHeaderRequirement(
    val name: String,
    val headers: List<String>
)

val iosHeaderRequirements = listOf(
    NativeHeaderRequirement("libcurl", listOf("curl/curl.h")),
    NativeHeaderRequirement("libssh2", listOf("libssh2.h", "libssh2_sftp.h")),
    NativeHeaderRequirement("libsmb2", listOf("smb2/smb2.h", "smb2/libsmb2.h")),
    NativeHeaderRequirement("openssl", listOf("openssl/ssl.h", "openssl/x509.h"))
)

val iosNativeLibsRootDefault: String? = (findProperty("iosNativeLibsRoot") as String?)
    ?: System.getenv("IOS_NATIVE_LIBS_ROOT")

val iosNativeXcframeworksRootDefault = (findProperty("iosNativeXcframeworksRoot") as String?)
    ?: System.getenv("IOS_NATIVE_XCFRAMEWORKS_ROOT")
    ?: file("${System.getProperty("user.home")}/ios-native-xcframeworks")
        .takeIf { it.exists() }
        ?.absolutePath

fun resolveIosNativeLibsRoot(targetName: String): String? {
    val propName = "iosNativeLibsRoot" + targetName.replaceFirstChar { it.uppercase() }
    val envName = when (targetName) {
        "iosArm64" -> "IOS_NATIVE_LIBS_ROOT_IOS_ARM64"
        "iosSimulatorArm64" -> "IOS_NATIVE_LIBS_ROOT_IOS_SIMULATOR_ARM64"
        else -> null
    }
    return (findProperty(propName) as String?)
        ?: envName?.let { System.getenv(it) }
        ?: iosNativeLibsRootDefault
}

fun resolveIosNativeXcframeworksRoot(targetName: String): String? {
    val propName = "iosNativeXcframeworksRoot" + targetName.replaceFirstChar { it.uppercase() }
    val envName = when (targetName) {
        "iosArm64" -> "IOS_NATIVE_XCFRAMEWORKS_ROOT_IOS_ARM64"
        "iosSimulatorArm64" -> "IOS_NATIVE_XCFRAMEWORKS_ROOT_IOS_SIMULATOR_ARM64"
        else -> null
    }
    return (findProperty(propName) as String?)
        ?: envName?.let { System.getenv(it) }
        ?: iosNativeXcframeworksRootDefault
}

fun podInstalledXcframeworkRoots(): List<File> {
    val podsRootFromEnvironment = System.getenv("PODS_ROOT")?.let(::file)
    val repoPodsRoot = rootProject.file("app/iosApp/Pods").takeIf { it.exists() }
    return listOfNotNull(
        podsRootFromEnvironment?.resolve("WebRTC-SDK"),
        repoPodsRoot?.resolve("WebRTC-SDK")
    ).filter { it.exists() }
        .distinctBy { it.absolutePath }
}

fun iosNativeXcframeworkRoots(targetName: String): List<File> {
    val roots = mutableListOf<String?>()
    val xcRoot = resolveIosNativeXcframeworksRoot(targetName)
    if (xcRoot != null) roots.add(xcRoot)
    val fallbackRoot = resolveIosNativeLibsRoot(targetName)
    if (fallbackRoot != null && fallbackRoot != xcRoot) roots.add(fallbackRoot)
    return (
        roots.filterNotNull()
            .map { file(it) }
            .filter { it.exists() } +
            podInstalledXcframeworkRoots()
        ).distinctBy { it.absolutePath }
}

fun collectXcframeworks(root: File): List<File> {
    return if (root.name.endsWith(".xcframework")) {
        listOf(root)
    } else {
        root.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".xcframework") }
            ?: emptyList()
    }
}

fun selectXcframeworkSlice(xcframework: File, targetName: String): File? {
    val slices = xcframework.listFiles()?.filter { it.isDirectory } ?: return null
    val iosSlices = slices.filter { it.name.startsWith("ios-") }
    val simulatorSlices = iosSlices.filter { it.name.contains("simulator") }
    val deviceSlices = iosSlices.filterNot { it.name.contains("simulator") }
    val targetSlices = if (targetName == "iosSimulatorArm64") simulatorSlices else deviceSlices
    if (targetSlices.isEmpty()) return null
    return targetSlices.firstOrNull { it.name.contains("arm64") } ?: targetSlices.first()
}

fun xcframeworkSliceDirs(targetName: String): List<File> {
    val rootFiles = iosNativeXcframeworkRoots(targetName)
    val xcframeworks = rootFiles.flatMap { collectXcframeworks(it) }
    return xcframeworks.mapNotNull { selectXcframeworkSlice(it, targetName) }
}

fun iosNativeFrameworksFor(targetName: String): List<File> {
    return xcframeworkSliceDirs(targetName)
        .flatMap { slice ->
            slice.listFiles()
                ?.filter { it.isDirectory && it.name.endsWith(".framework") }
                ?: emptyList()
        }
        .distinctBy { it.absolutePath }
}

fun iosNativeOptsFor(targetName: String): IosNativeOpts {
    val root = resolveIosNativeLibsRoot(targetName)
    val includeDir = root?.let { file("$it/include") }
    val libDir = root?.let { file("$it/lib") }
    val compilerOpts = mutableListOf<String>()
    val linkerOpts = mutableListOf<String>()
    val frameworkNames = mutableSetOf<String>()

    includeDir?.takeIf { it.exists() }?.let { compilerOpts.add("-I${it.absolutePath}") }
    libDir?.takeIf { it.exists() }?.let { linkerOpts.add("-L${it.absolutePath}") }

    val sliceDirs = xcframeworkSliceDirs(targetName)
    sliceDirs.forEach { slice ->
        val headerCandidates = listOf(
            slice.resolve("Headers"),
            slice.resolve("headers"),
            slice.resolve("include"),
            slice.resolve("Include")
        )
        headerCandidates.filter { it.exists() }.forEach { headersDir ->
            compilerOpts.add("-I${headersDir.absolutePath}")
        }
        val containsStaticLib = slice.listFiles()?.any { it.isFile && (it.extension == "a" || it.extension == "dylib") } == true
        if (containsStaticLib) {
            linkerOpts.add("-L${slice.absolutePath}")
        }
        val frameworks = slice.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".framework") }
            ?: emptyList()
        if (frameworks.isNotEmpty()) {
            linkerOpts.add("-F${slice.absolutePath}")
            frameworks.forEach { framework ->
                frameworkNames.add(framework.nameWithoutExtension)
                val fwHeaders = framework.resolve("Headers")
                if (fwHeaders.exists()) {
                    compilerOpts.add("-I${fwHeaders.absolutePath}")
                }
            }
        }
    }
    if (root != null && (compilerOpts.isEmpty() || linkerOpts.isEmpty())) {
        logger.warn(
            "iosNativeLibsRoot is set to '$root' for target '$targetName' but 'include/' or 'lib/' " +
                "was not found. Expected headers (libssh2/libsmb2) under include/ and libs under lib/."
        )
    }
    return IosNativeOpts(
        compilerOpts = compilerOpts.distinct(),
        linkerOpts = linkerOpts.distinct(),
        frameworkNames = frameworkNames.toList()
    )
}

fun includeDirsFromCompilerOpts(compilerOpts: List<String>): List<File> {
    return compilerOpts
        .filter { it.startsWith("-I") }
        .mapNotNull { opt ->
            val path = opt.removePrefix("-I").trim()
            path.takeIf { it.isNotEmpty() }
        }
        .map { file(it) }
        .filter { it.exists() }
}

fun resolveMacSdkIncludeDir(): File? {
    val sdkRoots = listOf(
        file("/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs"),
        file("/Library/Developer/CommandLineTools/SDKs")
    )
    return sdkRoots
        .asSequence()
        .filter { it.exists() && it.isDirectory }
        .flatMap { sdkRoot ->
            sdkRoot.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && it.name.startsWith("MacOSX") && it.name.endsWith(".sdk") }
                ?: emptySequence()
        }.firstNotNullOfOrNull { sdkDir ->
            sdkDir.resolve("usr/include").takeIf { it.exists() }
        }
}

fun fallbackIosInteropIncludeDirs(): List<File> {
    val fallbackDirs = mutableListOf(
        file("/opt/homebrew/include"),
        file("/usr/local/include")
    )
    resolveMacSdkIncludeDir()?.let { fallbackDirs.add(it) }
    return fallbackDirs
        .filter { it.exists() }
        .distinctBy { it.absolutePath }
}

fun missingHeaders(includeDirs: List<File>, headers: List<String>): List<String> {
    return headers.filter { header ->
        includeDirs.none { includeDir -> includeDir.resolve(header).exists() }
    }
}

fun iosNativeLibsRootPropertyFor(targetName: String): String =
    "iosNativeLibsRoot" + targetName.replaceFirstChar { it.uppercase() }

fun iosNativeXcframeworksPropertyFor(targetName: String): String =
    "iosNativeXcframeworksRoot" + targetName.replaceFirstChar { it.uppercase() }

fun iosNativeLibsRootEnvFor(targetName: String): String? = when (targetName) {
    "iosArm64" -> "IOS_NATIVE_LIBS_ROOT_IOS_ARM64"
    "iosSimulatorArm64" -> "IOS_NATIVE_LIBS_ROOT_IOS_SIMULATOR_ARM64"
    else -> null
}

fun iosNativeXcframeworksEnvFor(targetName: String): String? = when (targetName) {
    "iosArm64" -> "IOS_NATIVE_XCFRAMEWORKS_ROOT_IOS_ARM64"
    "iosSimulatorArm64" -> "IOS_NATIVE_XCFRAMEWORKS_ROOT_IOS_SIMULATOR_ARM64"
    else -> null
}

fun taskNamesLowercase(): List<String> = gradle.startParameter.taskNames.map { it.lowercase() }

fun isMacOsHost(): Boolean = System.getProperty("os.name").lowercase().contains("mac")

fun shouldValidateIosNativeDeps(taskNames: List<String>): Boolean {
    return taskNames.isNotEmpty() && taskNames.any { taskName ->
        taskName.contains("ios") ||
                taskName.contains("xcode") ||
                taskName.contains("appleframework")
    }
}

fun requestedIosTargets(taskNames: List<String>): Set<String> {
    val targets = mutableSetOf<String>()
    taskNames.forEach { taskName ->
        when {
            taskName.contains("iossimulatorarm64") -> targets.add("iosSimulatorArm64")
            taskName.contains("iosarm64") -> targets.add("iosArm64")
        }
    }
    return targets
}

fun requestedCinteropLibs(taskNames: List<String>): Set<String> {
    val libs = mutableSetOf<String>()
    taskNames.forEach { taskName ->
        when {
            taskName.contains("cinteroplibcurl") -> libs.add("libcurl")
            taskName.contains("cinteroplibssh2") -> libs.add("libssh2")
            taskName.contains("cinteroplibsmb2") -> libs.add("libsmb2")
        }
    }
    return libs
}

fun webrtcJvmClassifier(): String? {
    val osName = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        osName.contains("mac") -> {
            if (arch.contains("aarch64") || arch.contains("arm64")) "macos-aarch64" else "macos-x86_64"
        }
        osName.contains("linux") -> {
            when {
                arch.contains("aarch64") || arch.contains("arm64") -> "linux-aarch64"
                arch.contains("arm") -> "linux-aarch32"
                arch.contains("x86_64") || arch.contains("amd64") -> "linux-x86_64"
                else -> null
            }
        }
        osName.contains("windows") -> {
            if (arch.contains("x86_64") || arch.contains("amd64")) "windows-x86_64" else null
        }
        else -> null
    }
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
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.folderspan.core"
        testNamespace = providers.gradleProperty("deviceTestApplicationId").orNull
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTest {
            applicationId = providers.gradleProperty("deviceTestApplicationId").orNull
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            execution = "HOST"
        }
        packaging.resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES}"
            excludes += "/META-INF/INDEX.LIST"
            pickFirsts += "/META-INF/LICENSE.md"
        }
    }

    val iosArm64Target = iosArm64()
    val iosSimulatorArm64Target = iosSimulatorArm64()

    val taskNamesLowercase = taskNamesLowercase()
    val validateIosNativeDeps = isMacOsHost() && shouldValidateIosNativeDeps(taskNamesLowercase)
    val requestedTargets = requestedIosTargets(taskNamesLowercase)
    val requestedLibs = requestedCinteropLibs(taskNamesLowercase)

    listOf(iosArm64Target, iosSimulatorArm64Target).forEach { iosTarget ->
        val nativeOpts = iosNativeOptsFor(iosTarget.name)
        val nativeCompilerOpts = nativeOpts.compilerOpts
        val nativeLinkerOpts = nativeOpts.linkerOpts
        val frameworkNames = nativeOpts.frameworkNames
        val fallbackIncludeDirs = fallbackIosInteropIncludeDirs()
        val fallbackCompilerOpts = fallbackIncludeDirs.map { "-I${it.absolutePath}" }
        val allInteropIncludeDirs = (
            includeDirsFromCompilerOpts(nativeCompilerOpts) +
                fallbackIncludeDirs
            ).distinctBy { it.absolutePath }

        val shouldValidateTarget =
            validateIosNativeDeps && (requestedTargets.isEmpty() || requestedTargets.contains(iosTarget.name))

        if (shouldValidateTarget) {
            val requirementsToValidate = if (requestedLibs.isEmpty()) {
                iosHeaderRequirements
            } else {
                iosHeaderRequirements.filter { requestedLibs.contains(it.name) }
            }

            val missingByLib = requirementsToValidate.mapNotNull { requirement ->
                val missing = missingHeaders(allInteropIncludeDirs, requirement.headers)
                if (missing.isEmpty()) {
                    null
                } else {
                    "${requirement.name}: ${missing.joinToString(", ")}"
                }
            }
            if (missingByLib.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("Missing iOS native headers for target '${iosTarget.name}'.")
                        appendLine("Unresolved headers: ${missingByLib.joinToString(" | ")}")
                        appendLine("Please provide iOS native dependencies via one of:")
                        appendLine("- -P${iosNativeLibsRootPropertyFor(iosTarget.name)}=/path/to/root")
                        iosNativeLibsRootEnvFor(iosTarget.name)?.let { envName ->
                            appendLine("- $envName=/path/to/root")
                        }
                        appendLine("- -P${iosNativeXcframeworksPropertyFor(iosTarget.name)}=/path/to/xcframeworks")
                        iosNativeXcframeworksEnvFor(iosTarget.name)?.let { envName ->
                            appendLine("- $envName=/path/to/xcframeworks")
                        }
                        appendLine()
                        appendLine("Expected layout for root mode: include/(headers) and lib/(*.a/*.dylib).")
                        appendLine("Detected include search dirs:")
                        allInteropIncludeDirs.forEach { dir -> appendLine("  - ${dir.absolutePath}") }
                    }
                )
            }
            if (!frameworkNames.contains("WebRTC")) {
                val xcframeworkRoots = iosNativeXcframeworkRoots(iosTarget.name)
                throw GradleException(
                    buildString {
                        appendLine("Missing iOS WebRTC.xcframework for target '${iosTarget.name}'.")
                        appendLine("Install the CocoaPods dependency first:")
                        appendLine("  cd app/iosApp && pod install")
                        appendLine("Or provide the XCFramework via one of:")
                        appendLine("- -P${iosNativeXcframeworksPropertyFor(iosTarget.name)}=/path/to/xcframeworks")
                        iosNativeXcframeworksEnvFor(iosTarget.name)?.let { envName ->
                            appendLine("- $envName=/path/to/xcframeworks")
                        }
                        appendLine("- -PiosNativeXcframeworksRoot=/path/to/xcframeworks")
                        appendLine("- IOS_NATIVE_XCFRAMEWORKS_ROOT=/path/to/xcframeworks")
                        appendLine()
                        appendLine("Detected xcframework search roots:")
                        if (xcframeworkRoots.isEmpty()) {
                            appendLine("  - none")
                        } else {
                            xcframeworkRoots.forEach { root -> appendLine("  - ${root.absolutePath}") }
                        }
                    }
                )
            }
        }

        iosTarget.binaries.all {
            val baseLinkerOpts = mutableListOf(
                "-lsqlite3",
                "-lcurl",
                "-lssh2",
                "-lsmb2",
                "-lssl",
                "-lcrypto",
                "-lz"
            )
            val frameworkToLibFlag = mapOf(
                "libcurl" to "-lcurl",
                "libssh2" to "-lssh2",
                "libsmb2" to "-lsmb2"
            )
            frameworkNames.forEach { framework ->
                frameworkToLibFlag[framework]?.let { baseLinkerOpts.remove(it) }
            }
            val frameworkLinkerOpts = frameworkNames.flatMap { listOf("-framework", it) }
            linkerOpts(*(baseLinkerOpts + frameworkLinkerOpts + nativeLinkerOpts).toTypedArray())
        }
        iosTarget.compilations.getByName("main").cinterops {
            val base = project.file("src/iosMain/cinterop")
            create("libcurl") {
                defFile(base.resolve("libcurl.def"))
                if (nativeCompilerOpts.isNotEmpty()) compilerOpts(*nativeCompilerOpts.toTypedArray())
                if (fallbackCompilerOpts.isNotEmpty()) compilerOpts(*fallbackCompilerOpts.toTypedArray())
                if (nativeLinkerOpts.isNotEmpty()) linkerOpts(*nativeLinkerOpts.toTypedArray())
            }
            create("libssh2") {
                defFile(base.resolve("libssh2.def"))
                if (nativeCompilerOpts.isNotEmpty()) compilerOpts(*nativeCompilerOpts.toTypedArray())
                if (fallbackCompilerOpts.isNotEmpty()) compilerOpts(*fallbackCompilerOpts.toTypedArray())
                if (nativeLinkerOpts.isNotEmpty()) linkerOpts(*nativeLinkerOpts.toTypedArray())
            }
            create("libsmb2") {
                defFile(base.resolve("libsmb2.def"))
                compilerOpts("-I${base.absolutePath}")
                if (nativeCompilerOpts.isNotEmpty()) compilerOpts(*nativeCompilerOpts.toTypedArray())
                if (fallbackCompilerOpts.isNotEmpty()) compilerOpts(*fallbackCompilerOpts.toTypedArray())
                if (nativeLinkerOpts.isNotEmpty()) linkerOpts(*nativeLinkerOpts.toTypedArray())
            }
            create("openssl") {
                defFile(base.resolve("openssl.def"))
                compilerOpts("-I${base.absolutePath}")
                if (nativeCompilerOpts.isNotEmpty()) compilerOpts(*nativeCompilerOpts.toTypedArray())
                if (fallbackCompilerOpts.isNotEmpty()) compilerOpts(*fallbackCompilerOpts.toTypedArray())
                if (nativeLinkerOpts.isNotEmpty()) linkerOpts(*nativeLinkerOpts.toTypedArray())
            }
            create("foundationtls") {
                defFile(base.resolve("foundationtls.def"))
                compilerOpts("-I${base.absolutePath}")
            }
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    js {
        browser {
            testTask {
                useKarma { useChromeHeadless() }
            }
        }
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                useKarma { useChromeHeadless() }
            }
        }
        binaries.executable()
        compilerOptions {
            freeCompilerArgs.add("-Xwasm-use-new-exception-proposal")
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        getByName("jvmTest").kotlin.srcDir("src/udpSocketTest/kotlin")
        getByName("androidDeviceTest").kotlin.srcDir("src/udpSocketTest/kotlin")
        // The two interop endpoints must run together; keep them out of standalone suites.
        if (providers.gradleProperty("webrtcInterop").isPresent) {
            getByName("jvmTest").kotlin.srcDirs("src/webrtcInteropCommonTest/kotlin", "src/webrtcInteropJvmTest/kotlin")
            getByName("jsTest").kotlin.srcDirs("src/webrtcInteropCommonTest/kotlin", "src/webrtcInteropJsTest/kotlin")
        }
        val commonMain = getByName("commonMain")
        val androidMain = getByName("androidMain")
        val jvmMain = getByName("jvmMain")
        val jsMain = getByName("jsMain")
        val wasmJsMain = getByName("wasmJsMain")
        val iosMain = getByName("iosMain")

        val serverRouteMain = maybeCreate("serverRouteMain").apply {
            dependsOn(commonMain)
        }
        val nonJvmMain = maybeCreate("nonJvmMain").apply {
            dependsOn(commonMain)
        }
        val kmpWebRtcMain = maybeCreate("kmpWebRtcMain").apply {
            dependsOn(nonJvmMain)
        }
        val fileStagingMain = maybeCreate("fileStagingMain").apply {
            dependsOn(commonMain)
        }
        val zstdMain = maybeCreate("zstdMain").apply {
            dependsOn(commonMain)
        }
        val udpSocketMain = maybeCreate("udpSocketMain").apply {
            dependsOn(commonMain)
        }
        val skiaMain = maybeCreate("skiaMain").apply {
            dependsOn(commonMain)
        }

        androidMain.dependsOn(nonJvmMain)
        androidMain.dependsOn(serverRouteMain)
        androidMain.dependsOn(fileStagingMain)
        androidMain.dependsOn(zstdMain)
        androidMain.dependsOn(udpSocketMain)
        jvmMain.dependsOn(skiaMain)
        jvmMain.dependsOn(serverRouteMain)
        jvmMain.dependsOn(fileStagingMain)
        jvmMain.dependsOn(zstdMain)
        jvmMain.dependsOn(udpSocketMain)
        iosMain.dependsOn(skiaMain)
        iosMain.dependsOn(kmpWebRtcMain)
        iosMain.dependsOn(serverRouteMain)
        iosMain.dependsOn(fileStagingMain)
        iosMain.dependsOn(zstdMain)
        jsMain.dependsOn(skiaMain)
        jsMain.dependsOn(kmpWebRtcMain)
        wasmJsMain.dependsOn(skiaMain)
        wasmJsMain.dependsOn(kmpWebRtcMain)

        commonMain.dependencies {
            // put your Multiplatform dependencies here
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material3WindowSizeClass)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.navigation3.runtime)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.paging.common)
            implementation(libs.androidx.paging.compose)


            implementation(libs.compose.image.loader)
            implementation(libs.napier)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.html)
            implementation(libs.multiplatform.settings)
            implementation(libs.korlibs.crypto)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.sqldelight.driver.adapters)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.auth)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.serialization.kotlinx.protobuf)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.http)

            implementation(libs.qrcoder)


            implementation(libs.compose.settings.ui)
            implementation(libs.compose.settings.ui.extended)
            implementation(libs.reorderable)
            implementation(libs.material.kolor)
            implementation(libs.kmpnotifier.local)

            implementation(libs.okio)

        }
        nonJvmMain.dependencies {
            implementation(libs.webrtc.kmp)
        }
        zstdMain.dependencies {
            implementation(libs.zstd.kmp)
            implementation(libs.zstd.kmp.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.multiplatform.settings.test)
        }
        getByName("androidDeviceTest").apply {
            dependsOn(getByName("commonTest"))
            dependencies {
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.androidx.test.runner)
            }
        }
        androidMain.dependencies {
            implementation("${libs.jna.get()}@aar")
            implementation(libs.commons.net)
            implementation(libs.apache.sshd.core)
            implementation(libs.apache.sshd.sftp)
            implementation(libs.bouncycastle.bcprov)
            implementation(libs.bouncycastle.bcpkix)
            implementation(libs.smbj)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.material3)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.cio)
            implementation(libs.tink.android)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.napier)
            implementation(libs.sqldelight.driver.adapters)
            implementation(libs.sqldelight.driver.android)
            implementation(libs.shizuku.api)
            implementation(libs.shizuku.provider)
            implementation(libs.libsu.core)
            implementation(libs.libsu.service)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.darwin)
            implementation(libs.napier)
            implementation(libs.sqldelight.driver.adapters)
            implementation(libs.sqldelight.driver.native)
        }
        jsMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.js)
            implementation(libs.napier)
            implementation(libs.sqldelight.driver.adapters)
            implementation(libs.sqldelight.driver.js)
            implementation(npm("@cashapp/sqldelight-sqljs-worker", sqlDelightVersion))
            implementation(npm("crypto-js", "4.2.0"))
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(npm("@cashapp/sqldelight-sqljs-worker", sqlDelightVersion))
            implementation(npm("crypto-js", "4.2.0"))
        }
        jvmMain.dependencies {
            implementation(libs.jna)
            implementation(libs.commons.net)
            implementation(libs.apache.sshd.core)
            implementation(libs.apache.sshd.sftp)
            implementation(libs.bouncycastle.bcprov)
            implementation(libs.bouncycastle.bcpkix)
            implementation(libs.smbj)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.java)
            implementation(libs.napier)
            implementation(libs.sqldelight.driver.adapters)
            implementation(libs.sqldelight.driver.sqlite)
            implementation(libs.nucleus.notification.common)
            // Select the native classifier explicitly; Windows ARM64 has no upstream JNI artifact.
            implementation("dev.onvoid.webrtc:webrtc-java:$webrtcJavaVersion") {
                isTransitive = false
            }
            webrtcJvmClassifier()?.let { classifier ->
                implementation("dev.onvoid.webrtc:webrtc-java:$webrtcJavaVersion:$classifier@jar")
            }
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.koin.test)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.mock)
        }
    }
}

val iosSimulatorWebRtcFramework = iosNativeFrameworksFor("iosSimulatorArm64")
    .firstOrNull { it.name == "WebRTC.framework" }

val copyIosSimulatorWebRtcFrameworkForTests =
    tasks.register<Sync>("copyIosSimulatorWebRtcFrameworkForTests") {
        description = "Copies the WebRTC framework into the iOS simulator test bundle."
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

compose.resources {
    packageOfResClass = "com.folderspan.shared.generated.resources"
}

libres {
    generatedClassName = "App"
    generateNamedArguments = true
    baseLocaleLanguageCode = "en"
}

val validateAppStringCatalogs = tasks.register("validateAppStringCatalogs") {
    group = "verification"
    description = "Validates that English and Simplified Chinese string catalogs have matching keys and arguments."

    val englishCatalog = layout.projectDirectory.file("src/commonMain/libres/strings/app_en.xml")
    val simplifiedChineseCatalog = layout.projectDirectory.file("src/commonMain/libres/strings/app_zhHans.xml")
    inputs.files(englishCatalog, simplifiedChineseCatalog)

    doLast {
        val stringPattern = Regex("""<string\s+name="([^"]+)"[^>]*>([\s\S]*?)</string>""")
        val argumentPattern = Regex("""\$\{([A-Za-z][A-Za-z0-9_]*)}""")
        val simplifiedChineseCharacterPattern = Regex("""[\u3400-\u9fff]""")
        val englishCatalogText = englishCatalog.asFile.readText()

        fun readCatalog(file: File): Map<String, Set<String>> =
            stringPattern.findAll(file.readText())
                .associate { match ->
                    val key = match.groupValues[1]
                    val arguments = argumentPattern.findAll(match.groupValues[2])
                        .map { item -> item.groupValues[1] }
                        .toSet()
                    key to arguments
                }

        val english = readCatalog(englishCatalog.asFile)
        val simplifiedChinese = readCatalog(simplifiedChineseCatalog.asFile)
        check(english.keys == simplifiedChinese.keys) {
            "Localization key mismatch. English-only=${english.keys - simplifiedChinese.keys}, " +
                "Simplified-Chinese-only=${simplifiedChinese.keys - english.keys}"
        }
        english.forEach { (key, arguments) ->
            check(simplifiedChinese.getValue(key) == arguments) {
                "Localization argument mismatch for '$key': en=$arguments, zhHans=${simplifiedChinese.getValue(key)}"
            }
        }

        val allowedEnglishEndonymKeys = setOf("language_simplified_chinese_title")
        val untranslatedEnglishKeys = stringPattern
            .findAll(englishCatalogText)
            .filter { match ->
                match.groupValues[1] !in allowedEnglishEndonymKeys &&
                    simplifiedChineseCharacterPattern.containsMatchIn(match.groupValues[2])
            }
            .map { match -> match.groupValues[1] }
            .toList()
        check(untranslatedEnglishKeys.isEmpty()) {
            "English catalog contains untranslated Simplified Chinese values: $untranslatedEnglishKeys"
        }

        val allowedLowercaseEnglishKeys = setOf(
            "ui_conjunction_and", // "and" is composed inside a sentence.
            "task_transient_progressive_prefix", // "are" is composed inside a sentence.
        )
        val allowedLowercaseEnglishPrefixes = listOf(
            "iOS",
            "known_hosts",
            "openFile:",
        )
        val lowercaseEnglishStartKeys = stringPattern
            .findAll(englishCatalogText)
            .filter { match ->
                val key = match.groupValues[1]
                val value = match.groupValues[2].trimStart()
                key !in allowedLowercaseEnglishKeys &&
                    allowedLowercaseEnglishPrefixes.none(value::startsWith) &&
                    value.firstOrNull()?.isLowerCase() == true
            }
            .map { match -> match.groupValues[1] }
            .toList()
        check(lowercaseEnglishStartKeys.isEmpty()) {
            "English user-visible strings must use sentence case: $lowercaseEnglishStartKeys"
        }
    }
}

val notificationRoutesBaseline = rootProject.layout.projectDirectory.file("notification-routes.json")
val notificationRoutesGenerated = layout.buildDirectory.file("generated/notification-routes/notification-routes.json")
val notificationRoutesJvmJar = tasks.named<Jar>("jvmJar")
val notificationRoutesJvmRuntimeClasspath = configurations.named("jvmRuntimeClasspath")

fun JavaExec.configureNotificationRouteExport(vararg files: Provider<RegularFile>) {
    dependsOn(notificationRoutesJvmJar)
    classpath(notificationRoutesJvmJar.flatMap(Jar::getArchiveFile), notificationRoutesJvmRuntimeClasspath)
    mainClass.set("com.folderspan.notification.NotificationRouteCatalogExporterKt")
    args(files.map { file -> file.get().asFile.absolutePath })
}

val exportNotificationRoutes = tasks.register<JavaExec>("exportNotificationRoutes") {
    group = "build"
    description = "Exports the notification route registry to the committed catalog."
    configureNotificationRouteExport(provider { notificationRoutesBaseline })
    outputs.file(notificationRoutesBaseline)
}

val verifyNotificationRoutes = tasks.register<JavaExec>("verifyNotificationRoutes") {
    group = "verification"
    description = "Verifies that notification-routes.json matches the route registry."
    configureNotificationRouteExport(provider { notificationRoutesBaseline }, notificationRoutesGenerated)
    mustRunAfter(exportNotificationRoutes)
    inputs.file(notificationRoutesBaseline)
    outputs.file(notificationRoutesGenerated)
}

val auditUserVisibleStrings = tasks.register<Exec>("auditUserVisibleStrings") {
    group = "verification"
    description = "Rejects unclassified hardcoded Simplified Chinese user-visible Kotlin strings."
    workingDir(rootProject.projectDir)
    val pythonExecutable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "python"
    } else {
        "python3"
    }
    commandLine(
        pythonExecutable,
        rootProject.file("scripts/audit_user_visible_strings.py").absolutePath,
    )
    inputs.files(
        rootProject.fileTree("app/shared/src") { include("**/*.kt") },
        rootProject.fileTree("app/desktopApp/src") { include("**/*.kt") },
        rootProject.fileTree("core/src") { include("**/*.kt") },
        rootProject.fileTree("proMain/kotlin") { include("**/*.kt") },
        rootProject.file("scripts/audit_user_visible_strings.py"),
    )
}

tasks.matching { item -> item.name == "libresGenerateStrings" || item.name == "check" }.configureEach {
    dependsOn(validateAppStringCatalogs)
    if (name == "check") {
        dependsOn(auditUserVisibleStrings)
        dependsOn(verifyNotificationRoutes)
    }
}

tasks.matching { item -> item.name.startsWith("copyTestComposeResourcesFor") }.configureEach {
    dependsOn("libresGenerateImages")
}

sqldelight {
    databases {
        create("FolderSpanDatabase") {
            packageName.set("com.folderspan.db")
        }
    }
}
