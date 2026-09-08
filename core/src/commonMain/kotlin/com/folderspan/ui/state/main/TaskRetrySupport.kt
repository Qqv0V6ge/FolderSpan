package com.folderspan.ui.state.main

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.localization.LocalizedMessage
import com.folderspan.localization.renderOrLegacy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
enum class TaskRetryStage {
    COPY,
    DELETE,
    DELETE_SOURCE,
}

@Serializable
enum class TaskRetryState {
    PENDING,
    SUCCESS,
    FAILURE,
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TaskRetryEntry(
    @ProtoNumber(1) val entryKey: String,
    @ProtoNumber(2) val taskType: TaskType,
    @ProtoNumber(3) val stage: TaskRetryStage,
    @ProtoNumber(4) val srcPath: String = "",
    @ProtoNumber(5) val srcProtocol: FileProtocol = FileProtocol.Local,
    @ProtoNumber(6) val srcProtocolId: String = "",
    @ProtoNumber(7) val destPath: String = "",
    @ProtoNumber(8) val destProtocol: FileProtocol = FileProtocol.Local,
    @ProtoNumber(9) val destProtocolId: String = "",
    @ProtoNumber(10) val isDirectory: Boolean = false,
    @ProtoNumber(11) val size: Long = 0L,
    @ProtoNumber(12) val state: TaskRetryState = TaskRetryState.PENDING,
    @ProtoNumber(13) val failureMessage: String = "",
    @ProtoNumber(14) val localizedFailureMessage: LocalizedMessage? = null,
) {
    val resultPath: String
        get() = when (stage) {
            TaskRetryStage.COPY -> destPath.ifBlank { srcPath }
            TaskRetryStage.DELETE,
            TaskRetryStage.DELETE_SOURCE -> srcPath.ifBlank { destPath }
        }

    fun withState(newState: TaskRetryState): TaskRetryEntry {
        return if (state == newState) this else copy(state = newState)
    }

    fun withFailureMessage(message: String): TaskRetryEntry {
        val localized = LocalizedMessage.fromLegacy(message)
        return if (failureMessage == message && localizedFailureMessage == localized) {
            this
        } else {
            copy(
                failureMessage = message,
                localizedFailureMessage = localized,
            )
        }
    }

    fun displayFailureMessage(): String =
        localizedFailureMessage.renderOrLegacy(failureMessage)
}

fun buildTaskRetryEntryKey(
    stage: TaskRetryStage,
    srcPath: String,
    srcProtocol: FileProtocol,
    srcProtocolId: String,
    destPath: String,
    destProtocol: FileProtocol,
    destProtocolId: String,
): String {
    return listOf(
        stage.name,
        srcProtocol.name,
        srcProtocolId,
        srcPath,
        destProtocol.name,
        destProtocolId,
        destPath,
    ).joinToString("|")
}

fun buildCopyRetryEntry(
    taskType: TaskType,
    src: FileSimpleInfo,
    dest: FileSimpleInfo,
): TaskRetryEntry {
    return TaskRetryEntry(
        entryKey = buildTaskRetryEntryKey(
            stage = TaskRetryStage.COPY,
            srcPath = src.path,
            srcProtocol = src.protocol,
            srcProtocolId = src.protocolId,
            destPath = dest.path,
            destProtocol = dest.protocol,
            destProtocolId = dest.protocolId,
        ),
        taskType = taskType,
        stage = TaskRetryStage.COPY,
        srcPath = src.path,
        srcProtocol = src.protocol,
        srcProtocolId = src.protocolId,
        destPath = dest.path,
        destProtocol = dest.protocol,
        destProtocolId = dest.protocolId,
        isDirectory = src.isDirectory,
        size = src.size,
    )
}

fun buildDeleteRetryEntry(
    taskType: TaskType,
    target: FileSimpleInfo,
    stage: TaskRetryStage = TaskRetryStage.DELETE,
): TaskRetryEntry {
    return TaskRetryEntry(
        entryKey = buildTaskRetryEntryKey(
            stage = stage,
            srcPath = target.path,
            srcProtocol = target.protocol,
            srcProtocolId = target.protocolId,
            destPath = "",
            destProtocol = FileProtocol.Local,
            destProtocolId = "",
        ),
        taskType = taskType,
        stage = stage,
        srcPath = target.path,
        srcProtocol = target.protocol,
        srcProtocolId = target.protocolId,
        isDirectory = target.isDirectory,
    )
}

fun buildRetryFileSimpleInfo(
    path: String,
    protocol: FileProtocol,
    protocolId: String,
    isDirectory: Boolean,
    size: Long = 0L,
): FileSimpleInfo {
    val name = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
    return FileSimpleInfo.nullFileSimpleInfo().copy(
        name = name,
        isDirectory = isDirectory,
        path = path,
        size = size,
        protocol = protocol,
        protocolId = protocolId,
    )
}
