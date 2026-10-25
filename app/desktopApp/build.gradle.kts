import org.gradle.api.tasks.PathSensitivity
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

val desktopPackageName = "com.folderspan"
val desktopAppName = "FolderSpan"
val desktopPackageVersion = providers.gradleProperty("releaseVersion")
    .map { item -> item.removePrefix("v") }
    .orElse("1.0.0")
    .get()
val desktopPackageDescription = "FolderSpan"
val desktopPackageVendor = "FolderSpan"

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

        nativeDistributions {
            val hostOs = System.getProperty("os.name").lowercase()
            val hostFormats = when {
                hostOs.contains("mac") -> listOf(TargetFormat.Dmg, TargetFormat.Pkg)
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
            modules("java.sql")

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
                bundleID = desktopPackageName
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

if (System.getProperty("os.name").lowercase().contains("mac")) {
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
