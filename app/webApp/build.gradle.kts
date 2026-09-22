import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
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

    sourceSets {
        val commonMain = getByName("commonMain")
        val webMain = getByName("webMain")

        webMain.resources.srcDir(rootProject.file("core/src/commonMain/composeResources/files/share-file"))

        commonMain.dependencies {
            implementation(projects.app.shared)
            implementation(projects.core)
            implementation(libs.compose.ui)
            implementation(libs.koin.compose)
            implementation(libs.napier)
        }
    }
}

tasks.withType<KotlinWebpack>().matching { it.name.endsWith("ProductionWebpack") }.configureEach {
    sourceMaps = false
}

val linkShareStaticResourcesDir = rootProject.file("core/src/commonMain/composeResources/files/share-file/static")
val sqlJsRuntimeFiles = listOf("sql-wasm.js", "sql-wasm.wasm")

tasks.register<Copy>("copyLinkShareStaticToJsResources") {
    description = "Copies link-sharing static resources into the JS processed resources."
    from(linkShareStaticResourcesDir)
    into(layout.buildDirectory.dir("processedResources/js/main/static"))
}

tasks.register<Copy>("copyLinkShareStaticToWasmJsResources") {
    description = "Copies link-sharing static resources into the WasmJS processed resources."
    from(linkShareStaticResourcesDir)
    into(layout.buildDirectory.dir("processedResources/wasmJs/main/static"))
}

tasks.register<Copy>("copySqlJsWorkerToJsResources") {
    description = "Copies the SQL.js worker into the JS processed resources."
    from(rootProject.file("app/shared/src/jsMain/resources")) {
        include("sqljs.worker.js")
    }
    into(layout.buildDirectory.dir("processedResources/js/main"))
}

tasks.register<Copy>("copySqlJsWorkerToWasmJsResources") {
    description = "Copies the SQL.js worker into the WasmJS processed resources."
    from(rootProject.file("app/shared/src/wasmJsMain/resources")) {
        include("sqljs.worker.js")
    }
    into(layout.buildDirectory.dir("processedResources/wasmJs/main"))
}

tasks.register<Copy>("copySqlJsRuntimeToJsResources") {
    description = "Copies the SQL.js runtime into the JS processed resources."
    dependsOn(":kotlinNpmInstall")
    from(rootProject.layout.buildDirectory.dir("js/node_modules/sql.js/dist")) {
        include(sqlJsRuntimeFiles)
    }
    into(layout.buildDirectory.dir("processedResources/js/main"))
}

tasks.register<Copy>("copySqlJsRuntimeToWasmJsResources") {
    description = "Copies the SQL.js runtime into the WasmJS processed resources."
    dependsOn(":kotlinWasmNpmInstall")
    from(rootProject.layout.buildDirectory.dir("wasm/node_modules/sql.js/dist")) {
        include(sqlJsRuntimeFiles)
    }
    into(layout.buildDirectory.dir("processedResources/wasmJs/main"))
}

tasks.matching { it.name == "jsProcessResources" }.configureEach {
    dependsOn("copyLinkShareStaticToJsResources")
    dependsOn("copySqlJsWorkerToJsResources")
    dependsOn("copySqlJsRuntimeToJsResources")
}

tasks.matching { it.name == "wasmJsProcessResources" }.configureEach {
    dependsOn("copyLinkShareStaticToWasmJsResources")
    dependsOn("copySqlJsWorkerToWasmJsResources")
    dependsOn("copySqlJsRuntimeToWasmJsResources")
}
