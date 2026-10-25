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
