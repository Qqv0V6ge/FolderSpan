package com.folderspan.service.webui

import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path

class PathWebUiDocumentRoot(
    private val root: Path,
) : WebUiDocumentRoot {
    override fun get(requestPath: String): WebUiStaticFile? {
        val relative = normalizeWebUiStaticPath(requestPath) ?: return null
        val resolved = root.resolve(relative).normalize()
        if (!resolved.startsWith(root.normalize())) return null
        val file = if (Files.isRegularFile(resolved)) {
            resolved
        } else if (Files.isDirectory(resolved)) {
            val index = resolved.resolve("index.html").normalize()
            if (Files.isRegularFile(index) && index.startsWith(root.normalize())) index else return null
        } else {
            return null
        }
        return WebUiStaticFile(
            bytes = Files.readAllBytes(file),
            contentType = guessWebUiContentType(file.fileName.toString()),
        )
    }
}

internal class ClasspathWebUiDocumentRoot(private val classLoader: ClassLoader) : WebUiDocumentRoot {
    override fun get(requestPath: String): WebUiStaticFile? {
        val relative = normalizeWebUiStaticPath(requestPath) ?: return null
        val candidates = if (relative.endsWith('/')) {
            listOf("${relative}index.html")
        } else {
            listOf(relative, "$relative/index.html")
        }
        for (candidate in candidates) {
            val resource = classLoader.getResource("web-ui/$candidate") ?: continue
            val connection = resource.openConnection()
            connection.useCaches = false
            if (connection is JarURLConnection && connection.jarEntry.isDirectory) {
                connection.jarFile.close()
                continue
            }
            if (resource.protocol == "file" && !Files.isRegularFile(Path.of(resource.toURI()))) continue
            return WebUiStaticFile(
                bytes = connection.getInputStream().use { it.readBytes() },
                contentType = guessWebUiContentType(candidate),
            )
        }
        return null
    }
}

actual fun createWebUiDocumentRoot(): WebUiDocumentRoot = resolveWebUiDocumentRoot()

fun resolveWebUiDocumentRoot(): WebUiDocumentRoot = resolveWebUiDocumentRoot(WebUiDocumentRoot::class.java.classLoader)

internal fun resolveWebUiDocumentRoot(classLoader: ClassLoader): WebUiDocumentRoot {
    val configured = System.getProperty("folderspan.webUi.root")?.trim()?.takeIf(String::isNotEmpty)
    if (configured != null) {
        val path = Path.of(configured)
        if (Files.isDirectory(path)) return PathWebUiDocumentRoot(path)
    }
    val resource = classLoader.getResource("web-ui/index.html")
    if (resource != null) {
        val uri = resource.toURI()
        if (uri.scheme == "file") {
            val index = Path.of(uri)
            return PathWebUiDocumentRoot(index.parent)
        }
        return ClasspathWebUiDocumentRoot(classLoader)
    }
    return PlaceholderWebUiDocumentRoot()
}
