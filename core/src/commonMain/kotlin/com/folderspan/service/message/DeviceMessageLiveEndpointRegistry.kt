package com.folderspan.service.message

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DeviceMessageLiveEndpointRegistry {
    private val mutex = Mutex()
    private val endpoints = mutableMapOf<DeviceMessageEndpointIdentity, DeviceMessageLiveEndpoint>()
    private val mutableLiveEndpoints = MutableStateFlow<Set<DeviceMessageEndpointIdentity>>(emptySet())

    val liveEndpoints: StateFlow<Set<DeviceMessageEndpointIdentity>> = mutableLiveEndpoints.asStateFlow()

    suspend fun register(endpoint: DeviceMessageLiveEndpoint) {
        require(endpoint.identity.peerDeviceId.isNotBlank()) { "Peer device ID must not be blank" }
        require(endpoint.identity.connectionId.isNotBlank()) { "Connection ID must not be blank" }
        require(endpoint.client.endpointIdentity == endpoint.identity) { "Client endpoint identity does not match" }
        require(endpoint.client.maxChunkBytes > 0) { "Client chunk size must be positive" }
        if (!endpoint.isAuthorized()) {
            throw DeviceMessageTransferException(DeviceMessageTransferError.Unauthorized)
        }
        mutex.withLock {
            endpoints[endpoint.identity] = endpoint
            publishState()
        }
    }

    suspend fun unregister(identity: DeviceMessageEndpointIdentity): Boolean = mutex.withLock {
        val removed = endpoints.remove(identity) != null
        if (removed) publishState()
        removed
    }

    suspend fun unregisterConnection(peerDeviceId: String, connectionId: String): Int = mutex.withLock {
        val identities = endpoints.keys.filter { identity ->
            identity.peerDeviceId == peerDeviceId && identity.connectionId == connectionId
        }
        identities.forEach(endpoints::remove)
        if (identities.isNotEmpty()) publishState()
        identities.size
    }

    suspend fun isLiveAndAuthorized(identity: DeviceMessageEndpointIdentity): Boolean = mutex.withLock {
        endpoints[identity]?.isAuthorized?.invoke() == true
    }

    suspend fun <T> withLiveEndpoint(
        identity: DeviceMessageEndpointIdentity,
        block: suspend (DeviceMessageLiveEndpoint) -> T,
    ): T = mutex.withLock {
        val endpoint = endpoints[identity]
            ?: throw DeviceMessageTransferException(DeviceMessageTransferError.Offline)
        if (!endpoint.isAuthorized()) {
            throw DeviceMessageTransferException(DeviceMessageTransferError.Unauthorized)
        }
        block(endpoint)
    }

    private fun publishState() {
        mutableLiveEndpoints.value = endpoints.keys.toSet()
    }
}
