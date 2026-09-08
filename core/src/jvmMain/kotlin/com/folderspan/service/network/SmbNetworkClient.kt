package com.folderspan.service.network

import strings.AppStrings

import com.folderspan.data.main.network.Network
import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.utils.LogKit
import com.folderspan.utils.NetworkHostUtils
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

internal class SmbNetworkClient(private val network: Network) : NetworkClient, ChunkReadableNetworkClient {
    private suspend fun <T> withShare(block: suspend (DiskShare) -> T): Result<T> = withContext(Dispatchers.IO) {
        val client = SMBClient(SmbConfig.builder().withSigningRequired(true).build())
        var connection: Connection? = null
        var session: Session? = null
        var share: DiskShare? = null
        val resolved = NetworkHostUtils.resolveHostPort(network.host, 445)
        val host = resolved.host
        val port = resolved.port ?: 445
        val smbExtras = network.extras.smb
        val shareNamePreview = smbExtras.share.ifBlank { "<empty>" }
        val domainPreview = smbExtras.domain.ifBlank { "default" }
        LogKit.i(AppStrings.ui_smb_connection_arg0_arg1_share_arg2_domain_arg3_user.format(arg0 = host, arg1 = (port).toString(), arg2 = shareNamePreview, arg3 = domainPreview, arg4 = network.username))
        try {
            connection = client.connect(host, port)
            val domain = smbExtras.domain.ifBlank { null }
            val auth = AuthenticationContext(network.username, network.password.toCharArray(), domain)
            session = connection.authenticate(auth)
            val shareName = smbExtras.share.ifBlank { throw IllegalStateException(AppStrings.ui_smb_share_name_cannot_be_empty) }
            share = session.connectShare(shareName) as DiskShare
            LogKit.i(AppStrings.ui_smb_connected_arg0_arg1_share_arg2.format(arg0 = (host), arg1 = (port).toString(), arg2 = (shareName)))
            Result.success(block(share))
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_smb_operation_failed_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        } finally {
            runCatching { share?.close() }
            runCatching { session?.close() }
            runCatching { connection?.close() }
            runCatching { client.close() }
        }
    }

    override suspend fun list(
        path: String,
        requestId: String?,
        batchId: String?
    ): Result<List<NetworkFileEntry>> {
        return withShare { share ->
            val target = normalizePath(path)
            val entries = share.list(target)
            val files = entries.filterNot { isUnsafeNetworkPathSegment(it.fileName) }
                .map { entry ->
                    val attrs = entry.fileAttributes
                    val isDir = attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    val isHidden = attrs and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L
                    NetworkFileEntry(
                        name = entry.fileName,
                        path = joinPath(path, entry.fileName),
                        isDirectory = isDir,
                        size = if (isDir) -1 else entry.endOfFile,
                        createdDate = entry.lastWriteTime.toEpochMillis(),
                        updatedDate = entry.lastWriteTime.toEpochMillis(),
                        isHidden = isHidden
                    )
                }
            LogKit.i(AppStrings.ui_smb_list_arg0_count_arg1.format(arg0 = target, arg1 = (files.size).toString()))
            files
        }
    }

    override suspend fun download(
        remotePath: String,
        localPath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> {
        return withShare { share ->
            val file = share.openFile(
                normalizePath(remotePath),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java)
            )
            file.use { opened ->
                opened.inputStream.use { input ->
                    openLocalSinkNoFollow(localPath).use { channel ->
                        java.nio.channels.Channels.newOutputStream(channel).use { output ->
                            copyStreamWithProgress(input, output, size, onProgress)
                        }
                    }
                }
            }
            true
        }
    }

    override suspend fun downloadByChunks(
        remotePath: String,
        size: Long,
        onChunk: suspend (chunk: ByteArray, totalBytes: Long) -> Result<Unit>,
    ): Result<Boolean> {
        return withShare { share ->
            val file = share.openFile(
                normalizePath(remotePath),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java)
            )
            file.use { opened ->
                opened.inputStream.use { input ->
                    relayStreamByChunks(input, size.coerceAtLeast(0L), onChunk).getOrElse { error ->
                        throw error
                    }
                }
            }
            true
        }
    }

    override suspend fun upload(
        localPath: String,
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> {
        return try {
            openLocalSourceNoFollow(localPath).use { channel ->
                withShare { share ->
                    val file = share.openFile(
                        normalizePath(remotePath),
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OVERWRITE_IF,
                        EnumSet.noneOf(SMB2CreateOptions::class.java)
                    )
                    file.use { opened ->
                        java.nio.channels.Channels.newInputStream(channel).use { input ->
                            opened.outputStream.use { output ->
                                copyStreamWithProgress(input, output, size, onProgress)
                            }
                        }
                    }
                    true
                }
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun uploadFromSource(
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit,
        readChunk: suspend () -> ByteArray?,
    ): Result<Boolean> {
        return try {
            withShare { share ->
                val file = share.openFile(
                    normalizePath(remotePath),
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.noneOf(SMB2CreateOptions::class.java)
                )
                file.use { opened ->
                    opened.outputStream.use { output ->
                        pumpUploadChunks(
                            size = size,
                            onProgress = onProgress,
                            readChunk = readChunk,
                            writeChunk = { chunk -> output.write(chunk) },
                        ).getOrElse { error -> throw error }
                    }
                }
                true
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun copyFile(sourcePath: String, targetPath: String): Result<Boolean> {
        return withShare { share ->
            val sourceFile = share.openFile(
                normalizePath(sourcePath),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            )
            sourceFile.use { source ->
                val targetFile = share.openFile(
                    normalizePath(targetPath),
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                )
                targetFile.use { target ->
                    try {
                        source.remoteCopyTo(target)
                    } catch (error: SMBApiException) {
                        if (
                            error.status == NtStatus.STATUS_NOT_SUPPORTED ||
                            error.status == NtStatus.STATUS_NOT_IMPLEMENTED
                        ) {
                            throw NetworkUnsupportedException(
                                AppStrings.ui_smb_server_does_not_support_remote_replication_arg0.format(arg0 = (error.message).toString())
                            )
                        }
                        throw error
                    }
                }
            }
            true
        }
    }

    override suspend fun rename(path: String, newPath: String): Result<Boolean> {
        return withShare { share ->
            val normalized = normalizePath(path)
            val target = normalizePath(newPath)
            if (share.folderExists(normalized)) {
                share.openDirectory(
                    normalized,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)
                ).use { dir ->
                    dir.rename(target)
                }
            } else {
                share.openFile(
                    normalized,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.noneOf(SMB2CreateOptions::class.java)
                ).use { file ->
                    file.rename(target)
                }
            }
            true
        }
    }

    override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> {
        return withShare { share ->
            if (isDirectory) {
                share.rmdir(normalizePath(path), true)
            } else {
                share.rm(normalizePath(path))
            }
            true
        }
    }

    override suspend fun createFolder(path: String): Result<Boolean> {
        return withShare { share ->
            share.mkdir(normalizePath(path))
            true
        }
    }

    override suspend fun createFile(path: String): Result<Boolean> {
        return withShare { share ->
            val file = share.openFile(
                normalizePath(path),
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.noneOf(SMB2CreateOptions::class.java)
            )
            file.use { }
            true
        }
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return ""
        val normalized = path.replace("/", "\\")
        val trimmed = normalized.trim('\\')
        requireSafeNetworkWritePath(trimmed)
        return trimmed
    }

    private fun joinPath(base: String, name: String): String {
        require(!isUnsafeNetworkPathSegment(name)) { AppStrings.ui_remote_path_invalid }
        val separator = network.pathSeparator
        val normalized = if (base.endsWith(separator)) base else base + separator
        return normalized + name
    }
}
