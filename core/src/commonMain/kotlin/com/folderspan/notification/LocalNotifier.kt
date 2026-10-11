package com.folderspan.notification

import com.folderspan.PlatformType
import com.folderspan.data.main.device.DeviceType
import com.mmk.kmpnotifier.KMPNotifier
import com.mmk.kmpnotifier.local.localNotifier
import com.mmk.kmpnotifier.notification.NotificationAction
import com.mmk.kmpnotifier.notification.PayloadData
import strings.AppStrings
import kotlin.time.Clock

typealias NotificationPayload = Map<String, String>
typealias NotificationClickListener = (NotificationPayload) -> Unit

object LocalNotifier {
    private val eventNormalizer = NotificationEventNormalizer()
    private var clickListeners = setOf<NotificationClickListener>()
    private var pendingClicks = emptyList<NotificationPayload>()
    private var listenerRegistered = false
    private var platformBackend: LocalNotificationBackend? = null

    @Suppress("UNUSED_PARAMETER")
    fun initialize(askPermissionOnStart: Boolean = true) {
        ensureLibraryListenerRegistered()
    }

    fun notify(
        id: Int,
        title: String,
        body: String,
        payloadData: NotificationPayload = emptyMap()
    ): Boolean {
        ensureLibraryListenerRegistered()
        val actions = notificationActionsFor(payloadData)
        platformBackend?.let { backend ->
            return backend.notify(
                id = id,
                title = title,
                body = body,
                payloadData = payloadData,
                actions = actions,
                onAction = ::dispatchActionClick,
                onClick = ::dispatchDefaultClick,
            )
        }
        if (!KMPNotifier.isInitialized) return false
        KMPNotifier.localNotifier.notify {
            this.id = id
            this.title = title
            this.body = body
            this.payloadData = payloadData
            this.actions = actions
        }
        return true
    }

    fun remove(id: Int) {
        platformBackend?.let { backend ->
            backend.remove(id)
            return
        }
        if (KMPNotifier.isInitialized) {
            KMPNotifier.localNotifier.remove(id)
        }
    }

    fun addClickListener(listener: NotificationClickListener) {
        ensureLibraryListenerRegistered()
        clickListeners = clickListeners + listener
        if (pendingClicks.isEmpty()) return
        val pending = pendingClicks
        pendingClicks = emptyList()
        pending.forEach { payload ->
            runCatching { listener(payload) }
        }
    }

    internal fun installPlatformBackend(backend: LocalNotificationBackend) {
        platformBackend = backend
    }

    private fun ensureLibraryListenerRegistered() {
        if (listenerRegistered) return
        KMPNotifier.addListener(object : KMPNotifier.Listener {
            override fun onAction(actionId: String, notificationId: Int, payload: PayloadData) {
                val normalizedPayload = sanitizeLibraryPayload(payload)
                val nowMillis = Clock.System.now().toEpochMilliseconds()
                eventNormalizer.recordAction(normalizedPayload, nowMillis)
                dispatchClick(normalizedPayload + (NotificationActionKeys.ACTION_ID to actionId))
                remove(notificationId)
            }

            override fun onNotificationClicked(data: PayloadData) {
                val normalizedPayload = sanitizeLibraryPayload(data)
                val nowMillis = Clock.System.now().toEpochMilliseconds()
                if (eventNormalizer.shouldSuppressClick(normalizedPayload, nowMillis)) return
                dispatchDefaultClick(normalizedPayload)
            }
        })
        listenerRegistered = true
    }

    private fun dispatchDefaultClick(payload: NotificationPayload) {
        dispatchClick(payload + (NotificationActionKeys.ACTION_ID to NotificationActionKeys.ACTION_DEFAULT))
    }

    private fun dispatchActionClick(actionId: String, payload: NotificationPayload) {
        dispatchClick(payload + (NotificationActionKeys.ACTION_ID to actionId))
    }

    private fun dispatchClick(payload: NotificationPayload) {
        if (clickListeners.isEmpty()) {
            pendingClicks = pendingClicks + listOf(payload)
            return
        }
        clickListeners.forEach { listener ->
            runCatching { listener(payload) }
        }
    }
}

internal interface LocalNotificationBackend {
    fun notify(
        id: Int,
        title: String,
        body: String,
        payloadData: NotificationPayload,
        actions: List<NotificationAction> = emptyList(),
        onAction: (String, NotificationPayload) -> Unit = { _, _ -> },
        onClick: (NotificationPayload) -> Unit,
    ): Boolean

    fun remove(id: Int)
}

internal fun errorLogNotificationActions(platformType: DeviceType): List<NotificationAction> {
    if (platformType == DeviceType.JS) return emptyList()
    return listOf(
        NotificationAction(
            NotificationActionKeys.ACTION_OPEN,
            AppStrings.notification_error_log_feedback,
        ),
    )
}

internal fun notificationActionsFor(payloadData: NotificationPayload): List<NotificationAction> {
    if (RequestNotificationFactory.isErrorLogMetadata(payloadData)) {
        return errorLogNotificationActions(PlatformType)
    }
    if (RequestNotificationFactory.isPermissionReminderMetadata(payloadData)) {
        return listOf(
            NotificationAction(NotificationActionKeys.ACTION_OPEN, AppStrings.android_action_open),
            NotificationAction(NotificationActionKeys.ACTION_DELETE, AppStrings.android_action_delete),
        )
    }
    if (RequestNotificationFactory.isAppUpdateMetadata(payloadData)) {
        if (RequestNotificationFactory.appUpdateHttpLink(payloadData) == null) {
            return emptyList()
        }
        return listOf(
            NotificationAction(
                NotificationActionKeys.ACTION_OPEN,
                AppStrings.settings_about_software_open_link,
            ),
        )
    }
    val kind = payloadData[RequestNotificationFactory.META_REQUEST_KIND]
        ?.trim()
        ?.let { value -> runCatching { RequestNotificationKind.valueOf(value) }.getOrNull() }
        ?: return emptyList()
    return if (kind == RequestNotificationKind.DeviceShare) {
        listOf(
            NotificationAction(NotificationActionKeys.ACTION_SAVE, AppStrings.android_action_save),
            NotificationAction(NotificationActionKeys.ACTION_VIEW, AppStrings.android_action_view),
            NotificationAction(NotificationActionKeys.ACTION_REJECT, AppStrings.android_action_reject),
        )
    } else {
        listOf(
            NotificationAction(NotificationActionKeys.ACTION_APPROVE, AppStrings.android_action_approve),
            NotificationAction(NotificationActionKeys.ACTION_REJECT, AppStrings.android_action_reject),
        )
    }
}

internal fun sanitizeLibraryPayload(payload: PayloadData): NotificationPayload {
    return payload.entries
        .asSequence()
        .filterNot { (key, _) -> key in LIBRARY_INTERNAL_PAYLOAD_KEYS }
        .associate { (key, value) -> key to (value?.toString() ?: "") }
}

internal class NotificationEventNormalizer(
    private val dedupWindowMillis: Long = DEFAULT_DEDUP_WINDOW_MILLIS,
) {
    private var lastActionPayload: NotificationPayload? = null
    private var lastActionAtMillis: Long = Long.MIN_VALUE

    fun recordAction(payload: NotificationPayload, nowMillis: Long) {
        lastActionPayload = payload
        lastActionAtMillis = nowMillis
    }

    fun shouldSuppressClick(payload: NotificationPayload, nowMillis: Long): Boolean {
        val recordedPayload = lastActionPayload ?: return false
        val elapsed = nowMillis - lastActionAtMillis
        val shouldSuppress = payload == recordedPayload && elapsed in 0..dedupWindowMillis
        if (shouldSuppress) {
            lastActionPayload = null
            lastActionAtMillis = Long.MIN_VALUE
        }
        return shouldSuppress
    }
}

private val LIBRARY_INTERNAL_PAYLOAD_KEYS = setOf("action_id", "notification_id")
private const val DEFAULT_DEDUP_WINDOW_MILLIS = 500L
