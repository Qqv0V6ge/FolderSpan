package com.folderspan.data.main.share

import com.folderspan.PlatformType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.DiskMenuPermission
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.exception.AuthorityException
import com.folderspan.exception.TimeoutException
import com.folderspan.service.network.isUnsafeRemoteListEntry
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.ClipboardUrlShareFiles
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.Task
import com.folderspan.utils.LogKit
import io.ktor.client.plugins.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import strings.AppStrings

@Serializable
enum class ShareProtocol {
    Remote,
    System,
}

const val SYSTEM_SHARE_DESK_ID = "system-share"

fun buildSystemShareDesk(): Share {
    return Share(
        id = SYSTEM_SHARE_DESK_ID,
        name = AppStrings.ui_other_apps,
        pathSeparator = "/",
        protocol = ShareProtocol.System,
        type = PlatformType,
    )
}

data class Share(
    val id: String,
    override val name: String,
    override val pathSeparator: String,
    val session: ShareSession? = null,
    val protocol: ShareProtocol = ShareProtocol.Remote,
    val type: DeviceType,
) : DiskBase(), KoinComponent {
    init {
        require(protocol == ShareProtocol.System || session != null) {
            "Remote Share requires a Session"
        }
    }

    override val menuPermission: DiskMenuPermission = DiskMenuPermission(
        read = true,
        copy = true,
        favorite = protocol != ShareProtocol.Remote,
        share = true,
        info = true,
    )
    private val fileState: FileState by inject()
    private val deviceState: DeviceState by inject()

    fun disconnect(): Boolean = if (protocol == ShareProtocol.System) {
        ClipboardUrlShareFiles.clear()
        true
    } else session?.disconnect() ?: false

    private fun handleError() {
        deviceState.shares.remove(this)
        fileState.updateDesk(FileProtocol.Local, Local())
        LogKit.e(AppStrings.ui_shared_session_exception_reverted_to_local_disk)
    }

    private fun shouldDisconnect(error: Throwable?): Boolean {
        return when (error) {
            is AuthorityException -> true
            is TimeoutException -> true
            is HttpRequestTimeoutException -> true
            is IOException -> true
            else -> false
        }
    }

    suspend fun getFileList(
        path: String,
    ): Result<List<FileSimpleInfo>> {
        return try {
            val shareSession = checkNotNull(session) { AppStrings.message_task_share_session_expired }
            val result = shareSession.list(path).map { entries ->
                entries.map { entry ->
                    entry.withCopy(protocol = FileProtocol.Share, protocolId = id)
                }.filterNot { entry ->
                    isUnsafeRemoteListEntry(entry.name, entry.path)
                }
            }
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (error !is CancellationException) {
                    val message = AppStrings.ui_shared_list_returned_failure_arg0.format(
                        arg0 = (error?.message).toString()
                    )
                    if (shouldDisconnect(error)) {
                        LogKit.e(message, error)
                        handleError()
                    } else {
                        LogKit.w(message, error)
                    }
                }
            }
            result
        } catch (e: CancellationException) {
            Result.failure(e)
        } catch (e: Exception) {
            LogKit.e(AppStrings.ui_request_exception_for_shared_list, e)
            if (shouldDisconnect(e)) {
                handleError()
            }
            Result.failure(e)
        }
    }

    internal suspend fun getRootList(): Result<List<FileSimpleInfo>> {
        val shareSession = checkNotNull(session) { AppStrings.message_task_share_session_expired }
        return try {
            val result = shareSession.listRoots().map { entries ->
                entries.map { entry ->
                    entry.withCopy(protocol = FileProtocol.Share, protocolId = id)
                }.filterNot { entry ->
                    isUnsafeRemoteListEntry(entry.name, entry.path)
                }
            }
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (error !is CancellationException) {
                    val message = AppStrings.ui_shared_list_returned_failure_arg0.format(
                        arg0 = (error?.message).toString()
                    )
                    if (shouldDisconnect(error)) {
                        LogKit.e(message, error)
                        handleError()
                    } else {
                        LogKit.w(message, error)
                    }
                }
            }
            result
        } catch (error: CancellationException) {
            Result.failure(error)
        } catch (error: Exception) {
            LogKit.e(AppStrings.ui_request_exception_for_shared_list, error)
            if (shouldDisconnect(error)) handleError()
            Result.failure(error)
        }
    }

    suspend fun copyTo(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
    ): Result<Boolean> {
        return try {
            val shareSession = checkNotNull(session) { AppStrings.message_task_share_session_expired }
            require(destFileSimpleInfo.protocol == FileProtocol.Local) {
                AppStrings.ui_shared_files_as_read_only
            }
            check(shareSession.isActive) { AppStrings.message_task_share_session_expired }
            val copySource = checkNotNull(shareSession as? DeviceBackedShareSession) {
                AppStrings.ui_copy_failed
            }
            // 只借用 Device -> Local 的复制引擎，不注册为 Device，也不复用 Device UI。
            val result = Device(
                id = id,
                name = name,
                pathSeparator = pathSeparator,
                host = mutableMapOf(),
                type = type,
                token = "",
                pathClient = copySource.devicePathClient,
                fileClient = copySource.deviceFileClient,
            ).files.copyTo(
                task = task,
                srcFileSimpleInfo = srcFileSimpleInfo.withCopy(
                    protocol = FileProtocol.Device,
                    protocolId = id,
                ),
                destFileSimpleInfo = destFileSimpleInfo,
            )
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (shouldDisconnect(error)) {
                    LogKit.e(AppStrings.ui_shared_copy_failed_arg0.format(arg0 = (error?.message).toString()), error)
                    handleError()
                } else {
                    LogKit.w(AppStrings.ui_shared_copy_failed_arg0.format(arg0 = (error?.message).toString()), error)
                }
            }
            result
        } catch (e: Exception) {
            LogKit.e(AppStrings.ui_share_replication_request_exception, e)
            if (shouldDisconnect(e)) {
                handleError()
            }
            Result.failure(e)
        }
    }

    suspend fun readBytes(
        path: String,
        startOffset: Long,
        endOffset: Long,
    ): Result<ByteArray> {
        return try {
            val result = checkNotNull(session) { AppStrings.message_task_share_session_expired }
                .readBytes(path, startOffset, endOffset)
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (shouldDisconnect(error)) {
                    LogKit.e(AppStrings.ui_shared_read_range_failed_arg0.format(arg0 = (error?.message).toString()), error)
                    handleError()
                } else {
                    LogKit.w(AppStrings.ui_shared_read_range_failed_arg0.format(arg0 = (error?.message).toString()), error)
                }
            }
            result
        } catch (e: Exception) {
            LogKit.e(AppStrings.ui_shared_read_range_request_exception, e)
            if (shouldDisconnect(e)) {
                handleError()
            }
            Result.failure(e)
        }
    }

    fun traverse(
        path: String,
    ): Flow<Result<List<FileSimpleInfo>>> = flow {
        try {
            val results = traverseShareSession(
                this@Share,
                checkNotNull(session) { AppStrings.message_task_share_session_expired },
                path,
            )
            results.collect { result ->
                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                    if (shouldDisconnect(error)) {
                        LogKit.e(AppStrings.ui_shared_traversal_failed_arg0.format(arg0 = (error?.message).toString()), error)
                        handleError()
                    } else {
                        LogKit.w(AppStrings.ui_shared_traversal_failed_arg0.format(arg0 = (error?.message).toString()), error)
                    }
                }
                emit(result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogKit.e(AppStrings.ui_shared_traversal_request_exception, e)
            if (shouldDisconnect(e)) {
                handleError()
            }
            emit(Result.failure(e))
        }
    }
}
