package com.folderspan.privileged

import strings.AppStrings

import android.os.ParcelFileDescriptor
import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.exception.AuthorityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PrivilegedFileAccessTest {
    @Test
    fun rootDirectModeUsesRootBackendBeforeLocalIo() {
        var primaryCalled = false
        val attempts = mutableListOf<String>()
        val root = FakePrivilegedBackend(
            name = "root",
            attempts = attempts,
            result = Result.success("root"),
            authorized = true,
            usesBeforeLocalIo = true
        )
        val shizuku = FakePrivilegedBackend("shizuku", attempts, Result.success("shizuku"), authorized = true)

        val result = PrivilegedFileAccess.withFallback(
            primary = {
                primaryCalled = true
                Result.success("local")
            },
            backends = listOf(root, shizuku),
            operation = { client -> Result.success(client.backendName) }
        )

        assertEquals("root", result.getOrThrow())
        assertEquals(listOf("root"), attempts)
        assertEquals(false, primaryCalled)
    }

    @Test
    fun nonPermissionFailureDoesNotAttemptPrivilegedBackends() {
        val original = IllegalStateException("broken")
        val attempts = mutableListOf<String>()
        val shizuku = FakePrivilegedBackend("shizuku", attempts, Result.success("shizuku"))
        val root = FakePrivilegedBackend("root", attempts, Result.success("root"))

        val result = PrivilegedFileAccess.withFallback(
            primary = { Result.failure(original) },
            backends = listOf(shizuku, root),
            operation = { client -> Result.success(client.backendName) }
        )

        assertTrue(result.isFailure)
        assertSame(original, result.exceptionOrNull())
        assertEquals(emptyList(), attempts)
    }

    @Test
    fun permissionFailureUsesAuthorizedRootBeforeAuthorizedShizuku() {
        val attempts = mutableListOf<String>()
        val root = FakePrivilegedBackend("root", attempts, Result.success("root"), authorized = true)
        val shizuku = FakePrivilegedBackend("shizuku", attempts, Result.success("shizuku"), authorized = true)

        val result = PrivilegedFileAccess.withFallback(
            primary = { Result.failure(AuthorityException(AppStrings.message_task_permission_denied)) },
            backends = listOf(root, shizuku),
            operation = { client -> Result.success(client.backendName) }
        )

        assertEquals("root", result.getOrThrow())
        assertEquals(listOf("root"), attempts)
    }

    @Test
    fun permissionFailureUsesAuthorizedShizukuWhenRootIsNotAuthorized() {
        val attempts = mutableListOf<String>()
        val root = FakePrivilegedBackend("root", attempts, Result.success("root"), authorized = false)
        val shizuku = FakePrivilegedBackend("shizuku", attempts, Result.success("shizuku"), authorized = true)

        val result = PrivilegedFileAccess.withFallback(
            primary = { Result.failure(AuthorityException(AppStrings.message_task_permission_denied)) },
            backends = listOf(root, shizuku),
            operation = { client -> Result.success(client.backendName) }
        )

        assertEquals("shizuku", result.getOrThrow())
        assertEquals(listOf("shizuku"), attempts)
    }

    @Test
    fun permissionFailureDoesNotTrySecondAuthorizedBackendWhenSelectedBackendFails() {
        val original = AuthorityException(AppStrings.message_task_permission_denied)
        val attempts = mutableListOf<String>()
        val root = FakePrivilegedBackend("root", attempts, Result.failure(IllegalStateException("root failed")), authorized = true)
        val shizuku = FakePrivilegedBackend("shizuku", attempts, Result.success("shizuku"), authorized = true)

        val result = PrivilegedFileAccess.withFallback(
            primary = { Result.failure(original) },
            backends = listOf(root, shizuku),
            operation = { client -> Result.success(client.backendName) }
        )

        assertTrue(result.isFailure)
        assertSame(original, result.exceptionOrNull())
        assertEquals(listOf("root"), attempts)
    }

    @Test
    fun originalPermissionFailureIsPreservedWhenNoBackendSucceeds() {
        val original = AuthorityException(AppStrings.message_task_permission_denied)
        val attempts = mutableListOf<String>()
        val root = FakePrivilegedBackend("root", attempts, Result.failure(IllegalStateException("denied")), authorized = false)
        val shizuku = FakePrivilegedBackend("shizuku", attempts, Result.failure(IllegalStateException("missing")), authorized = false)

        val result = PrivilegedFileAccess.withFallback(
            primary = { Result.failure(original) },
            backends = listOf(root, shizuku),
            operation = { client -> Result.success(client.backendName) }
        )

        assertTrue(result.isFailure)
        assertSame(original, result.exceptionOrNull())
        assertEquals(emptyList(), attempts)
    }
}

private class FakePrivilegedBackend(
    private val name: String,
    private val attempts: MutableList<String>,
    private val result: Result<String>,
    private val authorized: Boolean = true,
    private val usesBeforeLocalIo: Boolean = false
) : PrivilegedFileBackend {
    override fun isAuthorized(): Boolean = authorized

    override fun usesBeforeLocalIo(): Boolean = usesBeforeLocalIo

    override fun <T> withClient(operation: (PrivilegedFileClient) -> Result<T>): Result<T> {
        attempts += name
        result.exceptionOrNull()?.let { error -> return Result.failure(error) }
        operation(FakePrivilegedFileClient(name)).getOrThrow()
        @Suppress("UNCHECKED_CAST")
        return Result.success(result.getOrThrow() as T)
    }
}

private class FakePrivilegedFileClient(
    override val backendName: String
) : PrivilegedFileClient {
    override fun list(path: String): Result<List<FileSimpleInfo>> = unsupported()
    override fun getFile(path: String): Result<FileSimpleInfo> = unsupported()
    override fun getFile(directory: String, fileName: String): Result<FileSimpleInfo> = unsupported()
    override fun getFileInfo(path: String): Result<FileInfo> = unsupported()
    override fun delete(path: String): Result<Boolean> = unsupported()
    override fun createDirectory(path: String): Result<Boolean> = unsupported()
    override fun createFile(path: String): Result<Boolean> = unsupported()
    override fun rename(path: String, oldName: String, newName: String): Result<Boolean> = unsupported()
    override fun totalSpace(path: String): Result<Long> = unsupported()
    override fun freeSpace(path: String): Result<Long> = unsupported()
    override fun exists(path: String): Result<Boolean> = unsupported()
    override fun deleteDirectory(path: String): Result<Boolean> = unsupported()
    override fun openFile(path: String, mode: Int): Result<ParcelFileDescriptor> = unsupported()

    private fun <T> unsupported(): Result<T> = Result.failure(UnsupportedOperationException())
}
