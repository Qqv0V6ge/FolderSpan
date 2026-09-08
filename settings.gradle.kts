rootProject.name = "FolderSpan"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupByRegex("androidx(?:\\..*)?")
                includeGroupByRegex("com\\.android(?:\\..*)?")
                includeGroupByRegex("com\\.google(?:\\..*)?")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Centralized dependency repositories remain incubating in Gradle 9.6.1.
    @Suppress("UnstableApiUsage")
    repositories {
        google {
            mavenContent {
                includeGroupByRegex("androidx(?:\\..*)?")
                includeGroupByRegex("com\\.android(?:\\..*)?")
                includeGroupByRegex("com\\.google(?:\\..*)?")
            }
        }
        mavenCentral()
        maven("https://jitpack.io")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":app:androidApp")
include(":app:desktopApp")
include(":app:shared")
include(":app:webApp")
include(":core")
// 开发默认包含 Pro；商店打包传入 -PincludeProMain=false，从项目图中移除模块。
if (providers.gradleProperty("includeProMain").orNull != "false") {
    include(":proMain")
}
