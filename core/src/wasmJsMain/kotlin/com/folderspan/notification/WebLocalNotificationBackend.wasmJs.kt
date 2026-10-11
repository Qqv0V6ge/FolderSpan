@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.notification

import com.mmk.kmpnotifier.notification.NotificationAction
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

private external interface BrowserNotificationOptions {
    var body: String?
    var tag: String?
}

private external interface BrowserNotification {
    var onclick: ((JsAny?) -> Unit)?
    fun close()
}

@JsFun("(title, options) => new Notification(title, options)")
private external fun createNotification(title: String, options: BrowserNotificationOptions): BrowserNotification

@JsFun("() => ({})")
private external fun createNotificationOptions(): BrowserNotificationOptions

@JsFun("() => typeof Notification !== 'undefined'")
private external fun isNotificationSupported(): Boolean

@JsFun("() => Notification.permission")
private external fun notificationPermission(): String

fun initializeWebNotificationBackend() {
    LocalNotifier.installPlatformBackend(WasmWebLocalNotificationBackend)
}

private object WasmWebLocalNotificationBackend : LocalNotificationBackend {
    private val activeNotifications = mutableMapOf<Int, BrowserNotification>()

    override fun notify(
        id: Int,
        title: String,
        body: String,
        payloadData: NotificationPayload,
        actions: List<NotificationAction>,
        onAction: (String, NotificationPayload) -> Unit,
        onClick: (NotificationPayload) -> Unit,
    ): Boolean {
        if (!isNotificationSupported() || notificationPermission() != PERMISSION_GRANTED) return false
        remove(id)
        val options = createNotificationOptions().apply {
            this.body = body
            tag = id.toString()
        }
        val notification = runCatching { createNotification(title, options) }.getOrNull() ?: return false
        notification.onclick = {
            remove(id)
            onClick(payloadData)
        }
        activeNotifications[id] = notification
        return true
    }

    override fun remove(id: Int) {
        activeNotifications.remove(id)?.close()
    }
}

private const val PERMISSION_GRANTED = "granted"
