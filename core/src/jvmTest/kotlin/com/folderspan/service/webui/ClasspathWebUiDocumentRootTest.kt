package com.folderspan.service.webui

import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ClasspathWebUiDocumentRootTest {
    @Test
    fun bundledUiLoadsFromJarWithCorrectContentTypes() {
        val directory = Files.createTempDirectory("web-ui-jar-test")
        try {
            val jar = directory.resolve("runtime.jar")
            val wasm = byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0)
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                output.putNextEntry(JarEntry("web-ui/assets/"))
                output.closeEntry()
                mapOf(
                    "web-ui/index.html" to "<html>Bundled UI</html>".encodeToByteArray(),
                    "web-ui/app.js" to "console.log('loaded')".encodeToByteArray(),
                    "web-ui/app.wasm" to wasm,
                    "web-ui/assets/index.html" to "nested page".encodeToByteArray(),
                    "private.txt" to "outside document root".encodeToByteArray(),
                ).forEach { (name, bytes) ->
                    output.putNextEntry(JarEntry(name))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            URLClassLoader(arrayOf(jar.toUri().toURL()), null).use { loader ->
                val root = resolveWebUiDocumentRoot(loader)
                assertIs<ClasspathWebUiDocumentRoot>(root)
                assertEquals("<html>Bundled UI</html>", root.get("/")?.bytes?.decodeToString())
                assertEquals("text/javascript; charset=UTF-8", root.get("/app.js?v=1")?.contentType)
                assertEquals("application/wasm", root.get("/app.wasm")?.contentType)
                assertContentEquals(wasm, root.get("/app.wasm")?.bytes)
                assertEquals("nested page", root.get("/assets/")?.bytes?.decodeToString())
                assertEquals("nested page", root.get("/assets")?.bytes?.decodeToString())
                assertNull(root.get("/../private.txt"))
                assertNull(root.get("/assets/../../private.txt"))
                assertNull(root.get("/assets\\private.txt"))
                assertNull(root.get("/missing.js"))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun runtimeWithoutBundledUiKeepsPlaceholder() {
        URLClassLoader(emptyArray(), null).use { loader ->
            val root = resolveWebUiDocumentRoot(loader)
            assertIs<PlaceholderWebUiDocumentRoot>(root)
            assertNull(root.get("/app.js"))
        }
    }
}
