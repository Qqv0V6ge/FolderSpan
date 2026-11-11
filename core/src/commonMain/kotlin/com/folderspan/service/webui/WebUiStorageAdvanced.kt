package com.folderspan.service.webui

import com.folderspan.service.network.NetworkFileEntry
import com.folderspan.service.network.StoragePath
import com.folderspan.service.network.StorageShare
import com.folderspan.service.network.StorageShareRequest
import com.folderspan.service.network.StorageUploadStatus
import com.folderspan.service.network.StorageVersion
import kotlinx.serialization.Serializable
import com.folderspan.ui.state.main.TaskRuntimeUploadSessionReference

@Serializable
internal data class WebUiStorageAdvancedRequest(
    val handle: String,
    val path: StoragePath = StoragePath(""),
    val cursor: String? = null,
    val size: Long = 0,
    val version: StorageVersion? = null,
    val share: StorageShare? = null,
    val shareRequest: StorageShareRequest? = null,
    val confirmed: Boolean = false,
    val taskKey: Long = 0,
    val uploadReference: TaskRuntimeUploadSessionReference? = null,
    val uploadHandle: String = "",
)

@Serializable
internal data class WebUiStorageUploadState(
    val handle: String,
    val target: StoragePath,
    val size: Long,
    val confirmedOffset: Long,
    val status: StorageUploadStatus,
    val entry: NetworkFileEntry? = null,
)
