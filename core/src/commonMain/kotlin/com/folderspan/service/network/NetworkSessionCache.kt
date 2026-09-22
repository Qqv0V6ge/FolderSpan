package com.folderspan.service.network

import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkProtocol
import com.folderspan.utils.NetworkHostUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import strings.AppStrings

internal data class NetworkSessionKey(
    private val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    private val password: String,
    private val extras: Any,
    private val hostKeyTrust: SftpHostKeyTrust?,
) {
    override fun toString(): String = "$protocol [$host]:$port"
}

internal fun networkSessionKey(
    network: Network,
    defaultPort: Int,
    trust: SftpHostKeyTrust? = null,
): NetworkSessionKey {
    val endpoint = NetworkHostUtils.resolveHostPort(network.host, defaultPort)
    val extras = when (network.protocol) {
        NetworkProtocol.FTP.name -> network.extras.ftp
        NetworkProtocol.SFTP.name -> network.extras.sftp
        NetworkProtocol.SMB.name -> network.extras.smb
        else -> error(AppStrings.ui_connection_failed)
    }
    return NetworkSessionKey(
        network.protocol, endpoint.host, endpoint.port ?: defaultPort,
        network.username, network.password, extras, trust,
    )
}

/** 只合并相同配置的认证；业务操作在锁外执行，允许流式传输回调再次使用同一会话。 */
internal class NetworkSessionCache<T : Any>(
    private val isOpen: (T) -> Boolean,
    private val close: suspend (T) -> Unit,
    private val isConnectionFailure: (Throwable) -> Boolean = { false },
    private val idleTimeoutMillis: Long = 30_000,
    private val exclusive: Boolean = false,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private class Entry<T> {
        val mutex = Mutex()
        var connection: T? = null
        var failure: Throwable? = null
        var users = 0
        var retired = false
        var idleJob: Job? = null
    }

    private val mutex = Mutex()
    private val entries = mutableMapOf<NetworkSessionKey, MutableList<Entry<T>>>()

    suspend fun <R> use(key: NetworkSessionKey, connect: suspend () -> T, block: suspend (T) -> R): R {
        repeat(2) {
            val entry = mutex.withLock {
                val sessions = entries.getOrPut(key) { mutableListOf() }
                val entry = sessions.firstOrNull { !exclusive || it.users == 0 }
                    ?: Entry<T>().also { sessions += it }
                entry.also {
                    it.idleJob?.cancel()
                    it.users++
                }
            }
            try {
                val connection = entry.mutex.withLock {
                    entry.failure?.let {
                        // 发起认证的任务取消后，其他等待者仍可重新建立自己的会话。
                        if (it is CancellationException) return@withLock null
                        throw it
                    }
                    entry.connection ?: try {
                        currentCoroutineContext().ensureActive()
                        connect().also { entry.connection = it }
                    } catch (error: Throwable) {
                        entry.failure = error
                        retire(key, entry)
                        throw error
                    }
                } ?: return@repeat
                currentCoroutineContext().ensureActive()
                if (!isOpen(connection)) {
                    retire(key, entry)
                    return@repeat
                }
                try {
                    return block(connection)
                } catch (error: Throwable) {
                    if (isConnectionFailure(error) || !isOpen(connection)) retire(key, entry)
                    throw error
                }
            } finally {
                release(key, entry)
            }
        }
        error(AppStrings.ui_connection_failed)
    }

    // 调用方持有 mutex；只移除当前会话，不影响同一配置下仍在使用的其他连接。
    private fun removeEntry(key: NetworkSessionKey, entry: Entry<T>) {
        entries[key]?.let { sessions ->
            sessions.remove(entry)
            if (sessions.isEmpty()) entries.remove(key)
        }
    }

    private suspend fun retire(key: NetworkSessionKey, entry: Entry<T>) = withContext(NonCancellable) {
        mutex.withLock {
            removeEntry(key, entry)
            entry.retired = true
        }
    }

    private suspend fun release(key: NetworkSessionKey, entry: Entry<T>) = withContext(NonCancellable) {
        val unused = mutex.withLock {
            entry.users--
            if (entry.users != 0) return@withLock null
            if (entry.retired) return@withLock entry.connection
            entry.idleJob = scope.launch {
                delay(idleTimeoutMillis)
                val expired = mutex.withLock {
                    if (entry.users != 0 || entries[key]?.contains(entry) != true) return@withLock null
                    removeEntry(key, entry)
                    entry.retired = true
                    entry.connection
                }
                if (expired != null) withContext(NonCancellable) { close(expired) }
            }
            null
        }
        if (unused != null) close(unused)
    }
}
