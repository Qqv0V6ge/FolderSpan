@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.folderspan.service.network

import strings.AppStrings

import com.folderspan.data.main.network.*
import com.folderspan.utils.IosSecurityScopeStore
import com.folderspan.utils.LogKit
import com.folderspan.utils.NetworkHostUtils
import cnames.structs.smb2_context
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.pointed
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.posix.*
import platform.smb2.SMB2_TYPE_DIRECTORY
import platform.smb2.smb2_close
import platform.smb2.smb2_closedir
import platform.smb2.smb2_connect_share
import platform.smb2.smb2_destroy_context
import platform.smb2.smb2_disconnect_share
import platform.smb2.smb2_get_error
import platform.smb2.smb2_init_context
import platform.smb2.smb2_mkdir
import platform.smb2.smb2_open
import platform.smb2.smb2_opendir
import platform.smb2.smb2_read
import platform.smb2.smb2_readdir
import platform.smb2.smb2_rename
import platform.smb2.smb2_rmdir
import platform.smb2.smb2_set_domain
import platform.smb2.smb2_set_password
import platform.smb2.smb2_set_user
import platform.smb2.smb2_unlink
import platform.smb2.smb2_write

internal class SmbNetworkClient(private val network: Network) : NetworkClient {
    override suspend fun list(
        path: String,
        requestId: String?,
        batchId: String?
    ): Result<List<NetworkFileEntry>> {
        return withShare { ctx ->
            val normalized = normalizePath(path)
            val dir = smb2_opendir(ctx, normalized) ?: return@withShare Result.failure(Exception(smbError(ctx)))
            val entries = mutableListOf<NetworkFileEntry>()
            while (true) {
                val entry = smb2_readdir(ctx, dir) ?: break
                val name = entry.pointed.name?.toKString().orEmpty()
                if (isUnsafeNetworkPathSegment(name)) continue
                val stat = entry.pointed.st
                val isDir = stat.smb2_type.toInt() == SMB2_TYPE_DIRECTORY
                val mtime = stat.smb2_mtime.toLong() * 1000
                entries.add(
                    NetworkFileEntry(
                        name = name,
                        path = joinPath(path, name),
                        isDirectory = isDir,
                        size = if (isDir) -1L else stat.smb2_size.toLong(),
                        createdDate = mtime,
                        updatedDate = mtime,
                        isHidden = name.startsWith(".")
                    )
                )
            }
            smb2_closedir(ctx, dir)
            LogKit.i(AppStrings.ui_smb_list_arg0_count_arg1.format(arg0 = normalized, arg1 = (entries.size).toString()))
            Result.success(entries)
        }
    }

    override suspend fun download(
        remotePath: String,
        localPath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> {
        return withShare { ctx ->
            IosSecurityScopeStore.withSecurityScopeIfNeeded(localPath) {
                val normalized = normalizePath(remotePath)
                val fh = smb2_open(ctx, normalized, O_RDONLY) ?: return@withSecurityScopeIfNeeded Result.failure(Exception(smbError(ctx)))
                val fd = open(localPath, O_WRONLY or O_CREAT or O_TRUNC or O_NOFOLLOW, 420)
                if (fd < 0) {
                    smb2_close(ctx, fh)
                    return@withSecurityScopeIfNeeded Result.failure(Exception(AppStrings.ui_unable_create_local_file))
                }
                val buffer = ByteArray(8192)
                val uBuffer = buffer.asUByteArray()
                var doneBytes = 0L
                try {
                    while (true) {
                        val readCount = smb2_read(ctx, fh, uBuffer.refTo(0), buffer.size.toUInt())
                        if (readCount == 0) break
                        if (readCount < 0) {
                            return@withSecurityScopeIfNeeded Result.failure(Exception(smbError(ctx)))
                        }
                        var offset = 0
                        while (offset < readCount) {
                            val written = write(
                                fd,
                                buffer.refTo(offset),
                                (readCount - offset).toULong()
                            )
                            if (written <= 0) {
                                return@withSecurityScopeIfNeeded Result.failure(Exception(AppStrings.ui_writing_local_file_failed))
                            }
                            offset += written.toInt()
                        }
                        doneBytes += readCount.toLong()
                        onProgress(doneBytes, size)
                    }
                } finally {
                    close(fd)
                    smb2_close(ctx, fh)
                }
                Result.success(true)
            }
        }
    }

    override suspend fun upload(
        localPath: String,
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> {
        return IosSecurityScopeStore.withSuspendSecurityScopeIfNeeded(localPath) {
            val fd = open(localPath, O_RDONLY or O_NOFOLLOW)
            if (fd < 0) {
                return@withSuspendSecurityScopeIfNeeded Result.failure(Exception(AppStrings.ui_unable_read_local_file))
            }
            try {
                withShare { ctx ->
                    val normalized = normalizePath(remotePath)
                    val fh = smb2_open(ctx, normalized, O_WRONLY or O_CREAT or O_TRUNC)
                        ?: return@withShare Result.failure(Exception(smbError(ctx)))
                    val buffer = ByteArray(8192)
                    val uBuffer = buffer.asUByteArray()
                    var doneBytes = 0L
                    try {
                        while (true) {
                            val readCount = read(fd, buffer.refTo(0), buffer.size.toULong())
                            if (readCount == 0L) break
                            if (readCount < 0L) {
                                return@withShare Result.failure(Exception(AppStrings.ui_failed_read_local_file))
                            }
                            var offset = 0
                            while (offset < readCount.toInt()) {
                                val written = smb2_write(
                                    ctx,
                                    fh,
                                    uBuffer.refTo(offset),
                                    (readCount.toInt() - offset).toUInt()
                                )
                                if (written < 0) {
                                    return@withShare Result.failure(Exception(smbError(ctx)))
                                }
                                offset += written
                                doneBytes += written.toLong()
                                onProgress(doneBytes, size)
                            }
                        }
                    } finally {
                        smb2_close(ctx, fh)
                    }
                    Result.success(true)
                }
            } finally {
                close(fd)
            }
        }
    }

    override suspend fun uploadFromSource(
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit,
        readChunk: suspend () -> ByteArray?,
    ): Result<Boolean> {
        return consumeUploadChunksBlocking(readChunk) { nextChunk ->
            withShare { ctx ->
                val normalized = normalizePath(remotePath)
                val fh = smb2_open(ctx, normalized, O_WRONLY or O_CREAT or O_TRUNC)
                    ?: return@withShare Result.failure(Exception(smbError(ctx)))
                var doneBytes = 0L
                try {
                    while (true) {
                        val chunk = nextChunk() ?: break
                        if (chunk.isEmpty()) continue
                        val uBuffer = chunk.asUByteArray()
                        var offset = 0
                        while (offset < chunk.size) {
                            val written = smb2_write(
                                ctx,
                                fh,
                                uBuffer.refTo(offset),
                                (chunk.size - offset).toUInt()
                            )
                            if (written < 0) {
                                return@withShare Result.failure(Exception(smbError(ctx)))
                            }
                            offset += written
                            doneBytes += written.toLong()
                            val total = if (size >= 0L) size else doneBytes
                            onProgress(doneBytes, total)
                        }
                    }
                } finally {
                    smb2_close(ctx, fh)
                }
                Result.success(true)
            }
        }
    }

    override suspend fun rename(path: String, newPath: String): Result<Boolean> {
        return withShare { ctx ->
            val oldPath = normalizePath(path)
            val target = normalizePath(newPath)
            val rc = smb2_rename(ctx, oldPath, target)
            if (rc == 0) Result.success(true) else Result.failure(Exception(smbError(ctx)))
        }
    }

    override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> {
        return withShare { ctx ->
            val normalized = normalizePath(path)
            val rc = if (isDirectory) smb2_rmdir(ctx, normalized) else smb2_unlink(ctx, normalized)
            if (rc == 0) Result.success(true) else Result.failure(Exception(smbError(ctx)))
        }
    }

    override suspend fun createFolder(path: String): Result<Boolean> {
        return withShare { ctx ->
            val normalized = normalizePath(path)
            val rc = smb2_mkdir(ctx, normalized)
            if (rc == 0) Result.success(true) else Result.failure(Exception(smbError(ctx)))
        }
    }

    override suspend fun createFile(path: String): Result<Boolean> {
        return withShare { ctx ->
            val normalized = normalizePath(path)
            val fh = smb2_open(ctx, normalized, O_WRONLY or O_CREAT or O_TRUNC)
                ?: return@withShare Result.failure(Exception(smbError(ctx)))
            smb2_close(ctx, fh)
            Result.success(true)
        }
    }

    private suspend fun <T> withShare(block: (CPointer<smb2_context>) -> Result<T>): Result<T> = withContext(Dispatchers.Default) {
        val ctx = smb2_init_context() ?: return@withContext Result.failure(Exception(AppStrings.ui_initialization_of_smb_failed))
        detectSmbStub(ctx)?.let { message ->
            smb2_destroy_context(ctx)
            LogKit.w(message)
            return@withContext Result.failure(IllegalStateException(message))
        }
        try {
            val resolved = NetworkHostUtils.resolveHostPort(network.host, 445)
            val host = resolved.host
            val port = resolved.port ?: 445
            val smbExtras = network.extras.smb
            val sharePreview = smbExtras.share.ifBlank { "<empty>" }
            val domainPreview = smbExtras.domain.ifBlank { "default" }
            LogKit.i(AppStrings.ui_smb_connection_arg0_arg1_share_arg2_domain_arg3_user.format(arg0 = host, arg1 = (port).toString(), arg2 = sharePreview, arg3 = domainPreview, arg4 = network.username))
            smb2_set_user(ctx, network.username)
            smb2_set_password(ctx, network.password)
            if (smbExtras.domain.isNotBlank()) {
                smb2_set_domain(ctx, smbExtras.domain)
            }
            val share = smbExtras.share.ifBlank { return@withContext Result.failure(Exception(AppStrings.ui_smb_share_name_cannot_be_empty)) }
            val hostPort = if (port != 445) NetworkHostUtils.combineHostPort(host, port) else host
            val rc = smb2_connect_share(ctx, hostPort, share, network.username)
            if (rc != 0) {
                val error = smbError(ctx)
                LogKit.w(AppStrings.ui_smb_connection_failed_arg0.format(arg0 = error))
                return@withContext Result.failure(Exception(error))
            }
            LogKit.i(AppStrings.ui_smb_is_connected_arg0_share_arg1.format(arg0 = (hostPort).toString(), arg1 = (share).toString()))
            block(ctx)
        } finally {
            smb2_disconnect_share(ctx)
            smb2_destroy_context(ctx)
        }
    }

    private fun detectSmbStub(ctx: CPointer<smb2_context>): String? {
        val marker = smb2_get_error(ctx)?.toKString().orEmpty()
        if (!marker.contains("stub", ignoreCase = true)) return null
        return AppStrings.ui_detecting_ios_libsmb2_as_a_stub_placeholder_replace_ios_native_xcframeworks
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return ""
        val normalized = path.replace("\\", "/").trim('/')
        requireSafeNetworkWritePath(normalized)
        return normalized
    }

    private fun joinPath(base: String, name: String): String {
        require(!isUnsafeNetworkPathSegment(name)) { AppStrings.ui_remote_path_invalid }
        val separator = network.pathSeparator
        val normalized = if (base.endsWith(separator)) base else base + separator
        return normalized + name
    }

    private fun smbError(ctx: CPointer<smb2_context>): String {
        return smb2_get_error(ctx)?.toKString() ?: AppStrings.ui_smb_operation_failed
    }
}
