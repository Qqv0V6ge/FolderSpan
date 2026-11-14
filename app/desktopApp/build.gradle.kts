import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Optional
import org.gradle.process.ExecOperations
import org.gradle.api.file.FileSystemOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.composeCompiler)
}

abstract class PrepareMacStoreNatives : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeJars: ConfigurableFileCollection
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val script: RegularFileProperty
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val folderAccessSource: RegularFileProperty
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compatibilityClasses: DirectoryProperty
    @get:Input
    abstract val architecture: Property<String>
    @get:OutputDirectory
    abstract val resourceDirectory: DirectoryProperty
    @get:OutputDirectory
    abstract val patchedRuntimeDirectory: DirectoryProperty
    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun prepare() {
        val arch = architecture.get()
        execOperations.exec {
            commandLine(listOf("python3", script.get().asFile.absolutePath,
                resourceDirectory.get().dir("macos-$arch/native").asFile.absolutePath,
                if (arch == "arm64") "arm64" else "x86_64", folderAccessSource.get().asFile.absolutePath,
                compatibilityClasses.get().asFile.absolutePath, patchedRuntimeDirectory.get().asFile.absolutePath) +
                runtimeJars.files.map { it.absolutePath }.sorted())
        }
    }
}

abstract class FinalizeMacStoreImage : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceApp: DirectoryProperty
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val privacyManifest: RegularFileProperty
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val entitlements: RegularFileProperty
    @get:Input
    abstract val requireDistributionSignature: Property<Boolean>
    @get:Input
    @get:Optional
    abstract val signingKeychain: Property<String>
    @get:OutputDirectory
    abstract val appDirectory: DirectoryProperty
    @get:Inject
    abstract val execOperations: ExecOperations
    @get:Inject
    abstract val fileOperations: FileSystemOperations

    @TaskAction
    fun finalizeImage() {
        val source = sourceApp.get().asFile
        val destination = appDirectory.get().asFile
        fileOperations.delete { delete(destination) }
        execOperations.exec { commandLine("/usr/bin/ditto", source.absolutePath, destination.absolutePath) }
        val resources = destination.resolve("Contents/Resources").apply { mkdirs() }
        privacyManifest.get().asFile.copyTo(resources.resolve("PrivacyInfo.xcprivacy"), overwrite = true)
        val signature = ByteArrayOutputStream()
        execOperations.exec {
            commandLine("/usr/bin/codesign", "--display", "--verbose=2", source.absolutePath)
            errorOutput = signature
            standardOutput = ByteArrayOutputStream()
        }
        val authority = signature.toString().lineSequence().firstOrNull { it.startsWith("Authority=") }?.substringAfter('=')
        check(!requireDistributionSignature.get() || authority != null) { "Missing Mac App Distribution signature" }
        execOperations.exec {
            commandLine(listOf("/usr/bin/codesign", "--force", "--sign", authority ?: "-", "--options", "runtime",
                "--entitlements", entitlements.get().asFile.absolutePath) +
                (signingKeychain.orNull?.let { listOf("--keychain", it) } ?: emptyList()) + destination.absolutePath)
        }
        // The App Store package is installed by root and must be readable by ordinary users.
        execOperations.exec { commandLine("/bin/chmod", "-RP", "a+rX", destination.absolutePath) }
        execOperations.exec {
            commandLine("/usr/bin/codesign", "--verify", "--deep", "--strict", destination.absolutePath)
        }
    }
}

val desktopPackageName = "com.folderspan"
val desktopAppName = "FolderSpan"
val desktopPackageVersion = providers.gradleProperty("releaseVersion")
    .map { item -> item.removePrefix("v") }
    .orElse("1.0.0")
    .get()
val desktopPackageDescription = "FolderSpan"
val desktopPackageVendor = "FolderSpan"
val macAppStoreEnabled = providers.gradleProperty("macAppStore")
    .map(String::toBooleanStrict).orElse(false).get()
val macAppStoreResources = layout.buildDirectory.dir("generated/mac-appstore-resources")
val macAppStoreRuntime = layout.buildDirectory.dir("generated/mac-appstore-runtime")
val macNativeArch = if (System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")) "arm64" else "x64"
val macStoreClasspath = files(configurations.named("runtimeClasspath"))
val macNativeScript = layout.projectDirectory.file("scripts/prepare-mac-app-store-native-libraries.py").asFile
val macFolderAccessSource = layout.projectDirectory.file("src/main/native/macos/FolderAccess.m").asFile
val compileMacAppStoreLoaders = tasks.register<JavaCompile>("compileMacAppStoreLoaders") {
    enabled = macAppStoreEnabled
    source(fileTree("src/macAppStoreCompat/java") { include("**/*.java") })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("classes/mac-appstore-compat"))
    options.release.set(11)
}
val prepareMacAppStoreNativeLibraries = tasks.register<PrepareMacStoreNatives>("prepareMacAppStoreNativeLibraries") {
    enabled = macAppStoreEnabled
    runtimeJars.from(macStoreClasspath)
    script.set(macNativeScript)
    folderAccessSource.set(macFolderAccessSource)
    compatibilityClasses.set(compileMacAppStoreLoaders.flatMap { it.destinationDirectory })
    architecture.set(macNativeArch)
    resourceDirectory.set(macAppStoreResources)
    patchedRuntimeDirectory.set(macAppStoreRuntime)
}

require(Regex("""\d+\.\d+\.\d+""").matches(desktopPackageVersion)) {
    "releaseVersion must use MAJOR.MINOR.PATCH format, but was '$desktopPackageVersion'."
}

val macPermissionUsagePlist = """
    <key>NSLocalNetworkUsageDescription</key>
    <string>FolderSpan scans and connects to devices on your local network for file sharing.</string>
    <key>NSDesktopFolderUsageDescription</key>
    <string>FolderSpan accesses your Desktop to manage files and folders at your request.</string>
    <key>NSDocumentsFolderUsageDescription</key>
    <string>FolderSpan accesses your Documents to manage files and folders at your request.</string>
    <key>NSDownloadsFolderUsageDescription</key>
    <string>FolderSpan accesses your Downloads to save received files and manage downloads.</string>
    <key>NSNetworkVolumesUsageDescription</key>
    <string>FolderSpan accesses network volumes to browse and transfer files at your request.</string>
    <key>NSRemovableVolumesUsageDescription</key>
    <string>FolderSpan accesses removable drives to browse and transfer files at your request.</string>
""".trimIndent()
val desktopNoProxyJvmArgs = listOf(
    "-Djava.net.useSystemProxies=false",
    "-Dhttp.proxyHost=",
    "-Dhttp.proxyPort=",
    "-Dhttps.proxyHost=",
    "-Dhttps.proxyPort=",
    "-DsocksProxyHost=",
    "-DsocksProxyPort="
)
val packagingResourcesDir = layout.projectDirectory.dir("src/main/resources/packaging")
val desktopProguardEnabled = providers.gradleProperty("compose.desktop.proguard.enabled")
    .map { item -> item.toBoolean() }
    .orElse(false)

fun resolveJdkHome(path: String?): String? {
    if (path.isNullOrBlank()) return null
    val home = File(path)
    if (home.resolve("jmods").isDirectory) return home.absolutePath
    val parent = home.parentFile
    return if (home.name == "jre" && parent?.resolve("jmods")?.isDirectory == true) {
        parent.absolutePath
    } else {
        null
    }
}

val desktopJavaHome = listOf(
    providers.gradleProperty("compose.desktop.java.home").orNull,
    providers.environmentVariable("JAVA_HOME").orNull,
    providers.gradleProperty("org.gradle.java.home").orNull,
    System.getProperty("java.home")
).firstNotNullOfOrNull(::resolveJdkHome)

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation(projects.app.shared)
    implementation(projects.core)

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
    implementation(libs.compose.native.tray)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.koin.compose)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.napier)

    if (macAppStoreEnabled) {
        runtimeOnly("${libs.jna.get()}@jar")
    }

    testImplementation(kotlin("test"))
}

val isStandardEdition = rootProject.findProject(":proMain") == null
if (isStandardEdition) {
    sourceSets.named("main") {
        resources.srcDir(rootProject.tasks.named("prepareLicenseBundle"))
    }
}

compose.desktop {
    application {
        mainClass = "com.folderspan.MainKt"
        desktopJavaHome?.let { item -> javaHome = item }
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        jvmArgs += desktopNoProxyJvmArgs
        if (macAppStoreEnabled) {
            disableDefaultConfiguration()
            fromFiles(macStoreClasspath.filter { it.name !in setOf("webrtc-java-0.20.0.jar", "zstd-kmp-jvm-0.4.0.jar") },
                macAppStoreRuntime.map { it.asFileTree.matching { include("*.jar") } })
            mainJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
            fromFiles(tasks.named<Jar>("jar").flatMap { it.archiveFile })
            dependsOn("jar")
            dependsOn(prepareMacAppStoreNativeLibraries.name)
            jvmArgs += listOf(
                "-Dfolderspan.macAppStore=true",
                "-Djava.library.path=\$APPDIR/resources/native",
                "-Djna.boot.library.path=\$APPDIR/resources/native",
                "-Djna.nounpack=true",
                "-Dskiko.library.path=\$APPDIR/resources/native",
                "-Dorg.sqlite.lib.path=\$APPDIR/resources/native",
            )
        }

        nativeDistributions {
            val hostOs = System.getProperty("os.name").lowercase()
            val hostFormats = when {
                hostOs.contains("mac") -> if (macAppStoreEnabled) listOf(TargetFormat.Pkg) else listOf(TargetFormat.Dmg, TargetFormat.Pkg)
                hostOs.contains("win") -> listOf(TargetFormat.Msi, TargetFormat.Exe)
                else -> listOf(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Rpm)
            }
            targetFormats(*hostFormats.toTypedArray())
            packageVersion = desktopPackageVersion
            description = desktopPackageDescription
            vendor = desktopPackageVendor
            packageName = desktopAppName
            if (isStandardEdition) {
                licenseFile.set(rootProject.file("LICENSE"))
                appResourcesRootDir.set(rootProject.layout.buildDirectory.dir("generated/desktop-license-resources"))
            }
            if (macAppStoreEnabled) {
                appResourcesRootDir.set(prepareMacAppStoreNativeLibraries.flatMap { it.resourceDirectory })
                outputBaseDir.set(layout.buildDirectory.dir("compose/appstore"))
            }
            modules("java.sql", "java.net.http")

            linux {
                iconFile.set(packagingResourcesDir.file("icon-512.png"))
                installationPath = "/opt/$desktopPackageName"
                shortcut = true
                menuGroup = "FolderSpan"
                appCategory = "Utility"
                packageName = desktopPackageName
            }

            windows {
                iconFile.set(packagingResourcesDir.file("icon.ico"))
                installationPath = """C:\Program Files\FolderSpan"""
                shortcut = true
                menuGroup = "FolderSpan"
            }

            macOS {
                iconFile.set(packagingResourcesDir.file("icon.icns"))
                installationPath = "/Applications"
                bundleID = if (macAppStoreEnabled) "com.folderspan.FolderSpan" else desktopPackageName
                appStore = macAppStoreEnabled
                if (macAppStoreEnabled) {
                    minimumSystemVersion = "12.0"
                    packageVersion = providers.gradleProperty("macAppStoreVersion").orElse("1.0").get()
                    packageBuildVersion = providers.gradleProperty("macAppStoreBuild").orElse("2").get()
                    entitlementsFile.set(packagingResourcesDir.file("appstore-entitlements.plist"))
                    runtimeEntitlementsFile.set(packagingResourcesDir.file("appstore-runtime-entitlements.plist"))
                    providers.gradleProperty("macAppStoreProvisioningProfile").orNull?.let {
                        provisioningProfile.set(file(it))
                    }
                    providers.gradleProperty("macAppStoreRuntimeProvisioningProfile").orNull?.let {
                        runtimeProvisioningProfile.set(file(it))
                    }
                }
                appCategory = "public.app-category.utilities"
                infoPlist {
                    extraKeysRawXml = macPermissionUsagePlist
                }

                signing {
                    sign.set(
                        providers.gradleProperty("compose.desktop.mac.sign")
                            .map { item -> item.toBoolean() }
                            .orElse(false)
                    )
                    identity.set(providers.gradleProperty("compose.desktop.mac.signing.identity").orNull)
                    keychain.set(providers.gradleProperty("compose.desktop.mac.signing.keychain").orNull)
                    prefix.set(providers.gradleProperty("compose.desktop.mac.signing.prefix").orNull)
                }

                notarization {
                    appleID.set(providers.gradleProperty("compose.desktop.mac.notarization.appleID").orNull)
                    password.set(providers.gradleProperty("compose.desktop.mac.notarization.password").orNull)
                    teamID.set(providers.gradleProperty("compose.desktop.mac.notarization.teamID").orNull)
                }
            }
        }

        buildTypes {
            release {
                proguard {
                    isEnabled.set(desktopProguardEnabled)
                    configurationFiles.from(project.file("proguard-rules.pro"))
                }
            }
        }
    }
}

if (macAppStoreEnabled) {
    val finalizeMacAppStoreImage = tasks.register<FinalizeMacStoreImage>("finalizeMacAppStoreImage") {
        dependsOn("createDistributable")
        sourceApp.set(layout.buildDirectory.dir("compose/appstore/main/app/FolderSpan.app"))
        privacyManifest.set(packagingResourcesDir.file("appstore-privacy.xcprivacy"))
        entitlements.set(packagingResourcesDir.file("appstore-entitlements.plist"))
        requireDistributionSignature.set(providers.gradleProperty("compose.desktop.mac.sign").map(String::toBoolean).orElse(false))
        signingKeychain.set(providers.gradleProperty("compose.desktop.mac.signing.keychain"))
        appDirectory.set(layout.buildDirectory.dir("compose/appstore/store-ready/FolderSpan.app"))
    }
    afterEvaluate {
        tasks.named<AbstractJPackageTask>("packagePkg") {
            dependsOn(finalizeMacAppStoreImage)
            appImage.set(layout.buildDirectory.dir("compose/appstore/store-ready"))
        }
    }
}

fun registerTarGzTask(
    taskName: String,
    appImageTaskName: String,
    binaryDirectoryName: String
) {
    val appImageDirectory = layout.buildDirectory.dir(
        "compose/binaries/$binaryDirectoryName/app/$desktopAppName"
    )
    val archiveDirectory = layout.buildDirectory.dir(
        "compose/binaries/$binaryDirectoryName/targz"
    )
    tasks.register<Tar>(taskName) {
        dependsOn(appImageTaskName)
        from(appImageDirectory)
        // Gradle 9 defaults archive files to 0644, which makes the launcher unusable.
        useFileSystemPermissions()
        archiveBaseName.set(desktopPackageName)
        archiveVersion.set(desktopPackageVersion)
        archiveExtension.set("tar.gz")
        compression = Compression.GZIP
        destinationDirectory.set(archiveDirectory)
        group = "compose desktop"
        description = "Package the app image as a .tar.gz archive."
        onlyIf { System.getProperty("os.name").lowercase().contains("linux") }
    }
}

registerTarGzTask("packageTarGz", "packageAppImage", "main")
registerTarGzTask("packageReleaseTarGz", "packageReleaseAppImage", "main-release")

fun registerGuidedDmgTask(taskName: String, distributableTaskName: String, binaryDirectoryName: String) {
    val installerDirectory = layout.projectDirectory.dir("installer")
    val appImageDirectory = layout.buildDirectory.dir("compose/binaries/$binaryDirectoryName/app/$desktopAppName.app")
    val dmgFile = layout.buildDirectory.file("compose/binaries/$binaryDirectoryName/dmg/$desktopAppName-$desktopPackageVersion.dmg")
    val signingEnabled = providers.gradleProperty("compose.desktop.mac.sign").orElse("false").get()
    val signingIdentity = providers.gradleProperty("compose.desktop.mac.signing.identity").orElse("").get()
    val signingKeychain = providers.gradleProperty("compose.desktop.mac.signing.keychain").orElse("").get()
    tasks.register<Exec>(taskName) {
        dependsOn(distributableTaskName)
        inputs.dir(appImageDirectory)
        inputs.files(fileTree(installerDirectory) { exclude(".build/**", ".swiftpm/**", "build/**") })
        inputs.files(packagingResourcesDir.file("icon.icns"), packagingResourcesDir.file("icon-1024.png"))
        inputs.property("version", desktopPackageVersion)
        inputs.property("sign", signingEnabled)
        inputs.property("identity", signingIdentity)
        inputs.property("keychain", signingKeychain)
        outputs.file(dmgFile)
        group = "compose desktop"
        description = "Package the native, bilingual FolderSpan installer as a DMG."
        onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
        commandLine(
            "bash", installerDirectory.file("package-dmg.sh").asFile.absolutePath,
            appImageDirectory.get().asFile.absolutePath, dmgFile.get().asFile.absolutePath, desktopPackageVersion
        )
        environment("FOLDERSPAN_INSTALLER_SIGN", signingEnabled)
        environment("FOLDERSPAN_INSTALLER_SIGN_IDENTITY", signingIdentity)
        environment("FOLDERSPAN_INSTALLER_SIGN_KEYCHAIN", signingKeychain)
    }
}

if (System.getProperty("os.name").lowercase().contains("mac") && !macAppStoreEnabled) {
    registerGuidedDmgTask("packageGuidedDmg", "createDistributable", "main")
    registerGuidedDmgTask("packageReleaseGuidedDmg", "createReleaseDistributable", "main-release")
    // Keep existing local/CI commands and Compose's notarization input directory.
    afterEvaluate {
        tasks.named("packageDmg") {
            dependsOn("packageGuidedDmg")
            enabled = false
        }
        tasks.named("packageReleaseDmg") {
            dependsOn("packageReleaseGuidedDmg")
            enabled = false
        }
    }
    tasks.register<Exec>("testMacInstaller") {
        group = "verification"
        description = "Test installer localization and filesystem transactions."
        commandLine("swift", "test", "--package-path", layout.projectDirectory.dir("installer").asFile.absolutePath)
    }
    tasks.named("check") { dependsOn("testMacInstaller") }
}

tasks.withType<AbstractJLinkTask>().configureEach {
    // Share constant strings; ZIP compression makes the outer installer/archive larger.
    freeArgs.add("--compress=1")
    doFirst {
        val javaHomePath = javaHome.get()
        check(File(javaHomePath, "jmods").isDirectory) {
            "Packaging requires a full JDK with jmods. Set compose.desktop.java.home, JAVA_HOME, or org.gradle.java.home."
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(desktopNoProxyJvmArgs)
}

if (isStandardEdition) {
    tasks.matching { it.name == "prepareAppResources" }.configureEach {
        dependsOn(rootProject.tasks.named("prepareDesktopLicenseResources"))
    }
    // Compose marks appResourcesDir as internal; include it in packaging fingerprints.
    tasks.withType<AbstractJPackageTask>().configureEach {
        inputs.dir(appResourcesDir)
            .withPropertyName("applicationResources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .optional()
    }
}
