package com.folderspan.service.http.server

import com.folderspan.localization.LocalizedMessage
import com.folderspan.localization.LocalizedMessageKeys
import com.folderspan.localization.LocalizedMessageValues
import com.folderspan.notification.LocalNotifier
import com.folderspan.notification.RequestNotificationBundle
import com.folderspan.notification.RequestNotificationDispatcher
import com.folderspan.notification.RequestNotificationFactory
import com.folderspan.ui.state.main.NotificationState
import com.folderspan.ui.state.main.NotificationType
import com.folderspan.utils.LogKit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import strings.AppStrings

enum class ServerStartNotificationService(val localizationId: String) {
    FileSharing(LocalizedMessageValues.SERVER_SERVICE_FILE_SHARING),
    SimpleSharing(LocalizedMessageValues.SERVER_SERVICE_SIMPLE_SHARING),
}

fun Throwable.toServerStartFailureMessage(port: Int): String {
    return toServerStartFailureLocalizedMessage(port).render()
}

fun Throwable.toServerStartFailureLocalizedMessage(port: Int): LocalizedMessage {
    if (isPortInUseFailure()) {
        return portInUseLocalizedMessage(port)
    }
    val detail = firstNonBlankMessage()
    return if (detail.isNullOrBlank()) {
        LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_SERVER_START_FAILURE_BODY)
    } else {
        LocalizedMessage(
            key = LocalizedMessageKeys.NOTIFICATION_SERVER_START_FAILURE_BODY_WITH_DETAIL,
            args = mapOf("detail" to detail),
        )
    }
}

fun portInUseMessage(port: Int): String {
    return portInUseLocalizedMessage(port).render()
}

fun portInUseLocalizedMessage(port: Int): LocalizedMessage {
    return LocalizedMessage(
        key = LocalizedMessageKeys.NOTIFICATION_SERVER_PORT_IN_USE_BODY,
        args = mapOf("port" to port.toString()),
    )
}

private fun Throwable.firstNonBlankMessage(): String? {
    return generateSequence(this) { item -> item.cause }
        .mapNotNull { item -> item.message?.trim() }
        .firstOrNull { item -> item.isNotEmpty() }
}

fun Throwable.isPortInUseFailure(): Boolean {
    return generateSequence(this) { item -> item.cause }
        .mapNotNull { item -> item.message?.lowercase() }
        .any { message ->
            "address already in use" in message ||
                "eaddrinuse" in message ||
                "bind failed" in message && "in use" in message ||
                "failed to bind" in message && "in use" in message
        }
}

fun notifyServerPortInUse(service: ServerStartNotificationService, port: Int) {
    postServerStartFailureNotification(service, port, portInUseLocalizedMessage(port))
}

fun notifyServerStartFailure(service: ServerStartNotificationService, port: Int, error: Throwable) {
    postServerStartFailureNotification(service, port, error.toServerStartFailureLocalizedMessage(port))
}

private fun postServerStartFailureNotification(
    service: ServerStartNotificationService,
    port: Int,
    localizedMessage: LocalizedMessage,
) {
    val localizedTitle = LocalizedMessage(
        key = LocalizedMessageKeys.NOTIFICATION_SERVER_START_FAILURE_TITLE,
        args = mapOf("service" to service.localizationId),
    )
    val metadata = mapOf(
        "kind" to SERVER_START_FAILURE_NOTIFICATION_KIND,
        "service" to service.localizationId,
        "port" to port.toString()
    )
    val bundle = RequestNotificationFactory.buildLocalizedCustomNotification(
        kind = SERVER_START_FAILURE_NOTIFICATION_KIND,
        localizedTitle = localizedTitle,
        localizedMessage = localizedMessage,
        type = NotificationType.Error,
        localizedCategory = LocalizedMessage(LocalizedMessageKeys.CATEGORY_SERVICE),
        metadata = metadata
    )

    val postedInApp = runCatching {
        ServerStartFailureInAppNotifier.post(bundle)
    }.onFailure { notifyError ->
        LogKit.w(AppStrings.ui_write_application_internal_notification_failed_arg0.format(arg0 = (notifyError.message).toString()))
    }.isSuccess

    if (!postedInApp) {
        runCatching {
            val payload = bundle.systemNotification ?: return@runCatching
            LocalNotifier.notify(
                id = payload.id,
                title = payload.displayTitle(),
                body = payload.displayBody(),
                payloadData = payload.payloadData
            )
        }.onFailure { notifyError ->
            LogKit.w(AppStrings.ui_send_service_start_failure_notification_arg0.format(arg0 = (notifyError.message).toString()))
        }
    }
}

private object ServerStartFailureInAppNotifier : KoinComponent {
    private val notificationState by inject<NotificationState>()

    fun post(bundle: RequestNotificationBundle) {
        RequestNotificationDispatcher.post(notificationState, bundle)
    }
}

private const val SERVER_START_FAILURE_NOTIFICATION_KIND = "server_start_failure"
