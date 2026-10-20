package com.folderspan.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.folderspan.androidContext
import com.folderspan.root.RootManager
import com.folderspan.shizuku.ShizukuManager
import com.folderspan.shizuku.ShizukuPermissionState
import com.folderspan.root.toPermissionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PERMISSION_READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
private const val PERMISSION_WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
private const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

actual object PlatformPermissionProvider {
    private var requester: AndroidPermissionRequester? = null

    fun bind(activity: ComponentActivity) {
        requester = AndroidPermissionRequester(activity)
    }

    actual fun permissions(): List<PlatformPermission> = androidPlatformPermissions(Build.VERSION.SDK_INT)

    actual suspend fun status(permission: PlatformPermission): PermissionStatus {
        val context = androidContext()
        return when (permission.id) {
            PermissionIds.ReadExternalStorage -> storageStatus(context)
            PermissionIds.AllFilesAccess -> allFilesStatus()
            PermissionIds.Notifications -> notificationStatus(context)
            PermissionIds.LocalNetwork -> localNetworkStatus(context)
            PermissionIds.BatteryOptimization -> batteryOptimizationStatus(context)
            PermissionIds.Shizuku -> shizukuStatus()
            PermissionIds.Root -> rootStatus()
            else -> PermissionStatus.Unsupported
        }
    }

    actual fun request(permission: PlatformPermission, onResult: (PermissionStatus) -> Unit) {
        val context = androidContext()
        val currentStatus = when (permission.id) {
            PermissionIds.ReadExternalStorage -> storageStatus(context)
            PermissionIds.AllFilesAccess -> allFilesStatus()
            PermissionIds.Notifications -> notificationStatus(context)
            PermissionIds.LocalNetwork -> localNetworkStatus(context)
            PermissionIds.BatteryOptimization -> batteryOptimizationStatus(context)
            PermissionIds.Shizuku -> shizukuStatus()
            PermissionIds.Root -> rootStatus()
            else -> PermissionStatus.Unsupported
        }
        if (currentStatus == PermissionStatus.Granted || permission.action == PermissionAction.None) {
            onResult(currentStatus)
            return
        }

        if (permission.id == PermissionIds.Shizuku) {
            requestShizukuPermission(onResult)
            return
        }
        if (permission.id == PermissionIds.Root) {
            requestRootPermission(onResult)
            return
        }

        val requester = requester
        if (requester == null) {
            onResult(PermissionStatus.Unsupported)
            return
        }

        when (permission.id) {
            PermissionIds.ReadExternalStorage -> requester.requestStorage { onResult(storageStatus(context)) }
            PermissionIds.AllFilesAccess -> requester.requestAllFilesAccess { onResult(allFilesStatus()) }
            PermissionIds.Notifications -> requester.requestNotifications { onResult(notificationStatus(context)) }
            PermissionIds.LocalNetwork -> requester.requestLocalNetwork { onResult(localNetworkStatus(context)) }
            PermissionIds.BatteryOptimization -> requester.requestBatteryOptimization {
                onResult(batteryOptimizationStatus(context))
            }
            else -> onResult(PermissionStatus.Unsupported)
        }
    }

    actual fun openSettings(permission: PlatformPermission) {
        if (!permission.settingsInstructions.isNullOrBlank()) return
        val context = androidContext()
        val intent = when (permission.id) {
            PermissionIds.AllFilesAccess -> buildAllFilesAccessIntent(context)
                ?: appDetailsIntent(context)
            PermissionIds.Notifications -> notificationSettingsIntent(context)
            PermissionIds.LocalNetwork -> appDetailsIntent(context)
            PermissionIds.BatteryOptimization -> batteryOptimizationSettingsIntent(context)
            PermissionIds.ReadExternalStorage,
            PermissionIds.Shizuku,
            PermissionIds.Root -> appDetailsIntent(context)
            else -> appDetailsIntent(context)
        }
        if (intent.resolveActivity(context.packageManager) == null) return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

private fun requestShizukuPermission(onResult: (PermissionStatus) -> Unit) {
    CoroutineScope(Dispatchers.Main).launch {
        runCatching { ShizukuManager.requestPermission() }
        onResult(shizukuStatus())
    }
}

private fun requestRootPermission(onResult: (PermissionStatus) -> Unit) {
    CoroutineScope(Dispatchers.Main).launch {
        withContext(Dispatchers.IO) {
            runCatching { RootManager.requestPermission() }
        }
        onResult(rootStatus())
    }
}

private class AndroidPermissionRequester(private val activity: ComponentActivity) {
    private val denialHistory = activity.getSharedPreferences("folderspan.permission.denials", Context.MODE_PRIVATE)
    private var storageCallback: (() -> Unit)? = null
    private var notificationCallback: (() -> Unit)? = null
    private var localNetworkCallback: (() -> Unit)? = null
    private var allFilesCallback: (() -> Unit)? = null
    private var batteryOptimizationCallback: (() -> Unit)? = null
    private var settingsCallback: (() -> Unit)? = null

    private val storagePermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (shouldOpenAppSettings(permissions)) {
                openAppDetailsSettings()
            }
            storageCallback?.invoke()
            storageCallback = null
        }

    private val notificationPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            rememberRuntimePermissionResult(Manifest.permission.POST_NOTIFICATIONS, granted)
            notificationCallback?.invoke()
            notificationCallback = null
        }

    private val localNetworkPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            rememberRuntimePermissionResult(PERMISSION_ACCESS_LOCAL_NETWORK, granted)
            localNetworkCallback?.invoke()
            localNetworkCallback = null
        }

    private val manageAllFilesPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            allFilesCallback?.invoke()
            allFilesCallback = null
        }

    private val batteryOptimizationPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            batteryOptimizationCallback?.invoke()
            batteryOptimizationCallback = null
        }

    private val permissionSettingsLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            settingsCallback?.invoke()
            settingsCallback = null
        }

    fun requestStorage(onResult: () -> Unit) {
        storageCallback = onResult
        storagePermissionLauncher.launch(buildStoragePermissions())
    }

    fun requestNotifications(onResult: () -> Unit) {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val needsNotificationSettings = shouldOpenAndroidNotificationSettings(
            sdkInt = Build.VERSION.SDK_INT,
            runtimePermissionGranted = runtimeGranted,
            notificationsEnabled = NotificationManagerCompat.from(activity).areNotificationsEnabled(),
        )
        if (needsNotificationSettings ||
            shouldOpenRuntimePermissionSettings(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            openRuntimePermissionSettings(notificationSettingsIntent(activity), onResult)
            return
        }
        notificationCallback = onResult
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun requestLocalNetwork(onResult: () -> Unit) {
        if (Build.VERSION.SDK_INT < 37) {
            onResult()
            return
        }
        if (shouldOpenRuntimePermissionSettings(PERMISSION_ACCESS_LOCAL_NETWORK)) {
            openRuntimePermissionSettings(appDetailsIntent(activity), onResult)
            return
        }
        localNetworkCallback = onResult
        localNetworkPermissionLauncher.launch(PERMISSION_ACCESS_LOCAL_NETWORK)
    }

    fun requestAllFilesAccess(onResult: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            onResult()
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

        if (intentToLaunch == null) {
            onResult()
            return
        }
        allFilesCallback = onResult
        manageAllFilesPermissionLauncher.launch(intentToLaunch)
    }

    fun requestBatteryOptimization(onResult: () -> Unit) {
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(activity.packageName)) {
            onResult()
            return
        }
        val intentToLaunch = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .takeIf { item ->  item.resolveActivity(activity.packageManager) != null }
        if (intentToLaunch == null) {
            onResult()
            return
        }
        batteryOptimizationCallback = onResult
        batteryOptimizationPermissionLauncher.launch(intentToLaunch)
    }

    private fun rememberRuntimePermissionResult(permission: String, granted: Boolean) {
        when {
            granted -> denialHistory.edit().remove(permission).apply()
            activity.shouldShowRequestPermissionRationale(permission) ->
                denialHistory.edit().putBoolean(permission, true).apply()
        }
    }

    private fun shouldOpenRuntimePermissionSettings(permission: String): Boolean {
        val granted = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        val showRationale = activity.shouldShowRequestPermissionRationale(permission)
        // A dismissed notification prompt also returns false. Only a rationale confirms a prior denial.
        rememberRuntimePermissionResult(permission, granted)
        return shouldOpenAndroidRuntimePermissionSettings(
            granted = granted,
            previouslyDenied = denialHistory.getBoolean(permission, false),
            shouldShowRationale = showRationale,
        )
    }

    private fun openRuntimePermissionSettings(intent: Intent, onResult: () -> Unit) {
        val target = intent.takeIf { it.resolveActivity(activity.packageManager) != null }
            ?: appDetailsIntent(activity).takeIf { it.resolveActivity(activity.packageManager) != null }
        if (target == null) {
            onResult()
            return
        }
        settingsCallback = onResult
        permissionSettingsLauncher.launch(target)
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

private fun buildStoragePermissions(): Array<String> = buildList {
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
            add(PERMISSION_WRITE_EXTERNAL_STORAGE)
        }
    }
}.distinct().toTypedArray()

private fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }

private fun buildAllFilesAccessIntent(context: Context): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return null
    }
    val packageUri = Uri.fromParts("package", context.packageName, null)
    val manageAppIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    val manageAllIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
        data = packageUri
    }
    return when {
        manageAppIntent.resolveActivity(context.packageManager) != null -> manageAppIntent
        manageAllIntent.resolveActivity(context.packageManager) != null -> manageAllIntent
        else -> null
    }
}

private fun notificationSettingsIntent(context: Context): Intent {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return appDetailsIntent(context)
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    return intent.takeIf { it.resolveActivity(context.packageManager) != null } ?: appDetailsIntent(context)
}

private fun batteryOptimizationSettingsIntent(context: Context): Intent {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    return if (intent.resolveActivity(context.packageManager) != null) {
        intent
    } else {
        appDetailsIntent(context)
    }
}

private fun storageStatus(context: Context): PermissionStatus {
    val missing = buildStoragePermissions().any { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }
    return if (missing) PermissionStatus.Denied else PermissionStatus.Granted
}

private fun notificationStatus(context: Context): PermissionStatus {
    val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    return androidNotificationPermissionStatus(
        sdkInt = Build.VERSION.SDK_INT,
        runtimePermissionGranted = runtimeGranted,
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )
}

private fun localNetworkStatus(context: Context): PermissionStatus {
    if (Build.VERSION.SDK_INT < 37) return PermissionStatus.Granted
    val granted = ContextCompat.checkSelfPermission(
        context,
        PERMISSION_ACCESS_LOCAL_NETWORK,
    ) == PackageManager.PERMISSION_GRANTED
    return if (granted) PermissionStatus.Granted else PermissionStatus.Denied
}

private fun allFilesStatus(): PermissionStatus {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return PermissionStatus.Unsupported
    }
    return if (Environment.isExternalStorageManager()) {
        PermissionStatus.Granted
    } else {
        PermissionStatus.Denied
    }
}

private fun batteryOptimizationStatus(context: Context): PermissionStatus {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
        PermissionStatus.Granted
    } else {
        PermissionStatus.Denied
    }
}

private fun shizukuStatus(): PermissionStatus = when (ShizukuManager.permissionState.value) {
    ShizukuPermissionState.Granted -> PermissionStatus.Granted
    ShizukuPermissionState.Denied -> PermissionStatus.Denied
    ShizukuPermissionState.ServiceMissing -> PermissionStatus.Unsupported
    ShizukuPermissionState.Unsupported -> PermissionStatus.Unsupported
}

private fun rootStatus(): PermissionStatus = RootManager.permissionState.value.toPermissionStatus()
