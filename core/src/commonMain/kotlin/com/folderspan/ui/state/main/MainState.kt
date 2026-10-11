package com.folderspan.ui.state.main

import androidx.compose.material3.ColorScheme
import com.folderspan.ui.navigation.AppNavigator
import com.folderspan.ui.navigation.AppRoute
import com.folderspan.ui.navigation.AppRoutePayloadStore
import com.folderspan.ui.navigation.AppScreenRoute
import com.folderspan.ui.navigation.toAppRoute
import com.folderspan.utils.WindowSizeClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ColorSchemes(
    val light: ColorScheme,
    val dark: ColorScheme
)

class MainState {
    // 是否展开抽屉的状态流
    private val _isExpandDrawer: MutableStateFlow<Boolean> = MutableStateFlow(true)
    val isExpandDrawer: StateFlow<Boolean> = _isExpandDrawer

    // 更新是否展开抽屉的方法
    fun updateExpandDrawer(value: Boolean) {
        _isExpandDrawer.value = value
    }

    private var usesModalNavigationDrawer: Boolean = false

    fun updateNavigationDrawerPresentation(usesModalDrawer: Boolean) {
        usesModalNavigationDrawer = usesModalDrawer
    }

    fun collapseDrawerAfterNavigation() {
        if (windowSize == WindowSizeClass.Compact || usesModalNavigationDrawer) {
            updateExpandDrawer(false)
        }
    }

    fun pushScreen(route: AppRoute) {
        val activeNavigator = navigator
        collapseDrawerAfterNavigation()
        if (activeNavigator == null) {
            queuePendingRoute(route)
            return
        }
        activeNavigator.push(route)
    }

    fun pushScreen(screen: AppScreenRoute) {
        pushScreen(screen.toAppRoute())
    }

    // 是否为编辑路径状态的状态流
    private val _isEditPath: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isEditPath: StateFlow<Boolean> = _isEditPath

    // 更新是否为编辑路径状态的方法
    fun updateEditPath(value: Boolean) {
        _isEditPath.value = value
    }

    // 远程设备ColorScheme（临时状态，不持久化）
    private val _remoteColorScheme = MutableStateFlow<ColorScheme?>(null)
    val remoteColorScheme: StateFlow<ColorScheme?> = _remoteColorScheme

    fun setRemoteColorScheme(colorScheme: ColorScheme?) {
        _remoteColorScheme.value = colorScheme
    }

    // 当前设备的light和dark ColorScheme（用于服务端API返回）
    private val _currentColorSchemes = MutableStateFlow<ColorSchemes?>(null)
    val currentColorSchemes: StateFlow<ColorSchemes?> = _currentColorSchemes

    fun setCurrentColorSchemes(schemes: ColorSchemes) {
        _currentColorSchemes.value = schemes
    }

    // 当前暗黑模式状态
    private val _currentDarkTheme = MutableStateFlow(false)
    val currentDarkTheme: StateFlow<Boolean> = _currentDarkTheme

    fun setCurrentDarkTheme(isDark: Boolean) {
        _currentDarkTheme.value = isDark
    }

    var windowSize: WindowSizeClass = WindowSizeClass.Expanded

    var navigator: AppNavigator? = null
        private set

    val currentRoute: AppRoute?
        get() = navigator?.currentRoute

    private var pendingRoute: AppRoute? = null

    fun updateNavigator(navigator: AppNavigator) {
        this.navigator = navigator
        val route = pendingRoute
        if (route != null) {
            pendingRoute = null
            pushScreen(route)
        }
    }

    /**
     * 仅当 [navigator] 仍是当前挂载实例时才将其注销。
     *
     * Compose 重建导航宿主时，新实例可能先挂载，旧实例随后才执行 onDispose。身份校验可以避免
     * 旧实例的延迟销毁回调误清除新实例。
     */
    fun clearNavigator(navigator: AppNavigator): Boolean {
        val activeNavigator = this.navigator
        if (activeNavigator !== navigator) {
            return false
        }

        this.navigator = null
        return true
    }

    fun requestOpenScreen(route: AppRoute) {
        pushScreen(route)
    }

    fun requestOpenScreen(screen: AppScreenRoute) {
        requestOpenScreen(screen.toAppRoute())
    }

    private fun queuePendingRoute(route: AppRoute) {
        val replacedRoute = pendingRoute
        if (replacedRoute != null && replacedRoute !== route) {
            AppRoutePayloadStore.release(replacedRoute)
        }
        pendingRoute = route
    }
}
