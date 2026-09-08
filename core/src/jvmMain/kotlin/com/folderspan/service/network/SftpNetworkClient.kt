package com.folderspan.service.network

import strings.AppStrings

import com.folderspan.PlatformType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.network.Network
import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.utils.LogKit
import com.folderspan.utils.NetworkHostUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.config.hosts.KnownHostEntry
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.sftp.client.extensions.CopyFileExtension
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.Security
import kotlin.time.Instant

internal class SftpNetworkClient(private val network: Network) : NetworkClient, ChunkReadableNetworkClient {
    private suspend fun <T> withClient(block: suspend (SftpClient) -> T): Result<T> = withContext(Dispatchers.IO) {
        ensureBouncyCastleProvider()
        var client: SshClient? = null
        var session: ClientSession? = null
        var sftp: SftpClient? = null
        val resolved = NetworkHostUtils.resolveHostPort(network.host, 22)
        val host = resolved.host
        val port = resolved.port ?: 22
        val sftpExtras = network.extras.sftp
        val authHint = if (sftpExtras.privateKey.isNotBlank()) "key" else "password"
        val knownHostsHint = if (sftpExtras.knownHosts.isNotBlank()) "known_hosts=provided" else "known_hosts=missing"
        LogKit.i(AppStrings.ui_sftp_connection_arg0_arg1_user_arg2_auth_arg3_arg4.format(arg0 = host, arg1 = (port).toString(), arg2 = network.username, arg3 = authHint, arg4 = knownHostsHint))
        try {
            client = SshClient.setUpDefaultClient()
            client.start()
            client.serverKeyVerifier = resolveSftpServerKeyVerifier(
                knownHosts = sftpExtras.knownHosts,
                host = host,
                port = port,
            )
            session = client.connect(network.username, host, port)
                .verify(10_000)
                .session

            if (sftpExtras.privateKey.isNotBlank()) {
                val keyPairs = loadPrivateKeyPairs(sftpExtras.privateKey, session)
                keyPairs.forEach { keyPair ->
                    session!!.addPublicKeyIdentity(keyPair)
                }
            } else {
                session!!.addPasswordIdentity(network.password)
            }

            session!!.auth().verify(10_000)
            sftp = SftpClientFactory.instance().createSftpClient(session)
            LogKit.i(AppStrings.ui_sftp_connected_arg0_arg1.format(arg0 = host, arg1 = (port).toString()))
            Result.success(block(sftp!!))
        } catch (e: ExceptionInInitializerError) {
            LogKit.w(AppStrings.ui_sftp_initialization_failed_arg0.format(arg0 = (e.cause?.message ?: e.message).toString()), e)
            Result.failure(e)
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_sftp_operation_failed_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        } finally {
            runCatching { sftp?.close() }
            runCatching { session?.close() }
            runCatching { client?.stop() }
        }
    }

    private fun ensureBouncyCastleProvider() {
        if (PlatformType != DeviceType.Android) return
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing != null && existing.javaClass.name == BouncyCastleProvider::class.java.name) {
            return
        }
        runCatching { Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME) }
        runCatching {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }.onFailure { error ->
            LogKit.w(AppStrings.ui_sftp_injection_into_bouncycastle_failed_arg0.format(arg0 = (error.message).toString()), error)
        }
    }

    override suspend fun list(
        path: String,
        requestId: String?,
        batchId: String?
    ): Result<List<NetworkFileEntry>> {
        return withClient { client ->
            val target = normalizePath(path)
            val entries = client.readDir(target)
                .filterNot { entry -> isUnsafeNetworkPathSegment(entry.filename) }
                .map { entry ->
                    val attrs = entry.attributes
                    val modified = parseFileTime(attrs.modifyTime)
                    NetworkFileEntry(
                        name = entry.filename,
                        path = joinPath(target, entry.filename),
                        isDirectory = attrs.isDirectory,
                        size = if (attrs.isDirectory) -1L else attrs.size,
                        createdDate = modified,
                        updatedDate = modified,
                        isHidden = entry.filename.startsWith(".")
                    )
                }
            LogKit.i(AppStrings.ui_sftp_list_arg0_count_arg1.format(arg0 = target, arg1 = (entries.size).toString()))
            entries
        }
    }

    override suspend fun download(
        remotePath: String,
        localPath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> {
        return withClient { client ->
            openLocalSinkNoFollow(localPath).use { channel ->
                client.read(normalizePath(remotePath)).use { input ->
                    java.nio.channels.Channels.newOutputStream(channel).use { output ->
                        copyStreamWithProgress(input, output, size, onProgress)
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
        return withClient { client ->
            client.read(normalizePath(remotePath)).use { input ->
                relayStreamByChunks(input, size.coerceAtLeast(0L), onChunk).getOrElse { error ->
                    throw error
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
        return openLocalSourceNoFollow(localPath).use { channel ->
            withClient { client ->
                client.write(normalizePath(remotePath)).use { output ->
                    java.nio.channels.Channels.newInputStream(channel).use { input ->
                        copyStreamWithProgress(input, output, size, onProgress)
                    }
                }
                true
            }
        }
    }

    override suspend fun uploadFromSource(
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit,
        readChunk: suspend () -> ByteArray?,
    ): Result<Boolean> {
        return withClient { client ->
            client.write(normalizePath(remotePath)).use { output ->
                pumpUploadChunks(
                    size = size,
                    onProgress = onProgress,
                    readChunk = readChunk,
                    writeChunk = { chunk -> output.write(chunk) },
                ).getOrElse { error -> throw error }
            }
            true
        }
    }

    override suspend fun copyFile(sourcePath: String, targetPath: String): Result<Boolean> {
        return withClient { client ->
            copyFileWithSftpExtension(
                extension = client.getExtension(CopyFileExtension::class.java),
                sourcePath = normalizePath(sourcePath),
                targetPath = normalizePath(targetPath),
            )
            true
        }
    }

    override suspend fun rename(path: String, newPath: String): Result<Boolean> {
        return withClient { client ->
            client.rename(normalizePath(path), normalizePath(newPath))
            true
        }
    }

    override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> {
        return withClient { client ->
            if (isDirectory) {
                client.rmdir(normalizePath(path))
            } else {
                client.remove(normalizePath(path))
            }
            true
        }
    }

    override suspend fun createFolder(path: String): Result<Boolean> {
        return withClient { client ->
            client.mkdir(normalizePath(path))
            true
        }
    }

    override suspend fun createFile(path: String): Result<Boolean> {
        return withClient { client ->
            client.write(normalizePath(path)).use { }
            true
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

    private fun loadPrivateKeyPairs(
        privateKey: String,
        session: ClientSession?
    ): Iterable<KeyPair> {
        val content = privateKey.trim()
        if (content.isBlank()) return emptyList()
        val resource = NamedResource.ofName("sftp-private-key")
        return ByteArrayInputStream(content.toByteArray())
            .use { SecurityUtils.loadKeyPairIdentities(session, resource, it, null) }
    }

    private fun parseFileTime(fileTime: Any?): Long {
        if (fileTime == null) return 0L
        val text = fileTime.toString().trim()
        if (text.isEmpty()) return 0L
        return runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrDefault(0L)
    }
}

internal fun resolveSftpServerKeyVerifier(
    knownHosts: String,
    host: String,
    port: Int,
): ServerKeyVerifier {
    if (knownHosts.isBlank()) return RejectAllServerKeyVerifier.INSTANCE
    val entries = loadSftpKnownHostEntries(knownHosts)
    require(entries.isNotEmpty()) { AppStrings.ui_sftp_known_hosts_unparseable_connection_rejected }
    return buildSftpKnownHostsVerifier(entries, host, port)
}

private fun loadSftpKnownHostEntries(knownHosts: String): List<KnownHostEntry> {
    return runCatching {
        ByteArrayInputStream(knownHosts.trim().toByteArray())
            .use { KnownHostEntry.readKnownHostEntries(it, true) }
    }.getOrDefault(emptyList())
}

private fun buildSftpKnownHostsVerifier(
    entries: List<KnownHostEntry>,
    host: String,
    port: Int,
): ServerKeyVerifier {
    return ServerKeyVerifier { session, _, key ->
        entries
            .filter { entry -> entry.isHostMatch(host, port) }
            .any { entry ->
                val keyEntry = entry.keyEntry ?: return@any false
                val resolved = runCatching { keyEntry.resolvePublicKey(session, null) }.getOrNull()
                resolved != null && KeyUtils.compareKeys(resolved, key)
            }
    }
}

internal fun copyFileWithSftpExtension(
    extension: CopyFileExtension?,
    sourcePath: String,
    targetPath: String,
) {
    if (extension == null || !extension.isSupported) {
        throw NetworkUnsupportedException(AppStrings.ui_sftp_server_side_copy_unsupported)
    }
    try {
        extension.copyFile(sourcePath, targetPath, true)
    } catch (error: SftpException) {
        if (error.status == SftpConstants.SSH_FX_OP_UNSUPPORTED) {
            throw NetworkUnsupportedException(
                "${AppStrings.ui_sftp_server_rejected_copy_file_extension}: ${error.message}"
            )
        }
        throw error
    }
}
