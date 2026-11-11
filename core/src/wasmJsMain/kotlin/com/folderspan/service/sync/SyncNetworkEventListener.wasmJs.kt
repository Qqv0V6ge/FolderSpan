@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.folderspan.service.sync

import org.w3c.dom.events.EventListener

internal actual fun createSyncNetworkEventListener(onChange: () -> Unit): EventListener = networkEventListener(onChange)

@JsFun("(onChange) => ({ handleEvent: () => onChange() })")
private external fun networkEventListener(onChange: () -> Unit): EventListener
