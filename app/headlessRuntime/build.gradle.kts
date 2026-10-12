import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val runtimePackageName = "com.folderspan.runtime"
val runtimeAppName = "FolderSpan-Runtime"
val runtimeWebUi = providers.gradleProperty("runtimeWebUi").orElse("js").get()
require(runtimeWebUi in setOf("none", "js", "wasm")) {
    "runtimeWebUi must be none, js, or wasm, but was '$runtimeWebUi'."
}
val runtimeWebTarget = when (runtimeWebUi) {
    "js" -> "js"
    "wasm" -> "wasmJs"
    else -> null
}
val runtimeWebUiDirectory = layout.buildDirectory.dir("generated/web-ui/$runtimeWebUi")
// CI reuses the matching Web job's ZIP; local packaging can still build its own UI.
val runtimeWebUiArchive = providers.gradleProperty("runtimeWebUiArchive").orNull?.let(rootProject::file)
val runtimePackageVersion = providers.gradleProperty("releaseVersion")
    .map { item -> item.removePrefix("v") }
    .orElse("1.0.0")
    .get()

require(Regex("""\d+\.\d+\.\d+""").matches(runtimePackageVersion)) {
    "releaseVersion must use MAJOR.MINOR.PATCH format, but was '$runtimePackageVersion'."
}

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

val runtimeJavaHome = listOf(
    providers.gradleProperty("compose.desktop.java.home").orNull,
    providers.environmentVariable("JAVA_HOME").orNull,
    providers.gradleProperty("org.gradle.java.home").orNull,
    System.getProperty("java.home")
).firstNotNullOfOrNull(::resolveJdkHome)
val runtimeJdkMajorVersion = runtimeJavaHome?.let { home ->
    providers.fileContents(layout.file(providers.provider { File(home, "release") })).asText.map { release ->
        Regex("""(?m)^JAVA_VERSION="(?:1\.)?(\d+)""").find(release)?.groupValues?.get(1)?.toInt()
            ?: error("Cannot read the packaging JDK version from $home/release.")
    }.get()
}

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
    implementation(projects.core)
    // Compose packaging requires its compiler plugin; the entry point itself has no UI.
    compileOnly(libs.compose.runtime)
    implementation(libs.multiplatform.settings)
    implementation(libs.napier)
    implementation(libs.kotlinx.coroutines.core)
}

val embedWebUi = tasks.register<Sync>("embedWebUiDistribution") {
    group = "distribution"
    description = "Embed the selected production Web UI into the headless runtime classpath."
    if (runtimeWebTarget != null) {
        if (runtimeWebUiArchive != null) {
            from(zipTree(runtimeWebUiArchive))
        } else {
            dependsOn(":app:webApp:${runtimeWebTarget}BrowserDistribution")
            from(project(":app:webApp").layout.buildDirectory.dir("dist/$runtimeWebTarget/productionExecutable"))
        }
    }
    into(runtimeWebUiDirectory)
}

if (System.getProperty("os.name").lowercase().contains("mac") &&
    runtimeJdkMajorVersion != null && runtimeJdkMajorVersion < 18) {
    afterEvaluate {
        // JDK 17's PKG bundler needs the actual .app, not its containing directory.
        tasks.named<AbstractJPackageTask>("packagePkg") {
            appImage.set(layout.buildDirectory.dir("compose/binaries/main/app/$runtimeAppName.app"))
        }
        tasks.named<AbstractJPackageTask>("packageReleasePkg") {
            appImage.set(layout.buildDirectory.dir("compose/binaries/main-release/app/$runtimeAppName.app"))
        }
    }
}

// Embed production assets only when packaging; IDE hot runs also consume processResources.
tasks.named<Jar>("jar") {
    inputs.property("runtimeWebUi", runtimeWebUi)
    if (runtimeWebTarget != null) {
        dependsOn(embedWebUi)
        from(runtimeWebUiDirectory) {
            into("web-ui")
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.folderspan.runtime.MainKt"
        runtimeJavaHome?.let { item -> javaHome = item }
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        nativeDistributions {
            val hostOs = System.getProperty("os.name").lowercase()
            val hostFormats = when {
                hostOs.contains("mac") -> listOf(TargetFormat.Dmg, TargetFormat.Pkg)
                hostOs.contains("win") -> listOf(TargetFormat.Msi, TargetFormat.Exe)
                else -> listOf(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Rpm)
            }
            targetFormats(*hostFormats.toTypedArray())
            packageVersion = runtimePackageVersion
            description = "FolderSpan headless runtime"
            vendor = "FolderSpan"
            packageName = runtimeAppName
            modules("java.sql")
        }
        buildTypes {
            release {
                // Retain the core's JNI and reflection-based integrations, as in Desktop.
                proguard.isEnabled.set(false)
            }
        }
    }
}

// Compose's default run task depends on the packaged JAR, including the production Web UI.
// Local development needs only JVM classes and the unminified browser distribution.
afterEvaluate {
    tasks.named<JavaExec>("run") {
        setDependsOn(listOf("prepareAppResources"))
        classpath = files(sourceSets.main.get().output, configurations.runtimeClasspath)
    }
    if (runtimeWebTarget != null) {
        tasks.withType<JavaExec>().matching { it.name == "run" || it.name == "hotRun" }.configureEach {
            dependsOn(":app:webApp:${runtimeWebTarget}BrowserDevelopmentExecutableDistribution")
            systemProperty(
                "folderspan.webUi.root",
                project(":app:webApp").layout.buildDirectory.dir("dist/$runtimeWebTarget/developmentExecutable").get().asFile.absolutePath,
            )
        }
        // The IDE can also launch hotRunAsync through this argument-file task.
        tasks.matching { it.name == "hotRunArgfile" }.configureEach {
            dependsOn(":app:webApp:${runtimeWebTarget}BrowserDevelopmentExecutableDistribution")
        }
    }
}

fun registerTarGzTask(taskName: String, appImageTaskName: String, binaryDirectoryName: String) {
    tasks.register<Tar>(taskName) {
        dependsOn(appImageTaskName)
        from(layout.buildDirectory.dir("compose/binaries/$binaryDirectoryName/app/$runtimeAppName"))
        archiveBaseName.set(runtimePackageName)
        archiveAppendix.set(runtimeWebUi)
        archiveVersion.set(runtimePackageVersion)
        archiveExtension.set("tar.gz")
        compression = Compression.GZIP
        destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/$binaryDirectoryName/targz"))
        group = "compose desktop"
        description = "Package the headless runtime app image as a .tar.gz archive."
        onlyIf { System.getProperty("os.name").lowercase().contains("linux") }
    }
}

registerTarGzTask("packageTarGz", "packageAppImage", "main")
registerTarGzTask("packageReleaseTarGz", "packageReleaseAppImage", "main-release")

tasks.withType<AbstractJLinkTask>().configureEach {
    freeArgs.add("--compress=1")
    doFirst {
        val javaHomePath = javaHome.get()
        check(File(javaHomePath, "jmods").isDirectory) {
            "Packaging requires a full JDK with jmods. Set compose.desktop.java_home, JAVA_HOME, or org.gradle.java.home."
        }
    }
}
