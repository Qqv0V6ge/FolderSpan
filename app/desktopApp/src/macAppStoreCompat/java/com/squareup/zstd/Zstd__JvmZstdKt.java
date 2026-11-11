/*
 * Mac App Store adapter for zstd-kmp 0.4.0's JvmZstd.kt JVM entry point.
 * Original: Copyright (C) 2025 Square, Inc.; Apache License, Version 2.0.
 * The upstream license and all other classes remain in the packaged JAR.
 */
package com.squareup.zstd;

import java.nio.file.Path;

final class Zstd__JvmZstdKt {
    public static void loadNativeLibrary() {
        if (!"true".equals(System.getProperty("folderspan.macAppStore"))) {
            throw new UnsatisfiedLinkError("This loader is only for the FolderSpan Mac App Store package");
        }
        System.load(Path.of(System.getProperty("compose.application.resources.dir"), "native", "libzstd-kmp.dylib")
            .toAbsolutePath().toString());
    }
}
