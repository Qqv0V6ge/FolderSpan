import org.gradle.api.tasks.testing.Test

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.build.config) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlin.native.cocoapods) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
    alias(libs.plugins.libres) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.sqldelight) apply false
}

// One source of license files for every application artifact.
val licenseFileNames = listOf("LICENSE", "NOTICE", "LICENSING.md", "THIRD_PARTY_NOTICES.txt")
val licenseSourceFiles = licenseFileNames.map { layout.projectDirectory.file(it).asFile }
val prepareLicenseBundle = tasks.register<Sync>("prepareLicenseBundle") {
    val requiredFiles = licenseSourceFiles.toList()
    group = "distribution"
    description = "Prepare product and third-party notices for application resources."
    inputs.files(licenseSourceFiles)
    from(layout.projectDirectory) {
        include(licenseFileNames)
        into("licenses")
    }
    into(layout.buildDirectory.dir("generated/license-bundle"))
    doFirst {
        require(requiredFiles.all { it.isFile }) {
            "Product license files are missing. Generate THIRD_PARTY_NOTICES.txt before packaging."
        }
    }
}

tasks.register<Sync>("prepareDesktopLicenseResources") {
    group = "distribution"
    description = "Prepare readable license files outside desktop application JARs."
    from(prepareLicenseBundle)
    into(layout.buildDirectory.dir("generated/desktop-license-resources/common"))
}

// Concurrent Test tasks run in separate JVMs; their in-memory staging locks are not shared.
subprojects {
    tasks.withType<Test>().configureEach {
        val stateRoot = layout.buildDirectory.dir("test-user-data/$name").get().asFile
        val home = stateRoot.resolve("home")
        val dataHome = home.resolve(".local/share")
        val cacheHome = home.resolve(".cache")
        val appData = home.resolve("AppData/Roaming")
        val localAppData = home.resolve("AppData/Local")
        val temporary = stateRoot.resolve("tmp")
        val preferences = stateRoot.resolve("prefs")
        val systemPreferences = stateRoot.resolve("system-prefs")
        val requiredDirectories = listOf(
            home, dataHome, cacheHome, appData, localAppData, temporary, preferences, systemPreferences,
        )

        systemProperty("user.home", home.absolutePath)
        systemProperty("java.io.tmpdir", temporary.absolutePath)
        systemProperty("java.util.prefs.userRoot", preferences.absolutePath)
        systemProperty("java.util.prefs.systemRoot", systemPreferences.absolutePath)
        environment("HOME", home.absolutePath)
        environment("USERPROFILE", home.absolutePath)
        environment("APPDATA", appData.absolutePath)
        environment("LOCALAPPDATA", localAppData.absolutePath)
        environment("XDG_DATA_HOME", dataHome.absolutePath)
        environment("XDG_CACHE_HOME", cacheHome.absolutePath)
        environment("TMPDIR", temporary.absolutePath)
        environment("TMP", temporary.absolutePath)
        environment("TEMP", temporary.absolutePath)
        doFirst {
            requiredDirectories.forEach { directory ->
                check(directory.isDirectory || directory.mkdirs()) {
                    "Cannot create isolated test directory: ${directory.absolutePath}"
                }
            }
        }
    }
}
