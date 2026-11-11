package com.folderspan.service.webui

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.network.NetworkConnectionConfig
import com.folderspan.service.network.NetworkFileEntry
import com.folderspan.service.network.StoragePath
import kotlinx.serialization.Serializable

@Serializable
internal data class WebUiStorageContentRequest(
    val handle: String,
    val config: NetworkConnectionConfig? = null,
    val taskKey: Long = 0,
    val entryId: String = "",
    val file: FileSimpleInfo? = null,
    val stagedPath: String = "",
    val destinationPath: String = "",
    val password: String = "",
    val enabled: Boolean = false,
    val entry: NetworkFileEntry? = null,
    val taskReference: String = "",
    val target: StoragePath? = null,
    val destination: StoragePath? = null,
    val isDirectory: Boolean = false,
    val expectedIdentity: String = "",
) {
    override fun toString() = "WebUiStorageContentRequest()"
}

internal const val STORAGE_CONTENT_ERROR_PREFIX = "storage-content:"

internal fun isStorageContentLoopbackHost(host: String): Boolean =
    host.removeSurrounding("[", "]").lowercase() in setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
