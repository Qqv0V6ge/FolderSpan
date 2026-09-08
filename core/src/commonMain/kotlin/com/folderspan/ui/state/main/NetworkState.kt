package com.folderspan.ui.state.main

import strings.AppStrings

import androidx.compose.runtime.mutableStateListOf
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.data.main.network.NetworkEntry
import com.folderspan.data.main.network.NetworkShare
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

class NetworkState(private val database: FolderSpanDatabase) {
    private fun defaultPathSeparator(protocol: String): String {
        return if (protocol == "SMB") "\\" else "/"
    }

    val entries = mutableStateListOf<NetworkEntry>()
    val networks: List<Network>
        get() = entries.map { item -> item.network }
    val connectedEntries = mutableStateListOf<NetworkEntry>()
    val connectedNetworks: List<Network>
        get() = connectedEntries.map { item -> item.network }

    private var hasLoaded = false
    private var nextSessionId = -1L

    private fun nextSessionId(): Long = nextSessionId--

    suspend fun loadPersisted() {
        if (hasLoaded) return
        hasLoaded = true
        reloadPersisted()
    }

    suspend fun reloadPersisted() {
        val saved = try {
            withContext(Dispatchers.Default) {
                database.networkDriveQueries.selectAll().executeAsListAwait()
            }
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_failed_load_network_disk_arg0.format(arg0 = (e.message).toString()))
            return
        }
        entries.removeAll { item -> item.isPersisted }
        connectedEntries.removeAll { item -> item.isPersisted }
        if (saved.isEmpty()) return
        val newItems = saved.map { item ->
            val extras = decodeExtras(item.extras)
            val password = decodePassword(item.password)
            val network = if (item.protocol == NetworkShare.PROTOCOL) {
                NetworkShare(
                    name = item.name,
                    baseUrl = item.host,
                    password = password,
                    pathSeparator = item.pathSeparator.ifBlank { "/" },
                    username = item.username,
                    pinned = item.pinned != 0L,
                )
            } else {
                Network(
                    name = item.name,
                    pathSeparator = item.pathSeparator.ifBlank { defaultPathSeparator(item.protocol) },
                    protocol = item.protocol,
                    host = item.host,
                    username = item.username,
                    password = password,
                    pinned = item.pinned != 0L,
                    extras = extras
                )
            }
            NetworkEntry(
                id = item.id,
                network = network,
                isPersisted = true
            )
        }
        entries.addAll(newItems)
    }

    suspend fun addNetwork(network: Network, persist: Boolean) {
        val entry = if (persist) {
            val id = insertNetwork(network)
            if (id != null) {
                NetworkEntry(id = id, network = network, isPersisted = true)
            } else {
                NetworkEntry(id = nextSessionId(), network = network, isPersisted = false)
            }
        } else {
            NetworkEntry(id = nextSessionId(), network = network, isPersisted = false)
        }
        entries.add(entry)
        if (entry.isPersisted) {
            SyncSnapshotChangeNotifier.onNetworkConfigurationChanged()
        }
    }

    fun connectEntry(entry: NetworkEntry) {
        if (connectedEntries.any { item -> item.network == entry.network }) return
        connectedEntries.add(entry)
    }

    fun snapshotEntries(): List<NetworkEntry> = entries.toList()

    fun snapshotConnectedEntryIds(): Set<Long> = connectedEntries.mapTo(mutableSetOf()) { item -> item.id }

    suspend fun connectValidated(entryId: Long): Result<NetworkEntry> = runCatching {
        loadPersisted()
        val entry = entries.firstOrNull { item -> item.id == entryId }
            ?: throw NoSuchElementException("network entry was not found")
        entry.network.getList(entry.network.pathSeparator).getOrThrow()
        connectEntry(entry)
        entry
    }

    private fun updateConnectedEntry(oldEntry: NetworkEntry, newEntry: NetworkEntry) {
        val index = connectedEntries.indexOfFirst { item -> item.id == oldEntry.id }
        if (index >= 0) {
            connectedEntries[index] = newEntry
        }
    }

    private fun removeConnectedEntry(entry: NetworkEntry) {
        connectedEntries.removeAll { item -> item.id == entry.id }
    }

    suspend fun updatePersisted(entry: NetworkEntry, persist: Boolean) {
        val index = entries.indexOf(entry)
        if (index < 0) return
        if (entry.isPersisted == persist) return

        val updated = if (persist) {
            val id = insertNetwork(entry.network) ?: return
            entry.copy(id = id, isPersisted = true)
        } else {
            deleteNetwork(entry)
            entry.copy(id = nextSessionId(), isPersisted = false)
        }
        entries[index] = updated
        updateConnectedEntry(entry, updated)
        SyncSnapshotChangeNotifier.onNetworkConfigurationChanged()
    }

    suspend fun removeEntry(entry: NetworkEntry) {
        if (entry.isPersisted) {
            deleteNetwork(entry)
        }
        entries.remove(entry)
        removeConnectedEntry(entry)
        if (entry.isPersisted) {
            SyncSnapshotChangeNotifier.onNetworkConfigurationChanged()
        }
    }

    suspend fun updateEntry(entry: NetworkEntry, network: Network) {
        val index = entries.indexOf(entry)
        if (index < 0) return
        if (entry.isPersisted) {
            try {
                withContext(Dispatchers.Default) {
                    database.networkDriveQueries.updateById(
                        name = network.name,
                        protocol = network.protocol,
                        host = network.host,
                        username = network.username,
                        password = encodePassword(network.password),
                        pathSeparator = network.pathSeparator,
                        pinned = if (network.pinned) 1L else 0L,
                        extras = encodeExtras(network),
                        id = entry.id
                    ).awaitDatabaseReady()
                }
            } catch (e: Exception) {
                LogKit.w(AppStrings.ui_failed_update_network_disk_arg0.format(arg0 = (e.message).toString()))
            }
        }
        val updated = entry.copy(network = network)
        entries[index] = updated
        updateConnectedEntry(entry, updated)
        if (entry.isPersisted) {
            SyncSnapshotChangeNotifier.onNetworkConfigurationChanged()
        }
    }

    private suspend fun insertNetwork(network: Network): Long? {
        return try {
            withContext(Dispatchers.Default) {
                database.networkDriveQueries.insert(
                    name = network.name,
                    protocol = network.protocol,
                    host = network.host,
                    username = network.username,
                    password = encodePassword(network.password),
                    pathSeparator = network.pathSeparator,
                    pinned = if (network.pinned) 1L else 0L,
                    extras = encodeExtras(network)
                ).awaitDatabaseReady()
                database.networkDriveQueries.lastInsertRowId().executeAsOneAwait()
            }
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_failed_save_network_disk_arg0.format(arg0 = (e.message).toString()))
            null
        }
    }

    private suspend fun deleteNetwork(entry: NetworkEntry) {
        try {
            withContext(Dispatchers.Default) {
                database.networkDriveQueries.deleteById(entry.id).awaitDatabaseReady()
            }
        } catch (e: Exception) {
            LogKit.w(AppStrings.ui_failed_delete_network_disk_arg0.format(arg0 = (e.message).toString()))
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun encodeExtras(network: Network): ByteArray {
        val extras = network.extras
        return if (extras == NetworkDriveExtras()) {
            ByteArray(0)
        } else {
            val plain = ProtoBuf.encodeToByteArray(NetworkDriveExtras.serializer(), extras)
            SymmetricCrypto.encrypt(plain)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeExtras(bytes: ByteArray): NetworkDriveExtras {
        if (bytes.isEmpty()) return NetworkDriveExtras()
        val decrypted = SymmetricCrypto.decrypt(bytes)
        return ProtoBuf.decodeFromByteArray(NetworkDriveExtras.serializer(), decrypted)
    }

    private fun encodePassword(password: String): String {
        if (password.isBlank()) return ""
        return SymmetricCrypto.encrypt(password)
    }

    private fun decodePassword(password: String): String {
        if (password.isBlank()) return ""
        return SymmetricCrypto.decrypt(password)
    }
}
