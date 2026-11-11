package com.folderspan.service.sync

import kotlinx.browser.window
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import org.w3c.dom.events.EventListener

internal expect fun createSyncNetworkEventListener(onChange: () -> Unit): EventListener

internal actual val platformSupportsSyncConditions = false

internal actual fun observeSyncPlatformConditions(): Flow<SyncPlatformConditions> = callbackFlow {
    var revision = 0L
    fun publish() {
        trySend(SyncPlatformConditions(false,
            network = if (window.navigator.onLine) SyncNetworkType.Other else SyncNetworkType.Offline,
            networkRevision = revision++))
    }
    val listener = createSyncNetworkEventListener { publish() }
    window.addEventListener("online", listener)
    window.addEventListener("offline", listener)
    try { publish(); awaitClose() }
    finally {
        window.removeEventListener("online", listener)
        window.removeEventListener("offline", listener)
    }
}.buffer(Channel.CONFLATED)
