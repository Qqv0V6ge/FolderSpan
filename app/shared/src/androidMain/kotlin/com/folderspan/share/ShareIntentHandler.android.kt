package com.folderspan.share

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.DragAndDropPermissions
import android.view.DragEvent
import com.folderspan.clipboard.ClipboardTextOpenBus
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ui.navigation.AppRoute
import com.folderspan.ui.navigation.matchesScreen
import com.folderspan.ui.screen.file.share.FileShareScreen
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.ShareListDropResourceRegistry
import com.folderspan.ui.state.file.normalizeShareListDropFiles
import com.folderspan.ui.state.main.MainState
import com.folderspan.utils.ShareHandler

/**
 * 负责处理所有入口 Intent 中的分享/查看操作。
 */
class ShareIntentHandler(
    private val activity: Activity,
    private val fileState: FileState,
    private val fileShareState: FileShareState,
    private val mainState: MainState,
) {
    private val heldDropPermissions = mutableListOf<DragAndDropPermissions>()
    private var preparedShareIntent: Intent? = null

    fun hasShareData(intent: Intent?): Boolean {
        return intent != null && when (intent.action) {
            Intent.ACTION_SEND -> hasSendData(intent)
            Intent.ACTION_SEND_MULTIPLE -> hasSendMultipleData(intent)
            Intent.ACTION_VIEW -> hasGrantedContentViewUri(intent)
            else -> false
        }
    }

    fun prepare(intent: Intent?) {
        if (!hasShareData(intent) || preparedShareIntent === intent) return
        preparedShareIntent = intent
        fileState.beginExternalShareHandling()
    }

    fun opensFileShareScreen(intent: Intent?): Boolean {
        return intent != null && when (intent.action) {
            Intent.ACTION_SEND ->
                grantedShareUri(intent, intent.getParcelableUriExtra(Intent.EXTRA_STREAM)) != null
            Intent.ACTION_SEND_MULTIPLE -> hasSendMultipleData(intent)
            Intent.ACTION_VIEW -> hasGrantedContentViewUri(intent)
            else -> false
        }
    }

    fun handle(intent: Intent?): Boolean {
        if (!hasShareData(intent)) {
            return false
        }
        val isPrepared = preparedShareIntent === intent
        preparedShareIntent = null
        if (!isPrepared) {
            fileState.beginExternalShareHandling()
        }
        var completed = false
        fun complete() {
            if (completed) return
            completed = true
            fileState.endExternalShareHandling()
        }

        val submitted = runCatching {
            when (intent?.action) {
                Intent.ACTION_SEND -> handleSend(intent) { complete() }
                Intent.ACTION_SEND_MULTIPLE -> handleSendMultiple(intent) { complete() }
                Intent.ACTION_VIEW -> handleView(intent) { complete() }
                else -> false
            }
        }.getOrElse { error ->
            complete()
            throw error
        }
        if (!submitted) {
            complete()
        }
        return true
    }

    /**
     * 处理系统拖拽事件（跨应用拖拽）。
     */
    fun handleDragEvent(event: DragEvent): Boolean {
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                event.clipDescription != null || event.clipData != null
            }

            DragEvent.ACTION_DROP -> {
                val uris = extractGrantedContentUris(event.clipData)
                if (uris.isEmpty()) {
                    return ClipboardTextOpenBus.publish(extractSharedClipText(event.clipData).orEmpty())
                }
                val permission = requestDropPermissions(event) ?: return false
                ShareHandler.handleDroppedFilesForShare(activity, fileState, uris) { result ->
                    if (!result.deliveredToShareList && result.files.isNotEmpty()) {
                        deliverFilesToShareScreen(result.files)
                    }
                    if (result.retainPermission) {
                        heldDropPermissions += permission
                    } else {
                        runCatching { permission.release() }
                    }
                }
                true
            }

            else -> false
        }
    }

    /**
     * 响应 ACTION_SEND：区分文本分享及单文件分享。
     */
    private fun handleSend(intent: Intent, onComplete: () -> Unit): Boolean {
        val uri = grantedShareUri(intent, intent.getParcelableUriExtra(Intent.EXTRA_STREAM))
        if (uri != null) {
            handleSharedFiles(listOf(uri), onComplete)
            return true
        }
        val sharedText = extractSharedIntentText(intent) ?: return false
        openHomeScreen()
        val submitted = ClipboardTextOpenBus.publish(sharedText)
        onComplete()
        return submitted
    }

    /**
     * 响应 ACTION_SEND_MULTIPLE：批量导入分享的文件列表。
     */
    private fun handleSendMultiple(intent: Intent, onComplete: () -> Unit): Boolean {
        val uris = grantedShareUris(intent, intent.getParcelableUriListExtra(Intent.EXTRA_STREAM))
        if (uris.isEmpty()) return false
        handleSharedFiles(uris, onComplete)
        return true
    }

    /**
     * 响应 ACTION_VIEW：与系统分享相同，把文件送进分享列表。
     */
    private fun handleView(intent: Intent, onComplete: () -> Unit): Boolean {
        if (!hasGrantedContentViewUri(intent)) return false
        val uri = intent.data ?: return false
        handleSharedFiles(listOf(uri), onComplete)
        return true
    }

    private fun hasGrantedContentViewUri(intent: Intent): Boolean {
        return grantedShareUri(intent, intent.data) != null
    }

    private fun grantedShareUri(intent: Intent, uri: Uri?): Uri? {
        if (uri == null) return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return uri.takeIf { hasSystemUriGrant(uri) }
    }

    private fun hasSystemUriGrant(uri: Uri): Boolean {
        return activity.checkUriPermission(
            uri,
            android.os.Process.myPid(),
            android.os.Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun grantedShareUris(intent: Intent, uris: List<Uri>?): List<Uri> {
        if (uris.isNullOrEmpty()) return emptyList()
        return uris.mapNotNull { uri -> grantedShareUri(intent, uri) }
    }

    private fun handleSharedFiles(uris: List<Uri>, onComplete: () -> Unit) {
        ShareHandler.handleSharedFilesForShare(activity, fileState, uris) { files ->
            deliverFilesToShareScreen(files)
            onComplete()
        }
    }

    private fun deliverFilesToShareScreen(files: List<FileSimpleInfo>) {
        fileShareState.updateIncomingFiles(normalizeShareListDropFiles(files))
        if (fileShareState.incomingFiles.isNotEmpty() &&
            !mainState.currentRoute.matchesScreen(FileShareScreen)
        ) {
            mainState.requestOpenScreen(FileShareScreen)
        }
    }

    private fun openHomeScreen() {
        if (mainState.currentRoute != AppRoute.Home) {
            mainState.requestOpenScreen(AppRoute.Home)
        }
    }

    private fun hasSendData(intent: Intent): Boolean =
        grantedShareUri(intent, intent.getParcelableUriExtra(Intent.EXTRA_STREAM)) != null ||
            extractSharedIntentText(intent) != null

    private fun hasSendMultipleData(intent: Intent): Boolean {
        return grantedShareUris(intent, intent.getParcelableUriListExtra(Intent.EXTRA_STREAM)).isNotEmpty()
    }

    private fun requestDropPermissions(event: DragEvent): DragAndDropPermissions? {
        return runCatching { activity.requestDragAndDropPermissions(event) }.getOrNull()
    }

    private fun extractGrantedContentUris(clipData: ClipData?): List<Uri> {
        if (clipData == null) return emptyList()
        val uris = mutableListOf<Uri>()
        for (index in 0 until clipData.itemCount) {
            val uri = clipData.getItemAt(index).uri ?: continue
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                uris += uri
            }
        }
        return uris.distinctBy { item -> item.toString() }
    }

    fun releaseDropPermissions() {
        if (preparedShareIntent != null) {
            fileState.endExternalShareHandling()
            preparedShareIntent = null
        }
        heldDropPermissions.forEach { permission ->
            runCatching { permission.release() }
        }
        heldDropPermissions.clear()
        ShareListDropResourceRegistry.releaseAll()
    }

    /**
     * 兼容 API <33 的 getParcelableExtra。
     */
    private fun Intent.getParcelableUriExtra(name: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }

    /**
     * 兼容 API <33 的 getParcelableArrayListExtra。
     */
    private fun Intent.getParcelableUriListExtra(name: String): ArrayList<Uri>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(name, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra(name)
        }
    }
}

// 不使用 coerceToText，避免把无权限 content URI 当作普通文本打开。
internal fun extractSharedClipText(clipData: ClipData?): String? {
    if (clipData == null) return null
    return (0 until clipData.itemCount).mapNotNull { index ->
        val item = clipData.getItemAt(index)
        item.text?.toString()?.takeIf(String::isNotBlank)
            ?: item.uri?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
    }.joinToString("\n").takeIf(String::isNotBlank)
}

internal fun extractSharedIntentText(intent: Intent): String? =
    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf(String::isNotBlank)
        ?: extractSharedClipText(intent.clipData)
