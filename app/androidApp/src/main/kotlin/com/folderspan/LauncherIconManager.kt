package com.folderspan

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.aakira.napier.Napier
import strings.AppStrings

/**
 * 在支持系统动态取色的图标与应用固定配色图标之间切换。
 *
 * Launcher 图标由系统读取 Manifest 组件资源，无法直接读取应用内设置，
 * 因此使用两个 activity-alias 承载不同的图标资源。
 */
internal class LauncherIconManager(context: Context) {
    private val packageManager = context.packageManager
    private val dynamicAlias = ComponentName(context, DYNAMIC_ALIAS_CLASS_NAME)
    private val fixedAlias = ComponentName(context, FIXED_ALIAS_CLASS_NAME)

    fun setDynamicColorEnabled(enabled: Boolean) {
        val enabledAlias = if (enabled) dynamicAlias else fixedAlias
        val disabledAlias = if (enabled) fixedAlias else dynamicAlias

        runCatching {
            setComponentState(enabledAlias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            setComponentState(disabledAlias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        }.onFailure { error ->
            Napier.e(AppStrings.ui_switch_launcher_icon_failed, error)
        }
    }

    private fun setComponentState(componentName: ComponentName, state: Int) {
        if (packageManager.getComponentEnabledSetting(componentName) == state) {
            return
        }
        packageManager.setComponentEnabledSetting(
            componentName,
            state,
            PackageManager.DONT_KILL_APP
        )
    }

    private companion object {
        const val DYNAMIC_ALIAS_CLASS_NAME = "com.folderspan.DynamicLauncherAlias"
        const val FIXED_ALIAS_CLASS_NAME = "com.folderspan.FixedLauncherAlias"
    }
}
