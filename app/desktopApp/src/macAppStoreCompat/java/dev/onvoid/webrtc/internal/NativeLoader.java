/*
 * Mac App Store adapter for webrtc-java 0.20.0's NativeLoader public interface.
 * The original dependency's Apache-2.0 license and notices remain in its JAR.
 * Only the separate Store package substitutes this class; other distributions
 * retain the original loader. No executable code is extracted at runtime.
 */
package dev.onvoid.webrtc.internal;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class NativeLoader {
    private static final Set<String> loaded = new HashSet<>();

    public static void loadLibrary(String name) throws Exception {
        loadLibrary(name, new String[0]);
    }

    public static synchronized void loadLibrary(String name, String... dependencies) throws Exception {
        if (loaded.contains(name)) return;
        if (!"true".equals(System.getProperty("folderspan.macAppStore")) || !"webrtc-java".equals(name)) {
            throw new UnsatisfiedLinkError("This loader is only for the FolderSpan Mac App Store package");
        }
        Path root = Path.of(System.getProperty("compose.application.resources.dir"), "native").toRealPath();
        for (String dependency : dependencies) load(root, dependency);
        String architecture = System.getProperty("os.arch").toLowerCase();
        String suffix = architecture.equals("aarch64") || architecture.equals("arm64") ? "aarch64" : "x86_64";
        load(root, "libwebrtc-java-macos-" + suffix + ".dylib");
        loaded.add(name);
    }

    private static void load(Path root, String name) throws Exception {
        Path library = root.resolve(name).toRealPath();
        if (!root.equals(library.getParent())) throw new SecurityException("Invalid packaged library path");
        System.load(library.toString());
    }
}
