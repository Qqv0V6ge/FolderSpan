@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.folderspan.service.network

import strings.AppStrings

import com.folderspan.data.main.network.*
import com.folderspan.utils.IosSecurityScopeStore
import com.folderspan.utils.LogKit
import com.folderspan.utils.NetworkHostUtils
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.libssh2.LIBSSH2_FXF_CREAT
import platform.libssh2.LIBSSH2_FXF_READ
import platform.libssh2.LIBSSH2_FXF_TRUNC
import platform.libssh2.LIBSSH2_FXF_WRITE
import platform.libssh2.LIBSSH2_KNOWNHOST_CHECK_MATCH
import platform.libssh2.LIBSSH2_KNOWNHOST_FILE_OPENSSH
import platform.libssh2.LIBSSH2_KNOWNHOST_KEYENC_RAW
import platform.libssh2.LIBSSH2_KNOWNHOST_TYPE_PLAIN
import platform.libssh2.LIBSSH2_SESSION
import platform.libssh2.LIBSSH2_SFTP
import platform.libssh2.LIBSSH2_SFTP_ATTRIBUTES
import platform.libssh2.LIBSSH2_SFTP_ATTR_ACMODTIME
import platform.libssh2.LIBSSH2_SFTP_ATTR_SIZE
import platform.libssh2.LIBSSH2_SFTP_OPENDIR
import platform.libssh2.LIBSSH2_SFTP_OPENFILE
import platform.libssh2.LIBSSH2_SFTP_RENAME_OVERWRITE
import platform.libssh2.LIBSSH2_SFTP_S_IFDIR
import platform.libssh2.LIBSSH2_SFTP_S_IFMT
import platform.libssh2.libssh2_exit
import platform.libssh2.libssh2_init
import platform.libssh2.libssh2_knownhost_checkp
import platform.libssh2.libssh2_knownhost_free
import platform.libssh2.libssh2_knownhost_init
import platform.libssh2.libssh2_knownhost_readfile
import platform.libssh2.libssh2_session_disconnect_ex
import platform.libssh2.libssh2_session_free
import platform.libssh2.libssh2_session_handshake
import platform.libssh2.libssh2_session_hostkey
import platform.libssh2.libssh2_session_init_ex
import platform.libssh2.libssh2_session_last_error
import platform.libssh2.libssh2_session_set_blocking
import platform.libssh2.libssh2_sftp_close_handle
import platform.libssh2.libssh2_sftp_init
import platform.libssh2.libssh2_sftp_mkdir_ex
import platform.libssh2.libssh2_sftp_open_ex
import platform.libssh2.libssh2_sftp_read
import platform.libssh2.libssh2_sftp_readdir_ex
import platform.libssh2.libssh2_sftp_rename_ex
import platform.libssh2.libssh2_sftp_rmdir_ex
import platform.libssh2.libssh2_sftp_shutdown
import platform.libssh2.libssh2_sftp_unlink_ex
import platform.libssh2.libssh2_sftp_write
import platform.libssh2.libssh2_userauth_password_ex
import platform.libssh2.libssh2_userauth_publickey_fromfile_ex
import platform.posix.*
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Exception
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.Long
import kotlin.OptIn
import kotlin.Result
import kotlin.String
import kotlin.Unit
import kotlin.let
import kotlin.text.contains
import kotlin.text.encodeToByteArray
import kotlin.text.endsWith
import kotlin.text.isBlank
import kotlin.text.isNotBlank
import kotlin.text.replace
import kotlin.text.startsWith
import kotlin.text.trim
import kotlin.toUInt
import kotlin.toULong

internal class SftpNetworkClient(private val network: Network) : NetworkClient {
    override suspend fun list(
        path: String,
        requestId: String?,
        batchId: String?
    ): Result<List<NetworkFileEntry>> {
        return withSftp { sftp ->
            val target = normalizePath(path)
            val handle = libssh2_sftp_open_ex(
                sftp,
                target,
                target.encodeToByteArray().size.toUInt(),
                0uL,
                0L,
                LIBSSH2_SFTP_OPENDIR
            ) ?: return@withSftp Result.failure(Exception(AppStrings.ui_sftp_open_directory_failed))
            val entries = mutableListOf<NetworkFileEntry>()
            memScoped {
                val attrs = alloc<LIBSSH2_SFTP_ATTRIBUTES>()
                val nameBuf = allocArray<ByteVar>(1024)
                val longBuf = allocArray<ByteVar>(1024)
                val bufSize = 1024UL
                while (true) {
                    val rc = libssh2_sftp_readdir_ex(
                        handle,
                        nameBuf,
                        bufSize,
                        longBuf,
                        bufSize,
                        attrs.ptr
                    )
                    if (rc <= 0) break
                    if (rc < 1024) nameBuf[rc] = 0
                    val name = nameBuf.toKString()
                    if (isUnsafeNetworkPathSegment(name)) continue
                    val isDir = (attrs.permissions.toInt() and LIBSSH2_SFTP_S_IFMT) == LIBSSH2_SFTP_S_IFDIR
                    val size = if (isDir) {
                        -1L
                    } else if (attrs.flags.toInt() and LIBSSH2_SFTP_ATTR_SIZE != 0) {
                        attrs.filesize.toLong()
                    } else {
                        0L
                    }
                    val modified = if (attrs.flags.toInt() and LIBSSH2_SFTP_ATTR_ACMODTIME != 0) {
                        attrs.mtime.toLong() * 1000
                    } else {
                        0L
                    }
                    entries.add(
                        NetworkFileEntry(
                            name = name,
                            path = joinPath(path, name),
                            isDirectory = isDir,
                            size = size,
                            createdDate = modified,
                            updatedDate = modified,
                            isHidden = name.startsWith(".")
                        )
                    )
                }
            }
            libssh2_sftp_close_handle(handle)
            LogKit.i(AppStrings.ui_sftp_list_arg0_count_arg1.format(arg0 = target, arg1 = (entries.size).toString()))
            Result.success(entries)
        }
    }

    override suspend fun download(
        remotePath: String,
        localPath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> {
        return withSftp { sftp ->
            IosSecurityScopeStore.withSecurityScopeIfNeeded(localPath) {
                val normalized = normalizePath(remotePath)
                val handle = libssh2_sftp_open_ex(
                    sftp,
                    normalized,
                    normalized.encodeToByteArray().size.toUInt(),
                    LIBSSH2_FXF_READ.toULong(),
                    0L,
                    LIBSSH2_SFTP_OPENFILE
                ) ?: return@withSecurityScopeIfNeeded Result.failure(
                    Exception(AppStrings.ui_sftp_open_remote_file_failed)
                )
                val fd = open(localPath, O_WRONLY or O_CREAT or O_TRUNC or O_NOFOLLOW, 420)
                if (fd < 0) {
                    libssh2_sftp_close_handle(handle)
                    return@withSecurityScopeIfNeeded Result.failure(posixError(AppStrings.ui_unable_create_local_file))
                }
                val buffer = ByteArray(8192)
                var doneBytes = 0L
                try {
                    while (true) {
                        val readCount = libssh2_sftp_read(
                            handle,
                            buffer.refTo(0),
                            buffer.size.toULong()
                        )
                        if (readCount == 0L) break
                        if (readCount < 0L) {
                            return@withSecurityScopeIfNeeded Result.failure(
                                Exception(AppStrings.ui_sftp_download_failed)
                            )
                        }
                        var offset = 0
                        while (offset < readCount.toInt()) {
                            val written = write(
                                fd,
                                buffer.refTo(offset),
                                (readCount.toInt() - offset).toULong()
                            )
                            if (written <= 0) {
                                return@withSecurityScopeIfNeeded Result.failure(posixError(AppStrings.ui_writing_local_file_failed))
                            }
                            offset += written.toInt()
                        }
                        doneBytes += readCount
                        onProgress(doneBytes, size)
                    }
                } finally {
                    close(fd)
                    libssh2_sftp_close_handle(handle)
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
                return@withSuspendSecurityScopeIfNeeded Result.failure(posixError(AppStrings.ui_unable_read_local_file))
            }
            try {
                withSftp { sftp ->
                    val normalized = normalizePath(remotePath)
                    val handle = libssh2_sftp_open_ex(
                        sftp,
                        normalized,
                        normalized.encodeToByteArray().size.toUInt(),
                        (LIBSSH2_FXF_WRITE or LIBSSH2_FXF_CREAT or LIBSSH2_FXF_TRUNC).toULong(),
                        0L,
                        LIBSSH2_SFTP_OPENFILE
                    ) ?: return@withSftp Result.failure(
                        Exception(AppStrings.ui_sftp_open_remote_file_failed)
                    )
                    val buffer = ByteArray(8192)
                    var doneBytes = 0L
                    try {
                        while (true) {
                            val readCount = read(fd, buffer.refTo(0), buffer.size.toULong())
                            if (readCount == 0L) break
                            if (readCount < 0L) {
                                return@withSftp Result.failure(posixError(AppStrings.ui_failed_read_local_file))
                            }
                            var offset = 0
                            while (offset < readCount.toInt()) {
                                val written = libssh2_sftp_write(
                                    handle,
                                    buffer.refTo(offset),
                                    (readCount.toInt() - offset).toULong()
                                )
                                if (written < 0L) {
                                    return@withSftp Result.failure(
                                        Exception(AppStrings.ui_sftp_upload_failed)
                                    )
                                }
                                offset += written.toInt()
                                doneBytes += written
                                onProgress(doneBytes, size)
                            }
                        }
                    } finally {
                        libssh2_sftp_close_handle(handle)
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
            withSftp { sftp ->
                val normalized = normalizePath(remotePath)
                val handle = libssh2_sftp_open_ex(
                    sftp,
                    normalized,
                    normalized.encodeToByteArray().size.toUInt(),
                    (LIBSSH2_FXF_WRITE or LIBSSH2_FXF_CREAT or LIBSSH2_FXF_TRUNC).toULong(),
                    0L,
                    LIBSSH2_SFTP_OPENFILE
                ) ?: return@withSftp Result.failure(
                    Exception(AppStrings.ui_sftp_open_remote_file_failed)
                )
                var doneBytes = 0L
                try {
                    while (true) {
                        val chunk = nextChunk() ?: break
                        if (chunk.isEmpty()) continue
                        var offset = 0
                        while (offset < chunk.size) {
                            val written = libssh2_sftp_write(
                                handle,
                                chunk.refTo(offset),
                                (chunk.size - offset).toULong()
                            )
                            if (written < 0L) {
                                return@withSftp Result.failure(
                                    Exception(AppStrings.ui_sftp_upload_failed)
                                )
                            }
                            offset += written.toInt()
                            doneBytes += written
                            val total = if (size >= 0L) size else doneBytes
                            onProgress(doneBytes, total)
                        }
                    }
                } finally {
                    libssh2_sftp_close_handle(handle)
                }
                Result.success(true)
            }
        }
    }

    override suspend fun rename(path: String, newPath: String): Result<Boolean> {
        return withSftp { sftp ->
            val source = normalizePath(path)
            val target = normalizePath(newPath)
            val rc = libssh2_sftp_rename_ex(
                sftp,
                source,
                source.encodeToByteArray().size.toUInt(),
                target,
                target.encodeToByteArray().size.toUInt(),
                LIBSSH2_SFTP_RENAME_OVERWRITE.toLong()
            )
            if (rc == 0) Result.success(true) else Result.failure(Exception(AppStrings.ui_sftp_rename_failed))
        }
    }

    override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> {
        return withSftp { sftp ->
            val normalized = normalizePath(path)
            val rc = if (isDirectory) {
                libssh2_sftp_rmdir_ex(sftp, normalized, normalized.encodeToByteArray().size.toUInt())
            } else {
                libssh2_sftp_unlink_ex(sftp, normalized, normalized.encodeToByteArray().size.toUInt())
            }
            if (rc == 0) Result.success(true) else Result.failure(Exception(AppStrings.ui_sftp_delete_failed))
        }
    }

    override suspend fun createFolder(path: String): Result<Boolean> {
        return withSftp { sftp ->
            val normalized = normalizePath(path)
            val rc = libssh2_sftp_mkdir_ex(
                sftp,
                normalized,
                normalized.encodeToByteArray().size.toUInt(),
                0L
            )
            if (rc == 0) {
                Result.success(true)
            } else {
                Result.failure(Exception(AppStrings.ui_sftp_directory_creation_failed))
            }
        }
    }

    override suspend fun createFile(path: String): Result<Boolean> {
        return withSftp { sftp ->
            val normalized = normalizePath(path)
            val handle = libssh2_sftp_open_ex(
                sftp,
                normalized,
                normalized.encodeToByteArray().size.toUInt(),
                (LIBSSH2_FXF_CREAT or LIBSSH2_FXF_WRITE or LIBSSH2_FXF_TRUNC).toULong(),
                0L,
                LIBSSH2_SFTP_OPENFILE
            ) ?: return@withSftp Result.failure(Exception(AppStrings.ui_sftp_file_creation_failed))
            libssh2_sftp_close_handle(handle)
            Result.success(true)
        }
    }

    private suspend fun <T> withSftp(block: (CPointer<LIBSSH2_SFTP>) -> Result<T>): Result<T> =
        withContext(Dispatchers.Default) {
        var socketFd = -1
        var session: CPointer<LIBSSH2_SESSION>? = null
        var sftp: CPointer<LIBSSH2_SFTP>? = null
        var knownHosts: CPointer<platform.libssh2.LIBSSH2_KNOWNHOSTS>? = null
        var knownHostsPath: String? = null
        var privateKeyPath: String? = null
        val resolved = NetworkHostUtils.resolveHostPort(network.host, 22)
        val host = resolved.host
        val port = resolved.port ?: 22
        val sftpExtras = network.extras.sftp
        val authHint = if (sftpExtras.privateKey.isNotBlank()) "key" else "password"
        val knownHostsHint = if (sftpExtras.knownHosts.isNotBlank()) "known_hosts=provided" else "known_hosts=missing"
        LogKit.i(AppStrings.ui_sftp_connection_arg0_arg1_user_arg2_auth_arg3_arg4.format(arg0 = host, arg1 = (port).toString(), arg2 = network.username, arg3 = authHint, arg4 = knownHostsHint))
        if (sftpExtras.knownHosts.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException(AppStrings.ui_sftp_known_hosts_missing_connection_rejected)
            )
        }
        try {
            if (libssh2_init(0) != 0) {
                return@withContext Result.failure(Exception(AppStrings.ui_sftp_initialization_failed))
            }
            socketFd = connectSocket(host, port)
            if (socketFd < 0) {
                return@withContext Result.failure(Exception(AppStrings.ui_sftp_server_connection_failed))
            }
            session = libssh2_session_init_ex(null, null, null, null)
                ?: return@withContext Result.failure(Exception(AppStrings.ui_sftp_session_initialization_failed))
            detectLibssh2Stub(session)?.let { message ->
                LogKit.w(message)
                return@withContext Result.failure(IllegalStateException(message))
            }
            libssh2_session_set_blocking(session, 1)
            if (libssh2_session_handshake(session, socketFd) != 0) {
                return@withContext Result.failure(
                    Exception(sessionError(session, AppStrings.ui_sftp_handshake_failed))
                )
            }
            if (sftpExtras.knownHosts.isNotBlank()) {
                knownHostsPath = writeTempFile("known_hosts", sftpExtras.knownHosts)
                knownHosts = libssh2_knownhost_init(session)
                if (knownHosts == null) {
                    return@withContext Result.failure(
                        Exception(AppStrings.ui_sftp_known_hosts_initialization_failed)
                    )
                }
                val readRc = libssh2_knownhost_readfile(
                    knownHosts,
                    knownHostsPath,
                    LIBSSH2_KNOWNHOST_FILE_OPENSSH
                )
                if (readRc < 0) {
                    return@withContext Result.failure(Exception(AppStrings.ui_sftp_known_hosts_read_failed))
                }
                memScoped {
                    val keyLen = alloc<size_tVar>()
                    val keyType = alloc<IntVar>()
                    val hostKey = libssh2_session_hostkey(session, keyLen.ptr, keyType.ptr)
                        ?: return@withContext Result.failure(Exception(AppStrings.ui_sftp_host_key_fetch_failed))
                    val hostCstr = host.cstr
                    val check = libssh2_knownhost_checkp(
                        knownHosts,
                        hostCstr,
                        port,
                        hostKey,
                        keyLen.value,
                        LIBSSH2_KNOWNHOST_TYPE_PLAIN or LIBSSH2_KNOWNHOST_KEYENC_RAW,
                        null
                    )
                    if (check != LIBSSH2_KNOWNHOST_CHECK_MATCH) {
                        return@withContext Result.failure(
                            Exception(AppStrings.ui_sftp_known_hosts_verification_failed)
                        )
                    }
                }
            }
            val authResult = if (sftpExtras.privateKey.isNotBlank()) {
                privateKeyPath = writeTempFile("sftp_key", sftpExtras.privateKey)
                libssh2_userauth_publickey_fromfile_ex(
                    session,
                    network.username,
                    network.username.length.toUInt(),
                    null,
                    privateKeyPath,
                    null
                )
            } else {
                libssh2_userauth_password_ex(
                    session,
                    network.username,
                    network.username.length.toUInt(),
                    network.password,
                    network.password.length.toUInt(),
                    null
                )
            }
            if (authResult != 0) {
                return@withContext Result.failure(
                    Exception(sessionError(session, AppStrings.ui_sftp_authentication_failed))
                )
            }
            sftp = libssh2_sftp_init(session)
                ?: return@withContext Result.failure(
                    Exception(sessionError(session, AppStrings.ui_sftp_initialization_failed))
                )
            LogKit.i(AppStrings.ui_sftp_connected_arg0_arg1.format(arg0 = host, arg1 = port.toString()))
            block(sftp)
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_sftp_operation_failed_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        } finally {
            if (sftp != null) {
                libssh2_sftp_shutdown(sftp)
            }
            if (knownHosts != null) {
                libssh2_knownhost_free(knownHosts)
            }
            if (session != null) {
                libssh2_session_disconnect_ex(session, 0, null, null)
                libssh2_session_free(session)
            }
            if (socketFd >= 0) {
                close(socketFd)
            }
            libssh2_exit()
            knownHostsPath?.let { removeTempFile(it) }
            privateKeyPath?.let { removeTempFile(it) }
        }
    }

    private fun connectSocket(host: String, port: Int): Int = memScoped {
        val hints = alloc<addrinfo>()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        val result = alloc<CPointerVar<addrinfo>>()
        if (getaddrinfo(host, port.toString(), hints.ptr, result.ptr) != 0) {
            return@memScoped -1
        }
        var addr = result.value
        while (addr != null) {
            val sock = socket(addr.pointed.ai_family, addr.pointed.ai_socktype, addr.pointed.ai_protocol)
            if (sock >= 0) {
                val conn = connect(sock, addr.pointed.ai_addr, addr.pointed.ai_addrlen)
                if (conn == 0) {
                    freeaddrinfo(result.value)
                    return@memScoped sock
                }
                close(sock)
            }
            addr = addr.pointed.ai_next
        }
        freeaddrinfo(result.value)
        -1
    }

    private fun detectLibssh2Stub(session: CPointer<LIBSSH2_SESSION>?): String? {
        val marker = sessionError(session, "").trim()
        if (!marker.contains("stub", ignoreCase = true)) return null
        return AppStrings.ui_it_detected_that_ios_libssh2_stub_placeholder_library_please
    }

    private fun sessionError(session: CPointer<LIBSSH2_SESSION>?, fallback: String): String {
        if (session == null) return fallback
        return memScoped {
            val errorPtr = alloc<CPointerVar<ByteVar>>()
            val errorLen = alloc<IntVar>()
            libssh2_session_last_error(session, errorPtr.ptr, errorLen.ptr, 0)
            errorPtr.value?.toKString() ?: fallback
        }
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return network.pathSeparator
        val normalized = path.replace("\\", "/")
        requireSafeNetworkWritePath(normalized)
        return normalized
    }

    private fun joinPath(base: String, name: String): String {
        require(!isUnsafeNetworkPathSegment(name)) { AppStrings.ui_remote_path_invalid }
        val normalized = if (base.endsWith("/")) base else "$base/"
        return normalized + name
    }

    private fun writeTempFile(prefix: String, content: String): String {
        val tempDir = NSTemporaryDirectory()
        val fileName = "${prefix}_${NSUUID().UUIDString}"
        val fullPath = if (tempDir.endsWith("/")) tempDir + fileName else "$tempDir/$fileName"
        val fd = open(fullPath, O_WRONLY or O_CREAT or O_TRUNC, 384)
        if (fd >= 0) {
            val bytes = content.encodeToByteArray()
            bytes.usePinned { pinned ->
                var offset = 0
                while (offset < bytes.size) {
                    val written = write(fd, pinned.addressOf(offset), (bytes.size - offset).toULong())
                    if (written <= 0) break
                    offset += written.toInt()
                }
            }
            close(fd)
        }
        return fullPath
    }

    private fun removeTempFile(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    private fun posixError(message: String): Exception {
        val detail = strerror(errno)?.toKString() ?: "unknown"
        return Exception("$message: $detail")
    }
}
