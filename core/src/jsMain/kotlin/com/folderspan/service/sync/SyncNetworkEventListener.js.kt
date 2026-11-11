package com.folderspan.service.sync

import org.w3c.dom.events.EventListener

internal actual fun createSyncNetworkEventListener(onChange: () -> Unit): EventListener = EventListener { onChange() }
