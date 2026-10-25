package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.PathInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.data.main.share.Share
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class FileStateDeskSwitcher(
    private val mainScope: CoroutineScope,
    private val fileBookmarkState: FileBookmarkState,
    private val currentDesk: () -> DiskBase,
    private val currentPath: () -> String,
    private val setDesk: (DiskBase) -> Unit,
    private val setPathDirectly: (String) -> Unit,
    private val setRootPath: (PathInfo) -> Unit,
    private val rememberPathForDesk: (DiskBase, String) -> Unit,
    private val rememberedPathForDesk: (DiskBase) -> String?,
    private val cancelListRequestsForDesk: suspend (DiskBase) -> Unit,
    private val getRootPaths: suspend () -> List<PathInfo>,
    private val updatePath: suspend (String) -> Unit,
    private val updateFileAndFolder: suspend () -> Unit,
) {
    fun updateDesk(
        protocol: FileProtocol,
        type: DiskBase,
        isRefresh: Boolean = true,
        pathOverride: String? = null,
    ) {
        keepProtocolForCallSiteCompatibility(protocol)
        val previousDesk = currentDesk()
        if (previousDesk == type) {
            val overridePath = resolveSameDeskPathOverride(currentPath(), pathOverride) ?: return
            if (!isRefresh) {
                setPathDirectly(overridePath)
                rememberPathForDesk(type, overridePath)
                return
            }
            mainScope.launch {
                updatePath(overridePath)
            }
            return
        }
        rememberPathForDesk(previousDesk, currentPath())
        setDesk(type)
        if (!isRefresh) return
        mainScope.launch {
            withContext(Dispatchers.Default) {
                cancelListRequestsForDesk(previousDesk)
                fileBookmarkState.load()
                val rememberedPath = resolveRememberedPath(
                    pathOverride,
                    rememberedPathForDesk(type),
                )
                val rootPaths = getRootPaths()
                if (rootPaths.isNotEmpty()) {
                    val rootPath = if (rememberedPath != null) {
                        rootPaths.firstOrNull { item -> rememberedPath.startsWith(item.path) } ?: rootPaths.first()
                    } else {
                        rootPaths.first()
                    }
                    setRootPath(rootPath)
                }

                if (type is Device) {
                    val homeBookmark =
                        fileBookmarkState.bookmarks.firstOrNull { item -> item.type == DrawerBookmarkType.Home }
                    if (homeBookmark != null) {
                        updatePath(homeBookmark.path)
                    } else {
                        if (rootPaths.isNotEmpty()) {
                            updatePath(rootPaths.first().path)
                        }
                    }
                }

                if (rememberedPath != null) {
                    updatePath(rememberedPath)
                    return@withContext
                }

                if (type is Share) {
                    updatePath("/")
                    return@withContext
                }

                if (type is NetworkAccess) {
                    val fallbackRoot = if (type is Network) type.pathSeparator else "/"
                    val rootPath = rootPaths.firstOrNull()?.path ?: fallbackRoot
                    updatePath(rootPath)
                    return@withContext
                }

                updateFileAndFolder()
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun keepProtocolForCallSiteCompatibility(protocol: FileProtocol) = Unit
}
