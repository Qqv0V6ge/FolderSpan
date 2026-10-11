package com.folderspan.localization

import strings.AppStrings

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.ui.state.main.Notification
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskRetryEntry
import com.folderspan.ui.state.main.TaskRetryStage
import com.folderspan.ui.state.main.TaskType
import io.github.skeptick.libres.LibresSettings
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class LocalizedMessageSerializationTest {
    @AfterTest
    fun resetLanguage() {
        LibresSettings.languageCode = "en"
    }

    @Test
    fun legacyTaskPayloadWithoutLocalizationFieldsStillDecodes() {
        val legacy = LegacyTask(
            taskType = TaskType.Copy,
            key = 7L,
            status = StatusEnum.FAILURE,
            result = mapOf("/tmp/a" to AppStrings.message_task_device_offline),
        )

        val decoded = ProtoBuf.decodeFromByteArray<Task>(
            ProtoBuf.encodeToByteArray(LegacyTask.serializer(), legacy),
        )

        assertEquals(legacy.result, decoded.result)
        assertTrue(decoded.resultMessages.isEmpty())
        LibresSettings.languageCode = "en"
        assertEquals("Device offline", decoded.resultMessage("/tmp/a", AppStrings.message_task_device_offline))

        LibresSettings.languageCode = "zhHans"
        assertEquals(AppStrings.message_task_device_offline, decoded.resultMessage("/tmp/a", AppStrings.message_task_device_offline))
    }

    @Test
    fun keyedOnlyNotificationRendersUsingCurrentLanguage() {
        val source = Notification(
            id = 1L,
            title = "",
            message = "",
            localizedTitle = LocalizedMessage(
                LocalizedMessageKeys.NOTIFICATION_DEVICE_CONNECT_TITLE,
            ),
            localizedMessage = LocalizedMessage(
                LocalizedMessageKeys.NOTIFICATION_DEVICE_CONNECT_BODY,
                args = mapOf("deviceName" to "Laptop"),
            ),
        )
        val decoded = ProtoBuf.decodeFromByteArray<Notification>(
            ProtoBuf.encodeToByteArray(source),
        )

        LibresSettings.languageCode = "en"
        assertEquals("Device connection request", decoded.displayTitle())
        assertEquals("Laptop wants to connect to you.", decoded.displayMessage())
    }

    @Test
    fun serverFailureNotificationRendersUsingCurrentLanguage() {
        val source = Notification(
            id = 2L,
            title = "",
            message = "",
            category = "",
            localizedTitle = LocalizedMessage(
                key = LocalizedMessageKeys.NOTIFICATION_SERVER_START_FAILURE_TITLE,
                args = mapOf(
                    "service" to LocalizedMessageValues.SERVER_SERVICE_FILE_SHARING,
                ),
            ),
            localizedMessage = LocalizedMessage(
                key = LocalizedMessageKeys.NOTIFICATION_SERVER_PORT_IN_USE_BODY,
                args = mapOf("port" to "12040"),
            ),
            localizedCategory = LocalizedMessage(LocalizedMessageKeys.CATEGORY_SERVICE),
        )
        val decoded = ProtoBuf.decodeFromByteArray<Notification>(
            ProtoBuf.encodeToByteArray(source),
        )

        LibresSettings.languageCode = "en"
        assertEquals("File sharing service failed to start", decoded.displayTitle())
        assertEquals(
            "Port 12040 is already in use. Change the port in Settings and try again, or close the app using it.",
            decoded.displayMessage(),
        )
        assertEquals("Service", decoded.displayCategory())

        LibresSettings.languageCode = "zhHans"
        assertEquals(AppStrings.ui_test_localized_message_serialization_file_sharing_service_failed_to_start, decoded.displayTitle())
        assertEquals(
            AppStrings.ui_test_localized_message_serialization_port_12040_is_already_occupied_please,
            decoded.displayMessage(),
        )
        assertEquals(AppStrings.notification_category_service, decoded.displayCategory())
    }

    @Test
    fun persistedLegacyServerFailureNotificationMigratesAtRenderTime() {
        LibresSettings.languageCode = "en"
        val source = Notification(
            id = 3L,
            title = AppStrings.ui_test_localized_message_serialization_file_sharing_service_started_failed,
            message = AppStrings.ui_test_localized_message_serialization_port_12040_is_already_occupied_please,
            category = AppStrings.notification_category_service,
            localizedTitle = LocalizedMessage(
                key = "",
                fallbackDetail = AppStrings.ui_test_localized_message_serialization_file_sharing_service_started_failed,
            ),
            localizedMessage = LocalizedMessage(
                key = "",
                fallbackDetail = AppStrings.ui_test_localized_message_serialization_port_12040_is_already_occupied_please,
            ),
            localizedCategory = LocalizedMessage(
                key = "",
                fallbackDetail = AppStrings.notification_category_service,
            ),
        )
        val decoded = ProtoBuf.decodeFromByteArray<Notification>(
            ProtoBuf.encodeToByteArray(source),
        )

        LibresSettings.languageCode = "en"
        assertEquals("File sharing service failed to start", decoded.displayTitle())
        assertEquals(
            "Port 12040 is already in use. Change the port in Settings and try again, or close the app using it.",
            decoded.displayMessage(),
        )
        assertEquals("Service", decoded.displayCategory())
    }

    @Test
    fun deviceShareResultMessagesRenderUsingCurrentLanguage() {
        val title = LocalizedMessage(
            LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_VIEW_FAILURE_TITLE,
        )
        val message = LocalizedMessage(
            key = LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_VIEW_FAILURE_BODY_WITH_DETAIL,
            args = mapOf(
                "deviceName" to "Laptop",
                "reason" to "Connection refused",
            ),
        )

        LibresSettings.languageCode = "en"
        assertEquals("Failed to connect to device share", title.render())
        assertEquals("Unable to connect to Laptop: Connection refused", message.render())

        LibresSettings.languageCode = "zhHans"
        assertEquals(AppStrings.notification_device_share_view_failure_title, title.render())
        assertEquals(AppStrings.ui_test_localized_message_serialization_connection_refused_to_laptop, message.render())
    }

    @Test
    fun mixedTaskPayloadPrefersLocalizedMessage() {
        val source = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.FAILURE,
            result = mapOf("/tmp/a" to AppStrings.message_task_device_offline),
            resultMessages = mapOf(
                "/tmp/a" to LocalizedMessage(LocalizedMessageKeys.TASK_DEVICE_OFFLINE),
            ),
        )
        val decoded = ProtoBuf.decodeFromByteArray<Task>(
            ProtoBuf.encodeToByteArray(source),
        )

        LibresSettings.languageCode = "en"
        assertEquals("Device offline", decoded.resultMessage("/tmp/a", AppStrings.message_task_device_offline))
    }

    @Test
    fun formattedArgumentsRoundTrip() {
        val message = LocalizedMessage(
            key = LocalizedMessageKeys.NOTIFICATION_LINK_UPLOAD_BODY,
            args = mapOf("deviceName" to "Phone"),
        )
        val decoded = ProtoBuf.decodeFromByteArray<LocalizedMessage>(
            ProtoBuf.encodeToByteArray(message),
        )

        LibresSettings.languageCode = "zhHans"
        assertEquals(AppStrings.ui_test_localized_message_serialization_phone_request_to_upload_file_or_folder, decoded.render())
    }

    @Test
    fun permissionArgumentsRenderInCurrentLanguage() {
        val message = LocalizedMessage(
            key = LocalizedMessageKeys.NOTIFICATION_PERMISSION_BODY_WITH_ITEMS,
            args = mapOf(
                "permissionIds" to "all_files_access,notifications",
            ),
        )

        LibresSettings.languageCode = "en"
        assertTrue(message.render().contains("All files access"))
        assertTrue(message.render().contains("Notification permission"))

        LibresSettings.languageCode = "zhHans"
        assertTrue(message.render().contains(AppStrings.permission_all_files_title))
        assertTrue(message.render().contains(AppStrings.permission_notifications_title))
    }

    @Test
    fun recognizedLegacyMessageMigratesLazily() {
        val migrated = assertNotNull(LocalizedMessage.fromLegacy(AppStrings.message_task_device_offline))

        assertEquals(LocalizedMessageKeys.TASK_DEVICE_OFFLINE, migrated.key)
        LibresSettings.languageCode = "en"
        assertEquals("Device offline", migrated.render())
    }

    @Test
    fun unknownLegacyMessageRetainsOriginalFallback() {
        val migrated = assertNotNull(LocalizedMessage.fromLegacy(AppStrings.ui_test_localized_message_serialization_return_from_the_remote_e_custom))

        assertEquals("", migrated.key)
        assertEquals(AppStrings.ui_test_localized_message_serialization_return_from_the_remote_e_custom, migrated.render())
    }

    @Test
    fun unknownLegacyFailurePreservesOriginalDetail() {
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.FAILURE,
            result = mapOf("/tmp/a" to AppStrings.ui_failed_create_parent_directory),
        )

        LibresSettings.languageCode = "en"
        assertEquals(AppStrings.ui_failed_create_parent_directory, task.resultMessage("/tmp/a", AppStrings.ui_failed_create_parent_directory))

        LibresSettings.languageCode = "zhHans"
        assertEquals(AppStrings.ui_failed_create_parent_directory, task.resultMessage("/tmp/a", AppStrings.ui_failed_create_parent_directory))
    }

    @Test
    fun localizedNotificationPreservesFailureDetail() {
        LibresSettings.languageCode = "zhHans"
        val reason = AppStrings.ui_test_localized_message_serialization_remote_path_does_not_exist
        val message = LocalizedMessage(
            key = LocalizedMessageKeys.NOTIFICATION_DEVICE_SHARE_VIEW_FAILURE_BODY_WITH_DETAIL,
            args = mapOf(
                "deviceName" to "Laptop",
                "reason" to reason,
            ),
        )

        LibresSettings.languageCode = "en"
        assertEquals(
            AppStrings.notification_device_share_view_failure_body_with_detail.format(
                deviceName = "Laptop",
                reason = reason,
            ),
            message.render(),
        )

        LibresSettings.languageCode = "zhHans"
        assertEquals(
            AppStrings.notification_device_share_view_failure_body_with_detail.format(
                deviceName = "Laptop",
                reason = reason,
            ),
            message.render(),
        )
    }

    @Test
    fun retryEntryStoresLegacyAndStructuredFailureTogether() {
        val source = TaskRetryEntry(
            entryKey = "retry",
            taskType = TaskType.Copy,
            stage = TaskRetryStage.COPY,
        ).withFailureMessage(AppStrings.message_task_request_timeout)
        val decoded = ProtoBuf.decodeFromByteArray<TaskRetryEntry>(
            ProtoBuf.encodeToByteArray(source),
        )

        assertEquals(AppStrings.message_task_request_timeout, decoded.failureMessage)
        assertEquals(
            LocalizedMessageKeys.TASK_REQUEST_TIMEOUT,
            decoded.localizedFailureMessage?.key,
        )
    }

    @Serializable
    private data class LegacyTask(
        @ProtoNumber(1) val taskType: TaskType,
        @ProtoNumber(2) val key: Long,
        @ProtoNumber(3) val status: StatusEnum,
        @ProtoNumber(4) val values: Map<String, String> = emptyMap(),
        @ProtoNumber(5) val result: Map<String, String> = emptyMap(),
        @ProtoNumber(6) val protocol: FileProtocol = FileProtocol.Local,
        @ProtoNumber(7) val protocolId: String = "",
        @ProtoNumber(8) val retryEntries: List<TaskRetryEntry> = emptyList(),
        @ProtoNumber(9) val activeResults: Map<String, String> = emptyMap(),
    )
}
