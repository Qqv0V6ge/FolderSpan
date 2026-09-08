package com.folderspan.share

import com.folderspan.utils.IosSecurityScopeStore
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
actual fun shareSystemItems(items: List<SystemShareItem>): Boolean {
    if (items.isEmpty()) return false

    val fileManager = NSFileManager.defaultManager
    data class ShareItem(
        val item: Any,
        val securityScopedUrl: NSURL?,
        val tempPath: String?,
    )
    val shareItems = items.mapNotNull { item ->
        val path = item.path.trim()
        if (path.isEmpty()) return@mapNotNull null
        val normalizedPath = path.removePrefix("file://")
        val rawUrl = if (path.startsWith("file://")) NSURL.URLWithString(path) else null
        val decodedPath = rawUrl?.path ?: normalizedPath
        val scopedUrl = IosSecurityScopeStore.resolve(decodedPath)
            ?: IosSecurityScopeStore.resolve(normalizedPath)
        val fallbackUrl = rawUrl ?: NSURL.fileURLWithPath(normalizedPath, isDirectory = item.isDirectory)
        val resolvedUrl = scopedUrl ?: fallbackUrl
        val filePath = resolvedUrl.path ?: return@mapNotNull null
        val securityKey = scopedUrl?.path ?: decodedPath
        val exists = IosSecurityScopeStore.withSecurityScopeIfNeeded(securityKey) {
            fileManager.fileExistsAtPath(filePath)
        }
        if (!exists) return@mapNotNull null
        val displayName = resolveDisplayName(item.displayName, filePath)
        if (item.isDirectory) {
            ShareItem(
                item = resolvedUrl,
                securityScopedUrl = scopedUrl,
                tempPath = null,
            )
        } else {
            val tempShareUrl = copyToTempShareUrl(fileManager, filePath, displayName, securityKey)
            if (tempShareUrl != null) {
                ShareItem(
                    item = tempShareUrl,
                    securityScopedUrl = null,
                    tempPath = tempShareUrl.path,
                )
            } else {
                val data = IosSecurityScopeStore.withSecurityScopeIfNeeded(securityKey) {
                    NSData.dataWithContentsOfFile(filePath)
                }
                if (data != null) {
                    ShareItem(
                        item = data,
                        securityScopedUrl = null,
                        tempPath = null,
                    )
                } else {
                    ShareItem(
                        item = resolvedUrl,
                        securityScopedUrl = scopedUrl,
                        tempPath = null,
                    )
                }
            }
        }
    }

    if (shareItems.isEmpty()) return false
    val itemsToShare = shareItems.map { shareItem -> shareItem.item }
    val present = present@{
        val rootController = topViewController() ?: return@present
        val accessedUrls = mutableListOf<NSURL>()
        shareItems.mapNotNull { item ->  item.securityScopedUrl }.forEach { scoped ->
            val accessed = scoped.startAccessingSecurityScopedResource()
            if (accessed) accessedUrls.add(scoped)
        }
        val activityController = UIActivityViewController(activityItems = itemsToShare, applicationActivities = null)
        activityController.completionWithItemsHandler = { _, _, _, _ ->
            accessedUrls.forEach { url -> url.stopAccessingSecurityScopedResource() }
            shareItems.mapNotNull { item ->  item.tempPath }.forEach { tempPath ->
                fileManager.removeItemAtPath(tempPath, error = null)
            }
        }
        activityController.popoverPresentationController?.let { popover ->
            popover.sourceView = rootController.view
            popover.sourceRect = rootController.view.bounds
        }
        rootController.presentViewController(activityController, animated = true, completion = null)
    }
    dispatch_async(dispatch_get_main_queue()) { present() }
    return true
}

private fun resolveDisplayName(displayName: String, filePath: String): String {
    val trimmed = displayName.trim()
    if (trimmed.isNotEmpty()) return trimmed
    val fallbackName = filePath.substringAfterLast('/').trim()
    return fallbackName.ifEmpty { "share" }
}

@OptIn(ExperimentalForeignApi::class)
private fun copyToTempShareUrl(
    fileManager: NSFileManager,
    filePath: String,
    displayName: String,
    securityKey: String,
): NSURL? {
    val sanitizedName = displayName.trim().replace("/", "_")
    val baseName = sanitizedName.ifEmpty {
        filePath.substringAfterLast('/').ifEmpty { "share" }
    }
    val uniqueName = "${NSUUID().UUIDString()}-$baseName"
    val tempDir = NSTemporaryDirectory().trimEnd('/')
    val tempPath = "$tempDir/$uniqueName"
    val copied = IosSecurityScopeStore.withSecurityScopeIfNeeded(securityKey) {
        if (!fileManager.fileExistsAtPath(tempDir)) {
            fileManager.createDirectoryAtPath(tempDir, true, attributes = null, error = null)
        }
        if (fileManager.fileExistsAtPath(tempPath)) {
            fileManager.removeItemAtPath(tempPath, error = null)
        }
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val success = fileManager.copyItemAtPath(filePath, tempPath, error.ptr)
            if (!success) {
                fileManager.removeItemAtPath(tempPath, error = null)
            }
            success
        }
    }
    return if (copied) NSURL.fileURLWithPath(tempPath, isDirectory = false) else null
}

private fun topViewController(): UIViewController? {
    val application = UIApplication.sharedApplication
    val activeScenes = application.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .filter { scene -> scene.activationState == UISceneActivationStateForegroundActive }
    val sceneWindows = activeScenes
        .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
    val appWindows = application.windows.filterIsInstance<UIWindow>()
    val windows = sceneWindows + appWindows
    val keyWindow = activeScenes.firstNotNullOfOrNull { item -> item.keyWindow }
        ?: application.keyWindow
        ?: windows.firstOrNull { item ->  item.isKeyWindow() }
        ?: windows.firstOrNull()
    var controller = keyWindow?.rootViewController
    while (true) {
        val presented = controller?.presentedViewController ?: break
        controller = presented
    }
    return controller
}
