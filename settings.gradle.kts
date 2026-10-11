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
include(":app:headlessRuntime")
include(":app:shared")
include(":app:webApp")
include(":core")
// 本地存在 Pro 模块时默认启用；传入 -PincludeProMain=false 可构建普通版。
if (file("proMain/build.gradle.kts").isFile && providers.gradleProperty("includeProMain").orNull != "false") {
    include(":proMain")
}
