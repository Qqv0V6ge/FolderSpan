package com.folderspan.notification

import io.github.skeptick.libres.LibresSettings
import java.io.File

fun main(args: Array<String>) {
    LibresSettings.languageCode = "zhHans"
    require(args.size in 1..2) {
        "Expected an output path, optionally preceded by a committed catalog path to verify."
    }
    val output = File(args.last()).apply {
        parentFile?.mkdirs()
        writeText(exportNotificationRouteCatalogJson())
    }
    if (args.size == 2) {
        val baseline = File(args.first())
        require(baseline.exists()) {
            "Missing ${baseline.name}. Run :core:exportNotificationRoutes."
        }
        require(baseline.readBytes().contentEquals(output.readBytes())) {
            val baselineLines = baseline.readLines()
            val outputLines = output.readLines()
            val mismatchLine = (0 until maxOf(baselineLines.size, outputLines.size))
                .firstOrNull { index -> baselineLines.getOrNull(index) != outputLines.getOrNull(index) }
                ?.plus(1)
            "Notification route catalog drifted" +
                mismatchLine?.let { line -> " at line $line" }.orEmpty() +
                ". Run :core:exportNotificationRoutes and commit notification-routes.json."
        }
    }
}
