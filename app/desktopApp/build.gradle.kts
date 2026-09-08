import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
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

val macLocalNetworkUsagePlist = """
    <key>NSLocalNetworkUsageDescription</key>
    <string>FolderSpan scans and connects to devices on your local network for file sharing.</string>
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
                    extraKeysRawXml = macLocalNetworkUsagePlist
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

tasks.withType<AbstractJLinkTask>().configureEach {
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
