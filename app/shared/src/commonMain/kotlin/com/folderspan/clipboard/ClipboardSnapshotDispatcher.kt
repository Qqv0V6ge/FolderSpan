package com.folderspan.clipboard

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.receiveAsFlow

sealed interface ClipboardTextDispatch {
    data object Empty : ClipboardTextDispatch

    data class ExistingPaths(
        val paths: List<String>,
    ) : ClipboardTextDispatch

    data class DownloadUrl(
        val url: String,
        val rawText: String,
    ) : ClipboardTextDispatch

    data class ShowContent(
        val rawText: String,
    ) : ClipboardTextDispatch
}

internal fun dispatchClipboardTextSnapshot(
    parsed: ClipboardParseResult,
    resolvedPaths: List<String>,
): ClipboardTextDispatch {
    if (resolvedPaths.isNotEmpty()) {
        return ClipboardTextDispatch.ExistingPaths(resolvedPaths)
    }
    parsed.standaloneHttpUrl?.let { url ->
        return ClipboardTextDispatch.DownloadUrl(url = url, rawText = parsed.rawText)
    }
    if (parsed.rawText.isNotBlank()) {
        return ClipboardTextDispatch.ShowContent(parsed.rawText)
    }
    return ClipboardTextDispatch.Empty
}

object ClipboardTextPasteBus {
    private val mutableEvents = MutableSharedFlow<ClipboardContent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ClipboardContent> = mutableEvents

    fun publish(content: ClipboardContent): Boolean {
        return !(content.texts.all(String::isBlank) && content.filePaths.isEmpty()) && mutableEvents.tryEmit(
            ClipboardContent(
                texts = content.texts.toList(),
                filePaths = content.filePaths.toList(),
            )
        )
    }
}

/** 将外部拖入或分享的文本交给“从剪贴板打开”，保留接收时的内容。 */
object ClipboardTextOpenBus {
    private val channel = Channel<ClipboardContent>(capacity = 8)
    val events = channel.receiveAsFlow()

    fun publish(text: String): Boolean =
        text.isNotBlank() && channel.trySend(ClipboardContent(texts = listOf(text))).isSuccess
}
