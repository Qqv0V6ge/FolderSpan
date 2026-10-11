package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.PathInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.data.main.share.Share
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.theme.toColorScheme
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class FileStateDeskSwitcher(
    private val mainScope: CoroutineScope,
    private val fileBookmarkState: FileBookmarkState,
    private val deviceState: DeviceState,
    private val mainState: MainState,
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
                    applyRemoteDeviceTheme(type)

                    val homeBookmark =
                        fileBookmarkState.bookmarks.firstOrNull { item -> item.type == DrawerBookmarkType.Home }
                    if (homeBookmark != null) {
                        updatePath(homeBookmark.path)
                    } else {
                        if (rootPaths.isNotEmpty()) {
                            updatePath(rootPaths.first().path)
                        }
                    }
                } else {
                    mainState.setRemoteColorScheme(null)
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

    private suspend fun applyRemoteDeviceTheme(type: Device) {
        try {
            val isDark = mainState.currentDarkTheme.value
            val themeResult = deviceState.getRemoteDeviceTheme(type, isDark)

            if (themeResult == null) {
                LogKit.w(AppStrings.ui_there_currently_no_available_topic_channels_remote_device_arg0.format(arg0 = type.id))
                mainState.setRemoteColorScheme(null)
            } else {
                themeResult.onSuccess { response ->
                    val colorScheme = response.colorScheme.toColorScheme()
                    LogKit.i(AppStrings.ui_get_remote_device_colorscheme_isdark_arg0_apply_it.format(arg0 = (isDark).toString()))
                    mainState.setRemoteColorScheme(colorScheme)
                }.onFailure { e ->
                    LogKit.w(AppStrings.ui_failed_get_remote_device_topic_arg0.format(arg0 = (e.message).toString()), e)
                    mainState.setRemoteColorScheme(null)
                }
            }
        } catch (e: Exception) {
            LogKit.e(AppStrings.ui_get_remote_device_topic_exception_arg0.format(arg0 = (e.message).toString()), e)
            mainState.setRemoteColorScheme(null)
        }
    }
}
