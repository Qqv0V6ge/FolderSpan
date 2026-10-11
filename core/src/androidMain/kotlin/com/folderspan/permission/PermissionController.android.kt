package com.folderspan.permission

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.folderspan.utils.LogKit
import strings.AppStrings

private const val PERMISSION_READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
private const val PERMISSION_WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"

/**
 * 统一管理通知/存储相关运行时权限与 MANAGE_EXTERNAL_STORAGE 入口。
 */
class PermissionController(
    private val activity: ComponentActivity,
    private val backgroundServiceStarter: () -> Unit
) {

    private val storagePermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.values.all { item ->  item }
            if (!granted) {
                Toast.makeText(
                    activity,
                    AppStrings.permission_storage_required,
                    Toast.LENGTH_LONG,
                ).show()
                if (shouldOpenAppSettings(permissions)) {
                    openAppDetailsSettings()
                }
            } else {
                ensureStoragePermission()
            }
        }

    private val manageAllFilesPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                Toast.makeText(
                    activity,
                    AppStrings.permission_all_files_still_required,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    /**
     * 请求分域媒体权限，有需要时继续拉起 MANAGE_EXTERNAL_STORAGE。
     */
    fun ensureStoragePermission() {
        val scopedRequested = requestScopedStoragePermissions()
        if (scopedRequested) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestManageAllFilesPermission()
        }
    }

    /**
     * 根据系统版本补齐 WRITE_EXTERNAL_STORAGE。
     * @return true 表示已经发起权限弹窗。
     */
    private fun requestScopedStoragePermissions(): Boolean {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(PERMISSION_READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    // Android 9 及以下仍需可写权限
                    add(PERMISSION_WRITE_EXTERNAL_STORAGE)
                }
            }
        }.distinct()

        val missing = permissions.filter { item ->
            ContextCompat.checkSelfPermission(activity, item) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            storagePermissionLauncher.launch(missing.toTypedArray())
            return true
        }
        return false
    }

    /**
     * 打开 MANAGE_EXTERNAL_STORAGE 配置入口，若设备不支持则记录警告。
     */
    private fun requestManageAllFilesPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            return
        }

        val packageUri = Uri.fromParts("package", activity.packageName, null)
        val manageAppIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        }
        val manageAllIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
            data = packageUri
        }

        val intentToLaunch = when {
            manageAppIntent.resolveActivity(activity.packageManager) != null -> manageAppIntent
            manageAllIntent.resolveActivity(activity.packageManager) != null -> manageAllIntent
            else -> null
        }

        if (intentToLaunch != null) {
            manageAllFilesPermissionLauncher.launch(intentToLaunch)
        } else {
            LogKit.w(AppStrings.ui_current_device_does_not_support_manage_external_storage_authorization)
        }
    }

    private fun shouldOpenAppSettings(permissions: Map<String, Boolean>): Boolean {
        val denied = permissions.filterValues { item ->  !item }
        return denied.isNotEmpty() && denied.keys.any { item ->  !activity.shouldShowRequestPermissionRationale(item) }
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        if (intent.resolveActivity(activity.packageManager) == null) return
        activity.startActivity(intent)
    }
}
