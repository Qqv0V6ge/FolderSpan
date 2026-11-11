package com.folderspan.ui.state.file

import com.folderspan.data.file.FileSimpleInfo
import kotlinx.serialization.Serializable

@Serializable
internal data class CopySavedAuthority(val endpoint: CopyEndpointIdentity, val proof: String)

@Serializable
internal data class CopySavedVersion(val file: FileSimpleInfo, val digest: String?)

/** Private task sidecar. Credentials and session tokens are represented only by keyed proofs. */
@Serializable
internal data class CopyTaskValidationSnapshot(
    val schemaVersion: Int = 1,
    val taskKey: Long,
    val requestId: String,
    val planId: String,
    val runtimeScope: String,
    val sources: List<CopySavedAuthority>,
    val target: CopySavedAuthority,
    val sourceEndpoint: CopyEndpointIdentity,
    val source: FileSimpleInfo,
    val destination: FileSimpleInfo,
    val directory: FileSimpleInfo,
    val canonicalDirectory: String,
    val versions: List<CopySavedVersion>,
)
