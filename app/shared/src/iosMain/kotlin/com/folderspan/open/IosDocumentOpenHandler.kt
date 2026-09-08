@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.folderspan.open

import com.folderspan.data.file.FileProtocol
import com.folderspan.di.initKoin
import com.folderspan.ui.navigation.matchesScreen
import com.folderspan.ui.screen.file.share.FileShareScreen
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.ShareListDropResourceRegistry
import com.folderspan.ui.state.file.normalizeShareListDropFiles
import com.folderspan.ui.state.main.MainState
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.IosSecurityScopeStore
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatformTools
import platform.Foundation.NSArray
import platform.Foundation.NSURL
import strings.AppStrings

private val openScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

fun handleIosDocumentOpen(url: NSURL?) {
    handleIosDocumentOpenSources(
        urls = listOfNotNull(url),
        sourceType = IosOpenSource.Document,
    )
}

fun handleIosDocumentOpenUrls(urls: NSArray) {
    handleIosDocumentOpenSources(
        urls = urls.toNsUrlList(),
        sourceType = IosOpenSource.Document,
    )
}

fun handleIosShareExtensionFiles(urls: NSArray) {
    handleIosDocumentOpenSources(
        urls = urls.toNsUrlList(),
        sourceType = IosOpenSource.ShareExtension,
    )
}

private fun NSArray.toNsUrlList(): List<NSURL> = buildList {
    repeat(count.toInt()) { index ->
        (objectAtIndex(index.toULong()) as? NSURL)?.let(::add)
    }
}

private fun handleIosDocumentOpenSources(
    urls: List<NSURL>,
    sourceType: IosOpenSource,
) {
    val sources = urls.mapNotNull { sourceUrl ->
        val sourcePath = sourceUrl.path?.trim().orEmpty()
        sourcePath.takeIf { it.isNotEmpty() }?.let { path ->
            IosDocumentSource(sourceUrl, path)
        }
    }.distinctBy { source -> source.path }
    if (sources.isEmpty()) return

    initKoin()
    val koin = KoinPlatformTools.defaultContext().get()
    val fileShareState = koin.get<FileShareState>()
    val mainState = koin.get<MainState>()

    openScope.launch {
        val sourceResults = withContext(Dispatchers.Default) {
            sources.map { source ->
                IosSecurityScopeStore.register(source.url)
                source.path to FileUtils.getFile(FileAccessPermission.Allowed, source.path)
                    .onFailure { error ->
                        LogKit.e(AppStrings.ui_ios_external_file_reading_failed_type_arg0.format(arg0 = (error::class.simpleName.orEmpty()).toString()))
                    }
                    .getOrNull()
                    ?.withCopy(
                        protocol = FileProtocol.Local,
                        protocolId = "",
                    )
            }
        }
        val failedPaths = sourceResults.mapNotNull { (path, file) -> path.takeIf { file == null } }
        IosSecurityScopeStore.unregister(failedPaths)
        if (sourceType == IosOpenSource.ShareExtension) {
            failedPaths.forEach { path ->
                FileUtils.deleteFile(FileAccessPermission.Allowed, path)
            }
        }
        val openedFiles = sourceResults.mapNotNull { (_, file) -> file }
        if (openedFiles.isEmpty()) return@launch

        val retainedPaths = openedFiles.map { item -> item.path }
        fileShareState.updateIncomingFiles(normalizeShareListDropFiles(openedFiles))
        ShareListDropResourceRegistry.register(
            referencedPaths = fileShareState.incomingFiles.map { it.path },
            release = {
                IosSecurityScopeStore.unregister(retainedPaths)
                if (sourceType == IosOpenSource.ShareExtension) {
                    retainedPaths.forEach { path ->
                        FileUtils.deleteFile(FileAccessPermission.Allowed, path)
                    }
                }
            },
        )
        if (fileShareState.incomingFiles.isNotEmpty() &&
            !mainState.currentRoute.matchesScreen(FileShareScreen)
        ) {
            mainState.requestOpenScreen(FileShareScreen)
        }
    }
}

private data class IosDocumentSource(
    val url: NSURL,
    val path: String,
)

private enum class IosOpenSource {
    Document,
    ShareExtension,
}
