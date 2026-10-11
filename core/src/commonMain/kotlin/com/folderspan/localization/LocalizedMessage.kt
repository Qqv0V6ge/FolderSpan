package com.folderspan.localization

import com.folderspan.permission.PermissionIds
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import strings.AppStrings

object LocalizedMessageKeys {
    const val TASK_DEVICE_OFFLINE = "task.device_offline"
    const val TASK_CANCELLED = "task.cancelled"
    const val TASK_REQUEST_TIMEOUT = "task.request_timeout"
    const val TASK_SHARE_SESSION_EXPIRED = "task.share_session_expired"
    const val TASK_OPERATION_FAILED = "task.operation_failed"
    const val TASK_PERMISSION_DENIED = "task.permission_denied"

    const val NOTIFICATION_DEVICE_CONNECT_TITLE = "notification.device_connect.title"
    const val NOTIFICATION_DEVICE_CONNECT_BODY = "notification.device_connect.body"
    const val NOTIFICATION_DEVICE_SHARE_TITLE = "notification.device_share.title"
    const val NOTIFICATION_DEVICE_SHARE_BODY = "notification.device_share.body"
    const val NOTIFICATION_LINK_SHARE_TITLE = "notification.link_share.title"
    const val NOTIFICATION_LINK_SHARE_BODY = "notification.link_share.body"
    const val NOTIFICATION_LINK_UPLOAD_TITLE = "notification.link_upload.title"
    const val NOTIFICATION_LINK_UPLOAD_BODY = "notification.link_upload.body"
    const val NOTIFICATION_PERMISSION_TITLE = "notification.permission.title"
    const val NOTIFICATION_PERMISSION_BODY = "notification.permission.body"
    const val NOTIFICATION_PERMISSION_BODY_WITH_ITEMS = "notification.permission.body_with_items"
    const val NOTIFICATION_SERVER_START_FAILURE_TITLE = "notification.server_start_failure.title"
    const val NOTIFICATION_SERVER_PORT_IN_USE_BODY = "notification.server_start_failure.port_in_use_body"
    const val NOTIFICATION_SERVER_START_FAILURE_BODY = "notification.server_start_failure.body"
    const val NOTIFICATION_SERVER_START_FAILURE_BODY_WITH_DETAIL =
        "notification.server_start_failure.body_with_detail"
    const val NOTIFICATION_DEVICE_SHARE_AUTO_SAVE_SUCCESS_TITLE =
        "notification.device_share_auto_save.success_title"
    const val NOTIFICATION_DEVICE_SHARE_AUTO_SAVE_FAILURE_TITLE =
        "notification.device_share_auto_save.failure_title"
    const val NOTIFICATION_DEVICE_SHARE_SAVE_SUCCESS_TITLE =
        "notification.device_share_save.success_title"
    const val NOTIFICATION_DEVICE_SHARE_SAVE_FAILURE_TITLE =
        "notification.device_share_save.failure_title"
    const val NOTIFICATION_DEVICE_SHARE_SAVE_SUCCESS_BODY =
        "notification.device_share_save.success_body"
    const val NOTIFICATION_DEVICE_SHARE_SAVE_FAILURE_BODY =
        "notification.device_share_save.failure_body"
    const val NOTIFICATION_DEVICE_SHARE_SAVE_FAILURE_BODY_WITH_DETAIL =
        "notification.device_share_save.failure_body_with_detail"
    const val NOTIFICATION_DEVICE_SHARE_VIEW_SUCCESS_TITLE =
        "notification.device_share_view.success_title"
    const val NOTIFICATION_DEVICE_SHARE_VIEW_FAILURE_TITLE =
        "notification.device_share_view.failure_title"
    const val NOTIFICATION_DEVICE_SHARE_VIEW_SUCCESS_BODY =
        "notification.device_share_view.success_body"
    const val NOTIFICATION_DEVICE_SHARE_VIEW_FAILURE_BODY =
        "notification.device_share_view.failure_body"
    const val NOTIFICATION_DEVICE_SHARE_VIEW_FAILURE_BODY_WITH_DETAIL =
        "notification.device_share_view.failure_body_with_detail"
    const val NOTIFICATION_APP_UPDATE_TITLE = "notification.app_update.title"
    const val NOTIFICATION_APP_UPDATE_BODY = "notification.app_update.body"
    const val NOTIFICATION_ERROR_LOG_TITLE = "notification.error_log.title"
    const val NOTIFICATION_ERROR_LOG_BODY = "notification.error_log.body"
    const val CATEGORY_DEVICE = "category.device"
    const val CATEGORY_SHARE = "category.share"
    const val CATEGORY_PERMISSION = "category.permission"
    const val CATEGORY_SERVICE = "category.service"
    const val CATEGORY_UPDATE = "category.update"
    const val CATEGORY_ERROR = "category.error"
}

object LocalizedMessageValues {
    const val SERVER_SERVICE_FILE_SHARING = "file_sharing"
    const val SERVER_SERVICE_SIMPLE_SHARING = "simple_sharing"
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LocalizedMessage(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) val args: Map<String, String> = emptyMap(),
    @ProtoNumber(3) val fallbackDetail: String? = null,
) {
    fun render(): String {
        val rendered = when (key) {
            LocalizedMessageKeys.TASK_DEVICE_OFFLINE -> AppStrings.message_task_device_offline
            LocalizedMessageKeys.TASK_CANCELLED -> AppStrings.message_task_cancelled
            LocalizedMessageKeys.TASK_REQUEST_TIMEOUT -> AppStrings.message_task_request_timeout
            LocalizedMessageKeys.TASK_SHARE_SESSION_EXPIRED -> AppStrings.message_task_share_session_expired
            LocalizedMessageKeys.TASK_OPERATION_FAILED -> AppStrings.message_task_operation_failed
            LocalizedMessageKeys.TASK_PERMISSION_DENIED -> {
                val detail = args["detail"].orEmpty()
                if (detail.isBlank()) {
                    AppStrings.message_task_permission_denied
                } else {
                    AppStrings.message_task_permission_denied_with_detail.format(detail = detail)
                }
            }

            LocalizedMessageKeys.NOTIFICATION_DEVICE_CONNECT_TITLE ->
                AppStrings.notification_device_connect_title

            LocalizedMessageKeys.NOTIFICATION_DEVICE_CONNECT_BODY ->
                AppStrings.notification_device_connect_body.format(
                    deviceName = args["deviceName"].orEmpty(),
                )

            LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_TITLE ->
                AppStrings.notification_device_share_title

            LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_BODY ->
                AppStrings.notification_device_share_body.format(
                    deviceName = args["deviceName"].orEmpty(),
                )

            LocalizedMessageKeys.NOTIFICATION_LINK_SHARE_TITLE ->
                AppStrings.notification_link_share_title

            LocalizedMessageKeys.NOTIFICATION_LINK_SHARE_BODY ->
                AppStrings.notification_link_share_body.format(
                    deviceName = args["deviceName"].orEmpty(),
                )

            LocalizedMessageKeys.NOTIFICATION_LINK_UPLOAD_TITLE ->
                AppStrings.notification_link_upload_title

            LocalizedMessageKeys.NOTIFICATION_LINK_UPLOAD_BODY ->
                AppStrings.notification_link_upload_body.format(
                    deviceName = args["deviceName"].orEmpty(),
                )

            LocalizedMessageKeys.NOTIFICATION_PERMISSION_TITLE ->
                AppStrings.notification_permission_title

            LocalizedMessageKeys.NOTIFICATION_PERMISSION_BODY ->
                AppStrings.notification_permission_body

            LocalizedMessageKeys.NOTIFICATION_PERMISSION_BODY_WITH_ITEMS ->
                AppStrings.notification_permission_body_with_items.format(
                    items = renderPermissionItems(),
                )

            LocalizedMessageKeys.NOTIFICATION_SERVER_START_FAILURE_TITLE ->
                AppStrings.notification_server_start_failure_title.format(
                    serviceName = renderServerServiceName(),
                )

            LocalizedMessageKeys.NOTIFICATION_SERVER_PORT_IN_USE_BODY ->
                AppStrings.notification_server_port_in_use_body.format(
                    port = args["port"].orEmpty(),
                )

            LocalizedMessageKeys.NOTIFICATION_SERVER_START_FAILURE_BODY ->
                AppStrings.notification_server_start_failure_body

            LocalizedMessageKeys.NOTIFICATION_SERVER_START_FAILURE_BODY_WITH_DETAIL ->
                AppStrings.notification_server_start_failure_body_with_detail.format(
                    detail = args["detail"].orEmpty(),
                )

            LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_AUTO_SAVE_SUCCESS_TITLE ->
                AppStrings.notification_device_share_auto_save_success_title

            LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_AUTO_SAVE_FAILURE_TITLE ->
                AppStrings.notification_device_share_auto_save_failure_title

            LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_SAVE_SUCCESS_TITLE ->
                AppStrings.notification_device_share_save_success_title

            LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_SAVE_FAILURE_TITLE ->
                AppStrings.notification_device_share_save_failure_title

            LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_SAVE_SUCCESS_BODY ->
                AppStrings.notification_device_share_save_success_body.format(
                    deviceName = args["deviceName"].orEmpty(),
                    savePath = args["savePath"].orEmpty(),
                )

            LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_SAVE_FAILURE_BODY ->
                AppStrings.notification_device_share_save_failure_body.format(
                    deviceName = args["deviceName"].orEmpty(),
                    savePath = args["savePath"].orEmpty(),
                )

            LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_SAVE_FAILURE_BODY_WITH_DETAIL ->
                AppStrings.notification_device_share_save_failure_body_with_detail.format(
                    deviceName = args["deviceName"].orEmpty(),
                    reason = args["reason"].orEmpty(),
                    savePath = args["savePath"].orEmpty(),
                )

            LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_VIEW_SUCCESS_TITLE ->
                AppStrings.notification_device_share_view_success_title

            LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_VIEW_FAILURE_TITLE ->
                AppStrings.notification_device_share_view_failure_title

            LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_VIEW_SUCCESS_BODY ->
                AppStrings.notification_device_share_view_success_body.format(
                    deviceName = args["deviceName"].orEmpty(),
                )

            LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_VIEW_FAILURE_BODY ->
                AppStrings.notification_device_share_view_failure_body.format(
                    deviceName = args["deviceName"].orEmpty(),
                )

            LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_VIEW_FAILURE_BODY_WITH_DETAIL ->
                AppStrings.notification_device_share_view_failure_body_with_detail.format(
                    deviceName = args["deviceName"].orEmpty(),
                    reason = args["reason"].orEmpty(),
                )

            LocalizedMessageKeys.NOTIFICATION_APP_UPDATE_TITLE ->
                AppStrings.notification_app_update_title

            LocalizedMessageKeys.NOTIFICATION_APP_UPDATE_BODY ->
                AppStrings.notification_app_update_body.format(
                    version = args["version"].orEmpty(),
                )

            LocalizedMessageKeys.NOTIFICATION_ERROR_LOG_TITLE ->
                AppStrings.notification_error_log_title

            LocalizedMessageKeys.NOTIFICATION_ERROR_LOG_BODY ->
                AppStrings.notification_error_log_body

            LocalizedMessageKeys.CATEGORY_DEVICE -> AppStrings.notification_category_device
            LocalizedMessageKeys.CATEGORY_SHARE -> AppStrings.notification_category_share
            LocalizedMessageKeys.CATEGORY_PERMISSION -> AppStrings.notification_category_permission
            LocalizedMessageKeys.CATEGORY_SERVICE -> AppStrings.notification_category_service
            LocalizedMessageKeys.CATEGORY_UPDATE -> AppStrings.notification_category_update
            LocalizedMessageKeys.CATEGORY_ERROR -> AppStrings.notification_category_error
            else -> null
        }
        return rendered ?: fallbackDetail.orEmpty()
    }

    private fun renderServerServiceName(): String {
        return when (args["service"]) {
            LocalizedMessageValues.SERVER_SERVICE_FILE_SHARING -> AppStrings.ui_file_sharing_service
            LocalizedMessageValues.SERVER_SERVICE_SIMPLE_SHARING -> AppStrings.ui_simple_sharing_service
            else -> args["serviceName"].orEmpty()
        }
    }

    private fun renderPermissionItems(): String {
        val ids = args["permissionIds"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (ids.isEmpty()) return args["items"].orEmpty()
        val titles = ids.map { id ->
            when (id) {
                PermissionIds.ReadExternalStorage -> AppStrings.permission_storage_title
                PermissionIds.AllFilesAccess -> AppStrings.permission_all_files_title
                PermissionIds.Notifications -> AppStrings.permission_notifications_title
                PermissionIds.BatteryOptimization -> AppStrings.permission_battery_title
                PermissionIds.Shizuku -> AppStrings.permission_shizuku_title
                PermissionIds.Root -> AppStrings.permission_root_title
                PermissionIds.LoginStartup -> AppStrings.permission_login_startup_title
                else -> id
            }
        }
        val preview = titles.take(3).joinToString(AppStrings.list_separator)
        return if (titles.size > 3) {
            AppStrings.notification_permission_more_items.format(
                items = preview,
                count = (titles.size - 3).toString(),
            )
        } else {
            preview
        }
    }

    companion object {
        fun fromLegacy(text: String?): LocalizedMessage? {
            val legacy = text?.trim().orEmpty()
            if (legacy.isEmpty()) return null
            return when (legacy) {
                AppStrings.message_task_device_offline -> LocalizedMessage(LocalizedMessageKeys.TASK_DEVICE_OFFLINE)
                AppStrings.message_task_cancelled -> LocalizedMessage(LocalizedMessageKeys.TASK_CANCELLED)
                AppStrings.message_task_request_timeout ->
                    LocalizedMessage(LocalizedMessageKeys.TASK_REQUEST_TIMEOUT)

                AppStrings.message_task_share_session_expired ->
                    LocalizedMessage(LocalizedMessageKeys.TASK_SHARE_SESSION_EXPIRED)

                AppStrings.ui_operation_failed -> LocalizedMessage(LocalizedMessageKeys.TASK_OPERATION_FAILED)
                AppStrings.message_task_permission_denied -> LocalizedMessage(LocalizedMessageKeys.TASK_PERMISSION_DENIED)
                AppStrings.notification_device_connect_title ->
                    LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_DEVICE_CONNECT_TITLE)

                AppStrings.notification_device_share_title ->
                    LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_TITLE)

                AppStrings.notification_link_share_title ->
                    LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_LINK_SHARE_TITLE)

                AppStrings.notification_link_upload_title ->
                    LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_LINK_UPLOAD_TITLE)

                AppStrings.notification_permission_title -> LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_PERMISSION_TITLE)
                AppStrings.notification_device_share_auto_save_success_title ->
                    LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_AUTO_SAVE_SUCCESS_TITLE)

                AppStrings.notification_device_share_auto_save_failure_title ->
                    LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_AUTO_SAVE_FAILURE_TITLE)

                AppStrings.notification_device_share_save_success_title ->
                    LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_SAVE_SUCCESS_TITLE)

                AppStrings.notification_device_share_save_failure_title ->
                    LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_SAVE_FAILURE_TITLE)

                AppStrings.notification_device_share_view_success_title ->
                    LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_VIEW_SUCCESS_TITLE)

                AppStrings.notification_device_share_view_failure_title ->
                    LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_VIEW_FAILURE_TITLE)

                AppStrings.notification_app_update_title ->
                    LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_APP_UPDATE_TITLE)

                AppStrings.ui_equipment -> LocalizedMessage(LocalizedMessageKeys.CATEGORY_DEVICE)
                AppStrings.ui_share -> LocalizedMessage(LocalizedMessageKeys.CATEGORY_SHARE)
                AppStrings.ui_permissions -> LocalizedMessage(LocalizedMessageKeys.CATEGORY_PERMISSION)
                AppStrings.notification_category_service -> LocalizedMessage(LocalizedMessageKeys.CATEGORY_SERVICE)
                AppStrings.ui_update -> LocalizedMessage(LocalizedMessageKeys.CATEGORY_UPDATE)
                else -> recognizedLegacyPattern(legacy)
                    ?: LocalizedMessage(key = "", fallbackDetail = text)
            }
        }

        private fun recognizedLegacyPattern(legacy: String): LocalizedMessage? {
            Regex(AppStrings.legacy_connection_request_pattern).matchEntire(legacy)?.let { match ->
                return LocalizedMessage(
                    key = LocalizedMessageKeys.NOTIFICATION_DEVICE_CONNECT_BODY,
                    args = mapOf("deviceName" to match.groupValues[1]),
                )
            }
            Regex(AppStrings.legacy_send_file_or_folder_request_pattern).matchEntire(legacy)?.let { match ->
                return LocalizedMessage(
                    key = LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_BODY,
                    args = mapOf("deviceName" to match.groupValues[1]),
                )
            }
            Regex(AppStrings.legacy_share_link_access_request_pattern).matchEntire(legacy)?.let { match ->
                return LocalizedMessage(
                    key = LocalizedMessageKeys.NOTIFICATION_LINK_SHARE_BODY,
                    args = mapOf("deviceName" to match.groupValues[1]),
                )
            }
            Regex(AppStrings.legacy_upload_file_or_folder_request_pattern).matchEntire(legacy)?.let { match ->
                return LocalizedMessage(
                    key = LocalizedMessageKeys.NOTIFICATION_LINK_UPLOAD_BODY,
                    args = mapOf("deviceName" to match.groupValues[1]),
                )
            }
            Regex(AppStrings.legacy_service_failed_to_start_pattern).matchEntire(legacy)?.let { match ->
                return LocalizedMessage(
                    key = LocalizedMessageKeys.NOTIFICATION_SERVER_START_FAILURE_TITLE,
                    args = legacyServerServiceArgs(match.groupValues[1]),
                )
            }
            Regex(AppStrings.ui_port_d_is_already_occupied_please_change_the_port_in_the_settings_and_retry)
                .matchEntire(legacy)
                ?.let { match ->
                    return LocalizedMessage(
                        key = LocalizedMessageKeys.NOTIFICATION_SERVER_PORT_IN_USE_BODY,
                        args = mapOf("port" to match.groupValues[1]),
                    )
                }
            if (legacy == AppStrings.notification_server_start_failure_body) {
                return LocalizedMessage(LocalizedMessageKeys.NOTIFICATION_SERVER_START_FAILURE_BODY)
            }
            Regex(AppStrings.legacy_service_start_failure_with_detail_pattern).matchEntire(legacy)?.let { match ->
                return LocalizedMessage(
                    key = LocalizedMessageKeys.NOTIFICATION_SERVER_START_FAILURE_BODY_WITH_DETAIL,
                    args = mapOf("detail" to match.groupValues[1]),
                )
            }
            Regex(AppStrings.legacy_share_file_saved_message_pattern).matchEntire(legacy)?.let { match ->
                return LocalizedMessage(
                    key = LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_SAVE_SUCCESS_BODY,
                    args = mapOf(
                        "deviceName" to match.groupValues[1],
                        "savePath" to match.groupValues[2],
                    ),
                )
            }
            Regex(AppStrings.legacy_share_file_save_failed_message_pattern)
                .matchEntire(legacy)
                ?.let { match ->
                    return LocalizedMessage(
                        key = LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_SAVE_FAILURE_BODY_WITH_DETAIL,
                        args = mapOf(
                            "deviceName" to match.groupValues[1],
                            "reason" to match.groupValues[2],
                            "savePath" to match.groupValues[3],
                        ),
                    )
                }
            Regex(AppStrings.legacy_share_connected_message_pattern).matchEntire(legacy)?.let { match ->
                return LocalizedMessage(
                    key = LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_VIEW_SUCCESS_BODY,
                    args = mapOf("deviceName" to match.groupValues[1]),
                )
            }
            Regex(AppStrings.legacy_cannot_connect_to_pattern).matchEntire(legacy)?.let { match ->
                return LocalizedMessage(
                    key = LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_VIEW_FAILURE_BODY_WITH_DETAIL,
                    args = mapOf(
                        "deviceName" to match.groupValues[1],
                        "reason" to match.groupValues[2],
                    ),
                )
            }
            return null
        }

        private fun legacyServerServiceArgs(serviceName: String): Map<String, String> {
            val normalized = serviceName.trim()
            val service = when (normalized) {
                AppStrings.ui_file_sharing_service, "File sharing service" ->
                    LocalizedMessageValues.SERVER_SERVICE_FILE_SHARING

                AppStrings.ui_simple_sharing_service, "Simple sharing service" ->
                    LocalizedMessageValues.SERVER_SERVICE_SIMPLE_SHARING

                else -> null
            }
            return if (service == null) {
                mapOf("serviceName" to normalized)
            } else {
                mapOf("service" to service)
            }
        }
    }
}

fun LocalizedMessage?.renderOrLegacy(legacy: String): String {
    val localized = when {
        this == null -> LocalizedMessage.fromLegacy(legacy)
        key.isBlank() -> LocalizedMessage.fromLegacy(fallbackDetail ?: legacy)
        else -> this
    }
    val rendered = localized?.render().orEmpty()
    return rendered.ifBlank { legacy }
}
