package com.folderspan.notification

import com.mmk.kmpnotifier.notification.NotificationAction
import kotlin.js.JsName
import kotlin.js.Promise

@JsName("Notification")
external class JsNotification(title: String, options: dynamic = definedExternally) {
    var onclick: ((dynamic) -> Unit)?
    fun close()

    companion object {
        fun requestPermission(): Promise<String>
        val permission: String
    }
}

fun initializeWebNotificationBackend() {
    LocalNotifier.installPlatformBackend(JsWebLocalNotificationBackend)
}

private object JsWebLocalNotificationBackend : LocalNotificationBackend {
    private val activeNotifications = mutableMapOf<Int, JsNotification>()

    override fun notify(
        id: Int,
        title: String,
        body: String,
        payloadData: NotificationPayload,
        actions: List<NotificationAction>,
        onAction: (String, NotificationPayload) -> Unit,
        onClick: (NotificationPayload) -> Unit,
    ): Boolean {
        if (!isSupported() || JsNotification.permission != PERMISSION_GRANTED) return false
        remove(id)
        val options = js("({})")
        options.body = body
        options.tag = id.toString()
        val notification = runCatching { JsNotification(title, options) }.getOrNull() ?: return false
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

    private fun isSupported(): Boolean = js("typeof Notification !== 'undefined'") as Boolean
}

private const val PERMISSION_GRANTED = "granted"
