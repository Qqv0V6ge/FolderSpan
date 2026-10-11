@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.folderspan.clipboard

import com.folderspan.open.releaseIosPreparedExternalSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSArray
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL

internal const val IOS_CLIPBOARD_FILE_PASTE_REQUEST_NOTIFICATION =
    "com.folderspan.clipboard.file-paste-request"

internal data class IosClipboardFileProviderResponse(
    val urls: List<NSURL>,
    val representedItemCount: Int,
    val ownsStagedUrls: Boolean,
)

private var pendingIosClipboardFileResponse: CompletableDeferred<IosClipboardFileProviderResponse>? = null

internal suspend fun requestIosClipboardFileProviderResponse(): IosClipboardFileProviderResponse? {
    val response = CompletableDeferred<IosClipboardFileProviderResponse>()
    pendingIosClipboardFileResponse?.complete(
        IosClipboardFileProviderResponse(emptyList(), 0, ownsStagedUrls = false)
    )
    pendingIosClipboardFileResponse = response
    NSNotificationCenter.defaultCenter.postNotificationName(
        aName = IOS_CLIPBOARD_FILE_PASTE_REQUEST_NOTIFICATION,
        `object` = null,
    )
    return withTimeoutOrNull(15_000L) { response.await() }.also {
        if (pendingIosClipboardFileResponse === response) {
            pendingIosClipboardFileResponse = null
        }
    }
}

fun completeIosClipboardFileProviderRequest(
    urls: NSArray,
    representedItemCount: Int,
) {
    val preparedUrls = buildList {
        repeat(urls.count.toInt()) { index ->
            (urls.objectAtIndex(index.toULong()) as? NSURL)?.let(::add)
        }
    }
    val pending = pendingIosClipboardFileResponse
    if (pending == null) {
        preparedUrls.forEach { url ->
            url.path?.let(::releaseIosPreparedExternalSource)
        }
        return
    }
    pending.complete(
        IosClipboardFileProviderResponse(
            urls = preparedUrls,
            representedItemCount = representedItemCount.coerceAtLeast(preparedUrls.size),
            ownsStagedUrls = true,
        )
    )
}
