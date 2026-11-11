package com.folderspan.service.network

import strings.AppStrings

import com.folderspan.exception.AuthorityException
import org.apache.commons.net.ftp.FTPSClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ui.state.main.LocalTaskArtifacts
import com.folderspan.ui.state.main.TaskUploadSourceIdentity
import com.folderspan.utils.PathUtils
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalUploadSourceJvmTest {

    @Test fun registeredContentStageGrantRequiresItsOriginalOwnerIdentityAndLiveRegistration(): Unit = runBlocking {
        val taskKey = 777_901L
        val stage = StorageContentPlatform.createStage(taskKey, "group-source").getOrThrow()
        val identity = StorageContentPlatform.stageIdentity(taskKey, stage).getOrThrow()
        val grant = TaskUploadSourceIdentity(FileSimpleInfo.pathFileSimpleInfo("/original"), stage, identity, taskKey)
        try {
            Files.write(Path.of(stage), byteArrayOf(1, 2, 3))
            assertFailsWith<AuthorityException> { openLocalSourceNoFollow(stage) }
            withContext(grant) {
                openLocalSourceNoFollow(stage).use { channel ->
                    val data = ByteBuffer.allocate(3)
                    assertTrue(channel.read(data) == 3)
                    assertContentEquals(byteArrayOf(1, 2, 3), data.array())
                }
            }
            assertTrue(StorageContentPlatform.stageIdentity(taskKey + 1, stage).isFailure)
            val replaced = Path.of(stage + ".original")
            Files.move(Path.of(stage), replaced)
            try {
                Files.write(Path.of(stage), byteArrayOf(9, 9, 9))
                withContext(grant) { assertFailsWith<IllegalStateException> { openLocalSourceNoFollow(stage) } }
                assertContentEquals(byteArrayOf(9, 9, 9), Files.readAllBytes(Path.of(stage)))
            } finally {
                Files.deleteIfExists(Path.of(stage))
                Files.move(replaced, Path.of(stage))
            }
            StorageContentPlatform.cleanup(taskKey).getOrThrow()
            withContext(grant) { assertFailsWith<IllegalStateException> { openLocalSourceNoFollow(stage) } }
        } finally { StorageContentPlatform.cleanup(taskKey).getOrThrow() }
    }
    @Test fun privateStageGrantUsesItsTrustedSessionContextInsteadOfTheGlobalHost(): Unit = runBlocking {
        val root = Files.createDirectories(Path.of(PathUtils.getCachePath(), "content-session-source"))
        val directory = Files.createTempDirectory(root, "session-")
        val settings = MapSettings()
        val context = StorageContentHostContext(StorageContentKeyStore(settings), settings, directory)
        val task = 777_903L
        try {
            val stage = context.createStage(task, "owned").getOrThrow()
            Files.write(Path.of(stage), byteArrayOf(3, 4, 5))
            val identity = context.stageIdentity(task, stage).getOrThrow()
            val source = FileSimpleInfo.pathFileSimpleInfo("/original")
            withContext(TaskUploadSourceIdentity(source, stage, identity, task)) {
                assertFailsWith<IllegalStateException> { openLocalSourceNoFollow(stage) }
            }
            withContext(TaskUploadSourceIdentity(source, stage, identity, task, context, false)) {
                assertFailsWith<IllegalStateException> { openLocalSinkNoFollow(stage) }
                openLocalSourceNoFollow(stage).use { channel ->
                    val bytes = ByteBuffer.allocate(3)
                    assertTrue(channel.read(bytes) == 3)
                    assertContentEquals(byteArrayOf(3, 4, 5), bytes.array())
                }
            }
        } finally {
            context.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun privateStageGrantIsBoundToOneOwnedFileAndCannotReadOrTruncateOtherFiles() = runBlocking {
        val root = Files.createDirectories(Path.of(PathUtils.getCachePath(), "sync-stage"))
        val directory = Files.createTempDirectory(root, "stage-access-test-")
        val stage = directory.resolve("stage.part")
        val other = directory.resolve("other.part")
        try {
            val owner = LocalTaskArtifacts.create(stage.toString(), cacheArtifact = true)
            Files.write(other, byteArrayOf(9, 9, 9))
            val grant = TaskUploadSourceIdentity(FileSimpleInfo.pathFileSimpleInfo("/original"), stage.toString(), owner.identity)
            assertFailsWith<AuthorityException> { openLocalSourceNoFollow(stage.toString()) }
            withContext(grant) {
                openLocalSinkNoFollow(stage.toString()).use { it.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3))) }
                openLocalSourceNoFollow(stage.toString()).use { channel ->
                    val buffer = ByteBuffer.allocate(3)
                    assertTrue(channel.read(buffer) == 3)
                    assertContentEquals(byteArrayOf(1, 2, 3), buffer.array())
                }
                assertFailsWith<AuthorityException> { openLocalSourceNoFollow(other.toString()) }
                assertFailsWith<AuthorityException> { openLocalSinkNoFollow(other.toString()) }
            }
            Files.move(stage, directory.resolve("original.part"))
            Files.write(stage, byteArrayOf(7, 7, 7))
            withContext(grant) {
                assertFailsWith<IllegalStateException> { openLocalSourceNoFollow(stage.toString()) }
                assertFailsWith<IllegalStateException> { openLocalSinkNoFollow(stage.toString()) }
            }
            assertContentEquals(byteArrayOf(7, 7, 7), Files.readAllBytes(stage))
            Files.delete(stage)
            Files.createSymbolicLink(stage, other)
            withContext(grant) { assertFailsWith<IllegalStateException> { openLocalSinkNoFollow(stage.toString()) } }
            assertContentEquals(byteArrayOf(9, 9, 9), Files.readAllBytes(other))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun openLocalSourceNoFollowRejectsSymbolicLinkWithoutReadingTarget() = runBlocking {
        val directory = Files.createTempDirectory("local-upload-nofollow-")
        val secret = directory.resolve("secret.bin")
        val link = directory.resolve("alias.bin")
        try {
            Files.write(secret, byteArrayOf(7, 7, 7))
            Files.createSymbolicLink(link, secret)
            val error = runCatching { openLocalSourceNoFollow(link.toString()) }.exceptionOrNull()
            assertTrue(error is AuthorityException || error?.message.orEmpty().contains(AppStrings.legacy_symbolic_link_marker))
            assertContentEquals(byteArrayOf(7, 7, 7), Files.readAllBytes(secret))
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(secret)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun openLocalSinkNoFollowRejectsSymbolicLinkWithoutWritingTarget() = runBlocking {
        val directory = Files.createTempDirectory("local-sink-nofollow-")
        val secret = directory.resolve("secret.bin")
        val link = directory.resolve("alias.bin")
        try {
            Files.write(secret, byteArrayOf(9, 9, 9))
            Files.createSymbolicLink(link, secret)
            val error = runCatching { openLocalSinkNoFollow(link.toString()) }.exceptionOrNull()
            assertTrue(error is AuthorityException || error?.message.orEmpty().contains(AppStrings.legacy_symbolic_link_marker))
            assertContentEquals(byteArrayOf(9, 9, 9), Files.readAllBytes(secret))
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(secret)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun ftpsTransportEnablesEndpointChecking() {
        val client = createFtpTransportClient(ftpsEnabled = true)
        assertTrue(client is FTPSClient)
        assertTrue(client.isEndpointCheckingEnabled)
        val plain = createFtpTransportClient(ftpsEnabled = false)
        assertFalse(plain is FTPSClient)
    }
}
