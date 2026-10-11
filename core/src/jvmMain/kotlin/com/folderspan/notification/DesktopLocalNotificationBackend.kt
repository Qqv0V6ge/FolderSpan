package com.folderspan.notification

import com.mmk.kmpnotifier.notification.NotificationAction
import dev.nucleusframework.notification.AuthorizationOption
import dev.nucleusframework.notification.NotificationCenter
import dev.nucleusframework.notification.common.NotificationManager
import dev.nucleusframework.notification.common.NotificationResult
import dev.nucleusframework.notification.common.notification
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Window
import java.util.concurrent.ConcurrentHashMap

fun initializeDesktopNotificationBackend() {
    val gateway = NucleusDesktopSystemNotificationGateway()
    gateway.initialize()
    LocalNotifier.installPlatformBackend(DesktopLocalNotificationBackend(gateway))
}

internal data class DesktopSystemNotification(
    val title: String,
    val body: String,
    val actions: List<DesktopSystemNotificationAction>,
    val onActivated: () -> Unit,
    val onDismissed: () -> Unit,
    val onFailed: () -> Unit,
)

internal data class DesktopSystemNotificationAction(
    val title: String,
    val onClick: () -> Unit,
)

internal fun interface DesktopSystemNotificationHandle {
    fun dismiss()
}

internal interface DesktopSystemNotificationGateway {
    fun initialize()

    fun isAvailable(): Boolean

    fun post(notification: DesktopSystemNotification): DesktopSystemNotificationHandle?
}

internal class DesktopLocalNotificationBackend(
    private val gateway: DesktopSystemNotificationGateway,
    private val dispatchClick: ((() -> Unit) -> Unit) = ::dispatchDesktopNotificationClick,
) : LocalNotificationBackend {
    private data class ActiveNotification(
        val token: Any,
        val handle: DesktopSystemNotificationHandle,
    )

    private val activeNotifications = ConcurrentHashMap<Int, ActiveNotification>()

    override fun notify(
        id: Int,
        title: String,
        body: String,
        payloadData: NotificationPayload,
        actions: List<NotificationAction>,
        onAction: (String, NotificationPayload) -> Unit,
        onClick: (NotificationPayload) -> Unit,
    ): Boolean {
        if (!gateway.isAvailable()) return false
        remove(id)

        val token = Any()
        val handle = gateway.post(
            DesktopSystemNotification(
                title = title,
                body = body,
                actions = actions.map { action ->
                    DesktopSystemNotificationAction(title = action.title) {
                        if (removeIfActive(id, token)) {
                            dispatchClick { onAction(action.id, payloadData) }
                        }
                    }
                },
                onActivated = {
                    if (removeIfActive(id, token)) {
                        dispatchClick { onClick(payloadData) }
                    }
                },
                onDismissed = { removeIfActive(id, token) },
                onFailed = { removeIfActive(id, token) },
            )
        ) ?: return false

        activeNotifications[id] = ActiveNotification(token, handle)
        return true
    }

    override fun remove(id: Int) {
        activeNotifications.remove(id)?.handle?.dismiss()
    }

    private fun removeIfActive(id: Int, token: Any): Boolean {
        val active = activeNotifications[id] ?: return false
        return active.token === token && activeNotifications.remove(id, active)
    }
}

private class NucleusDesktopSystemNotificationGateway(
    private val isMacOs: Boolean = System.getProperty("os.name", "").contains("mac", ignoreCase = true),
) : DesktopSystemNotificationGateway {
    @Volatile
    private var macAuthorizationDenied = false

    override fun initialize() {
        runCatching { NotificationManager.initialize() }
        if (isMacOs) {
            runCatching {
                if (NotificationCenter.isAvailable) {
                    NotificationCenter.requestAuthorization(setOf(AuthorizationOption.ALERT)) { granted, _ ->
                        macAuthorizationDenied = !granted
                    }
                }
            }.onFailure {
                macAuthorizationDenied = true
            }
        }
    }

    override fun isAvailable(): Boolean {
        return !macAuthorizationDenied && runCatching { NotificationManager.isAvailable() }.getOrDefault(false)
    }

    override fun post(notification: DesktopSystemNotification): DesktopSystemNotificationHandle? {
        return runCatching {
            when (
                val result = notification(
                    title = notification.title,
                    message = notification.body,
                    onActivated = notification.onActivated,
                    onDismissed = { notification.onDismissed() },
                    onFailed = notification.onFailed,
                ) {
                    notification.actions.forEach { action ->
                        button(action.title, action.onClick)
                    }
                }.send()
            ) {
                is NotificationResult.Success -> DesktopSystemNotificationHandle {
                    result.handle.dismiss()
                }
                is NotificationResult.Failure -> null
            }
        }.getOrNull()
    }
}

private fun dispatchDesktopNotificationClick(onClick: () -> Unit) {
    EventQueue.invokeLater {
        Window.getWindows()
            .filter { window -> window.isDisplayable }
            .forEach { window ->
                window.isVisible = true
                window.toFront()
                window.requestFocus()
            }
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().requestForeground(true)
            }
        }
        onClick()
    }
}
