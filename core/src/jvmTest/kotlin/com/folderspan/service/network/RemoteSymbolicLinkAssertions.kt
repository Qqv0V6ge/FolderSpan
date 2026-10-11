package com.folderspan.service.network

import com.folderspan.exception.AuthorityException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal suspend fun verifyRemoteSymbolicLinkRejection(client: NetworkClient, root: String) {
    val reader = client as ChunkReadableNetworkClient
    val source = "$root/ordinary/payload.txt"
    val local = Files.createTempFile("remote-link-control", ".txt")
    suspend fun rejected(label: String, operation: suspend () -> Result<*>) {
        val result = operation()
        assertTrue(result.exceptionOrNull() is AuthorityException, "$label: ${result.exceptionOrNull()}")
    }
    suspend fun read(path: String): String {
        val bytes = mutableListOf<Byte>()
        reader.downloadByChunks(path, 7) { chunk, _ -> bytes.addAll(chunk.toList()); Result.success(Unit) }.getOrThrow()
        return bytes.toByteArray().decodeToString()
    }
    try {
        assertEquals("payload", read(source))
        val entries = client.list(root.ifEmpty { "/" }).getOrThrow()
        for (name in listOf("file-link", "dir-link", "dangling", "cycle")) {
            assertTrue(entries.single { it.name == name }.let { it.isSymbolicLinkKnown && it.isSymbolicLink })
        }
        for (path in listOf("$root/file-link", "$root/dir-link/payload.txt", "$root/dangling", "$root/cycle/payload.txt")) {
            var callbacks = 0
            rejected("stream read $path") {
                reader.downloadByChunks(path, 7) { _, _ -> callbacks++; Result.success(Unit) }
            }
            assertEquals(0, callbacks)
            Files.writeString(local, "local-control")
            rejected("download $path") { client.download(path, local.toString(), 7) }
            assertEquals("local-control", Files.readString(local))
            rejected("upload $path") { client.upload(local.toString(), path, 13) }
            rejected("stream write $path") {
                client.uploadFromSource(path, 0) { callbacks++; null }
            }
            assertEquals(0, callbacks)
            rejected("create $path") { client.createFile(path) }
            rejected("delete $path") { client.delete(path, false) }
            rejected("copy source $path") { client.copyFile(path, "$root/copy.txt") }
            rejected("copy target $path") { client.copyFile(source, path) }
            rejected("rename source $path") { client.rename(path, "$root/renamed.txt") }
            rejected("rename target $path") { client.rename(source, path) }
        }
        for (path in listOf("$root/dir-link", "$root/dir-link/", "$root/cycle", "$root/dangling")) {
            rejected("list $path") { client.list(path) }
            rejected("mkdir below $path") { client.createFolder("${path.trimEnd('/')}/new") }
            rejected("create below $path") { client.createFile("${path.trimEnd('/')}/new.txt") }
            rejected("delete directory $path") { client.delete(path, true) }
            rejected("rename below $path") { client.rename(source, "${path.trimEnd('/')}/new.txt") }
            rejected("copy below $path") { client.copyFile(source, "${path.trimEnd('/')}/new.txt") }
        }
        assertEquals("payload", read(source))
        assertEquals(listOf("payload.txt"), client.list("$root/ordinary").getOrThrow().map { it.name })

        val normal = "$root/new-directory"
        client.createFolder(normal).getOrThrow()
        client.createFile("$normal/empty").getOrThrow()
        Files.writeString(local, "payload")
        client.upload(local.toString(), "$normal/uploaded", 7).getOrThrow()
        assertEquals("payload", read("$normal/uploaded"))
        var sent = false
        client.uploadFromSource("$normal/streamed", 7) {
            if (sent) null else "payload".encodeToByteArray().also { sent = true }
        }.getOrThrow()
        client.copyFile("$normal/streamed", "$normal/copied").getOrThrow()
        client.rename("$normal/copied", "$normal/renamed").getOrThrow()
        client.download("$normal/renamed", local.toString(), 7).getOrThrow()
        assertEquals("payload", Files.readString(local))
        for (name in listOf("empty", "uploaded", "streamed", "renamed")) client.delete("$normal/$name", false).getOrThrow()
        client.delete(normal, true).getOrThrow()
    } finally {
        Files.deleteIfExists(local)
    }
}
