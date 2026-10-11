package com.folderspan.ui.state.file

import com.folderspan.data.StatusEnum
import com.folderspan.extensions.formatBytes
import com.folderspan.extensions.formatPercent
import com.folderspan.service.http.clipboard.ClipboardStagedDownload
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadDraft
import com.folderspan.service.http.clipboard.ClipboardUrlDownloader
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadError
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadPlatformCapabilities
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadProgress
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadResult
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadState
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadStateMachine
import com.folderspan.service.http.clipboard.ClipboardUrlDraftConfirmResult
import com.folderspan.service.http.clipboard.ClipboardUrlDraftOpenResult
import com.folderspan.service.http.clipboard.clipboardUrlDownloadPlatformCapabilities
import com.folderspan.service.http.clipboard.clipboardUrlTargetSummary
import com.folderspan.service.http.clipboard.defaultClipboardUrlUserAgent
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TASK_PROGRESS_CUR_VALUE_KEY
import com.folderspan.ui.state.main.TASK_PROGRESS_MAX_VALUE_KEY
import com.folderspan.ui.state.main.TaskRuntimeStoreLock
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import strings.AppStrings

fun interface ClipboardUrlDownloadResultPresenter {
    fun present(staged: ClipboardStagedDownload): Boolean
}

class ClipboardUrlDownloadCoordinator(
    private val engine: ClipboardUrlDownloader,
    private val taskState: TaskState,
    private val resultPresenter: ClipboardUrlDownloadResultPresenter,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val capabilities: ClipboardUrlDownloadPlatformCapabilities =
        clipboardUrlDownloadPlatformCapabilities,
    private val stateMachine: ClipboardUrlDownloadStateMachine = ClipboardUrlDownloadStateMachine(),
) {
    private val lock = TaskRuntimeStoreLock()
    private var activeTaskKey: Long? = null
    private var generation = 0L
    private class CopyDelivery(
        val copy: suspend (Task, ClipboardStagedDownload) -> Result<Boolean>,
        val completion: CompletableDeferred<Boolean> = CompletableDeferred(),
    )
    private var copyDelivery: CopyDelivery? = null

    val state = stateMachine.state

    fun openUrl(rawText: String, url: String): ClipboardUrlDraftOpenResult = withLock {
        openDraft(rawText, url)
    }

    private fun openDraft(rawText: String, url: String): ClipboardUrlDraftOpenResult =
        stateMachine.openDraft(
            ClipboardUrlDownloadDraft(
                rawText = rawText,
                url = url,
                targetSummary = clipboardUrlTargetSummary(url),
                userAgent = defaultClipboardUrlUserAgent(capabilities = capabilities),
                capabilities = capabilities,
            )
        )

    /** 文件列表中的 URL 条目在粘贴时配置下载，确认后在同一任务中交付到选定目标。 */
    suspend fun downloadForCopy(
        url: String,
        copy: suspend (Task, ClipboardStagedDownload) -> Result<Boolean>,
    ): Boolean {
        val delivery = CopyDelivery(copy)
        val opened = withLock {
            if (stateMachine.state.value.isActive()) return@withLock false
            copyDelivery = delivery
            openDraft(url, url) == ClipboardUrlDraftOpenResult.Opened
        }
        if (!opened) return false
        return try {
            delivery.completion.await()
        } catch (error: CancellationException) {
            if (withLock { copyDelivery === delivery }) cancel()
            throw error
        }
    }

    fun showContent(rawText: String): ClipboardUrlDraftOpenResult = withLock {
        if (stateMachine.state.value.isActive()) return@withLock ClipboardUrlDraftOpenResult.AlreadyRunning
        stateMachine.fallback(rawText = rawText, error = null)
        ClipboardUrlDraftOpenResult.Opened
    }

    fun confirm(draft: ClipboardUrlDownloadDraft): ClipboardUrlDraftConfirmResult {
        val result = withLock { stateMachine.confirm(draft) }
        if (result !is ClipboardUrlDraftConfirmResult.Confirmed) return result

        val task = Task(
            taskType = TaskType.Download,
            status = StatusEnum.LOADING,
            values = mapOf(
                "path" to draft.targetSummary,
                TASK_PROGRESS_CUR_VALUE_KEY to "0",
                TASK_PROGRESS_MAX_VALUE_KEY to "100",
            ),
        )
        val taskGeneration = withLock {
            generation += 1
            activeTaskKey = task.key
            generation
        }
        val delivery = withLock { copyDelivery }
        taskState.addOrUpdate(task)
        taskState.registerTaskHandler(task) {
            runDownload(taskGeneration, task, result, delivery)
        }
        return result
    }

    fun cancel() {
        val activeTask = withLock {
            generation += 1
            val task = activeTaskKey?.let(taskState::getTask)
            activeTaskKey = null
            copyDelivery?.completion?.complete(false)
            copyDelivery = null
            stateMachine.cancel()
            task
        }
        if (activeTask?.status == StatusEnum.LOADING || activeTask?.status == StatusEnum.PAUSE) {
            taskState.requestCancel(activeTask)
        }
    }

    fun dismiss() {
        val active = withLock { stateMachine.state.value.isActive() }
        if (active) cancel() else withLock { stateMachine.dismiss() }
    }

    fun teardown() {
        cancel()
        ClipboardUrlShareFiles.clear()
    }

    private suspend fun runDownload(
        taskGeneration: Long,
        task: Task,
        confirmed: ClipboardUrlDraftConfirmResult.Confirmed,
        delivery: CopyDelivery?,
    ) {
        var metricsStarted = false
        try {
            val result = engine.download(
                config = confirmed.config,
                onProgress = { progress ->
                    withContext(uiDispatcher) {
                        ifCurrent(taskGeneration) {
                            metricsStarted = publishTaskProgress(task, progress, metricsStarted)
                            stateMachine.downloading(progress)
                        }
                    }
                },
                onRetry = { attempt, delayMillis ->
                    withContext(uiDispatcher) {
                        ifCurrent(taskGeneration) {
                            taskState.putTransientResult(
                                task,
                                task.values["path"].orEmpty(),
                                AppStrings.ui_clipboard_url_download_retrying_arg0.format(arg0 = attempt.toString()),
                            )
                            stateMachine.retryWaiting(attempt, delayMillis)
                        }
                    }
                },
            )
            if (result is ClipboardUrlDownloadResult.Success && delivery != null) {
                val copied = try {
                    if (withLock { generation == taskGeneration }) delivery.copy(task, result.staged)
                    else Result.success(false)
                } finally {
                    result.staged.release()
                }
                withContext(uiDispatcher) {
                    ifCurrent(taskGeneration) {
                        if (copied.getOrDefault(false)) {
                            taskState.delete(task)
                            stateMachine.succeeded(result.staged.displayName, result.staged.size)
                            delivery.completion.complete(true)
                        } else {
                            failTask(task, confirmed.config.rawText, ClipboardUrlDownloadError.StagingFailure,
                                AppStrings.ui_file_copy_failed)
                        }
                        activeTaskKey = null
                    }
                }
                return
            }
            if (result is ClipboardUrlDownloadResult.Success && !withLock { generation == taskGeneration }) {
                result.staged.release()
                return
            }
            withContext(uiDispatcher) {
                ifCurrent(taskGeneration) {
                    when (result) {
                        is ClipboardUrlDownloadResult.Success -> deliver(task, result, confirmed.config.rawText)
                        is ClipboardUrlDownloadResult.Failure -> failTask(
                            task = task,
                            rawText = result.rawText,
                            error = result.error,
                            safeReason = result.safeReason,
                        )
                    }
                    activeTaskKey = null
                }
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable + uiDispatcher) {
                ifCurrent(taskGeneration) {
                    stateMachine.cancel()
                    activeTaskKey = null
                }
            }
            throw error
        } catch (_: Throwable) {
            withContext(NonCancellable + uiDispatcher) {
                ifCurrent(taskGeneration) {
                    failTask(
                        task = task,
                        rawText = confirmed.config.rawText,
                        error = ClipboardUrlDownloadError.Unknown,
                        safeReason = AppStrings.ui_download_task_failed,
                    )
                    activeTaskKey = null
                }
            }
        } finally {
            confirmed.config.clearSensitive()
            delivery?.completion?.complete(false)
            withLock {
                if (copyDelivery === delivery) copyDelivery = null
            }
        }
    }

    private fun publishTaskProgress(
        task: Task,
        progress: ClipboardUrlDownloadProgress,
        metricsStarted: Boolean,
    ): Boolean {
        val totalBytes = progress.totalBytes
        val receivedBytes = progress.bytesReceived.coerceAtLeast(0L)
        // 通用 Task 的进度字段是 Int；按百分比填充，原始 Long 字节数保留在运行指标和消息中。
        val progressPercent = if (totalBytes != null && totalBytes > 0L) {
            ((receivedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }
        taskState.putValues(task, listOf(
            TASK_PROGRESS_CUR_VALUE_KEY to progressPercent.toString(),
            TASK_PROGRESS_MAX_VALUE_KEY to "100",
        ))
        if (totalBytes != null && totalBytes > 0L) {
            if (!metricsStarted) {
                taskState.beginRuntimeByteMetrics(task, totalBytes)
                taskState.startRuntimeByteItem(task, TASK_DOWNLOAD_PROGRESS_PATH, totalBytes)
            } else {
                taskState.updateRuntimeByteMetrics(task, totalBytes)
            }
            taskState.putRuntimeByteProgress(task, TASK_DOWNLOAD_PROGRESS_PATH, progress.bytesReceived)
        }
        taskState.putRuntimeParallelCount(task, progress.activeSegments)
        val statusMessage = if (progress.fellBackToSingleThread) {
            AppStrings.ui_clipboard_url_download_task_single_thread_arg0_arg1.format(
                arg0 = progress.attempt.toString(),
                arg1 = progress.maxAttempts.toString(),
            )
        } else {
            AppStrings.ui_clipboard_url_download_task_progress_arg0_arg1.format(
                arg0 = progress.attempt.toString(),
                arg1 = progress.maxAttempts.toString(),
            )
        }
        val bytesMessage = if (totalBytes != null && totalBytes > 0L) {
            AppStrings.ui_clipboard_url_download_received_arg0_arg1_arg2.format(
                arg0 = receivedBytes.formatBytes(),
                arg1 = totalBytes.formatBytes(),
                arg2 = receivedBytes.formatPercent(totalBytes),
            )
        } else {
            AppStrings.ui_clipboard_url_download_received_unknown_arg0.format(arg0 = receivedBytes.formatBytes())
        }
        taskState.putTransientResult(task, task.values["path"].orEmpty(), "$bytesMessage\n$statusMessage")
        return metricsStarted || totalBytes != null && totalBytes > 0L
    }

    private fun deliver(task: Task, result: ClipboardUrlDownloadResult.Success, rawText: String) {
        val staged = result.staged
        var released = false
        val releaseOnce = {
            if (!released) {
                released = true
                staged.release()
            }
        }
        val presentedStaged = staged.copy(release = releaseOnce)
        val accepted = runCatching { resultPresenter.present(presentedStaged) }.getOrDefault(false)
        if (!accepted) {
            releaseOnce()
            failTask(
                task = task,
                rawText = rawText,
                error = ClipboardUrlDownloadError.StagingFailure,
                safeReason = AppStrings.ui_download_completed_but_the_temporary_file_cannot_be_opened,
            )
            return
        }
        taskState.delete(task)
        stateMachine.succeeded(staged.displayName, staged.size)
    }

    private fun failTask(
        task: Task,
        rawText: String,
        error: ClipboardUrlDownloadError,
        safeReason: String?,
    ) {
        val reason = safeReason.orEmpty().ifBlank { AppStrings.ui_download_task_failed }
        taskState.putResult(task, task.values["path"].orEmpty(), reason)
        taskState.updateStatus(task, StatusEnum.FAILURE)
        taskState.clearSignals(task.key)
        stateMachine.fallback(rawText = rawText, error = error, safeReason = reason)
    }

    private fun ifCurrent(taskGeneration: Long, block: () -> Unit) = withLock {
        if (generation == taskGeneration) block()
    }

    private inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val TASK_DOWNLOAD_PROGRESS_PATH = "download"
    }
}

private fun ClipboardUrlDownloadState.isActive(): Boolean = when (this) {
    is ClipboardUrlDownloadState.Configuring,
    is ClipboardUrlDownloadState.Inspecting,
    is ClipboardUrlDownloadState.Downloading,
    is ClipboardUrlDownloadState.RetryWaiting -> true

    ClipboardUrlDownloadState.Idle,
    is ClipboardUrlDownloadState.Succeeded,
    is ClipboardUrlDownloadState.Fallback,
    ClipboardUrlDownloadState.Cancelled -> false
}
