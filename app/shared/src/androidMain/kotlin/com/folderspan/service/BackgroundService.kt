package com.folderspan.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.folderspan.privileged.PrivilegedFileAccess
import com.folderspan.shared.R
import com.folderspan.service.http.server.startBackgroundShareServices
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicBoolean
import strings.AppStrings

@SuppressLint("NewApi")
class BackgroundService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val notificationId = 1
    private val channelId = "background_service_channel"
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val isStopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (!startForegroundServiceCompat()) {
            return
        }
        acquireLocks()

        serviceScope.launch {
            val fileShareState = GlobalContext.get().get<FileShareState>()
            try {
                startBackgroundShareServices(
                    fileShareState = fileShareState,
                    awaitEasyShareInitializationReady = {
                        PrivilegedFileAccess.awaitPreferredBackendReady()
                    },
                )
            } finally {
                stopForegroundService(AppStrings.ui_end_background_sharing_service)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        releaseLocks()
    }

    override fun onTimeout(startId: Int) {
        LogKit.w(AppStrings.ui_foreground_service_has_timed_out_ready_stop_startid_arg0.format(arg0 = (startId).toString()))
        stopForegroundService(AppStrings.ui_front_desk_service_timeout)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        LogKit.w(AppStrings.ui_foreground_service_timeout_stop_start_id_arg0_fgs_type_arg1.format(arg0 = (startId).toString(), arg1 = (fgsType).toString()))
        stopForegroundService(AppStrings.ui_front_desk_service_timeout)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            AppStrings.android_background_service_channel,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = AppStrings.android_background_service_channel_description
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForegroundServiceCompat(): Boolean {
        val notification = createNotification()

        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    // Android 14+ (API 34+) - 需要指定前台服务类型
                    startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    // Android 10+ (API 29+) - 支持前台服务类型
                    startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }

                else -> {
                    // Android 9 及以下 - 标准前台服务
                    startForeground(notificationId, notification)
                }
            }
            true
        } catch (e: SecurityException) {
            LogKit.e(AppStrings.ui_frontend_service_failed_start_arg0.format(arg0 = (e.message).toString()), e)
            stopSelf()
            false
        } catch (e: RuntimeException) {
            if (e.javaClass.name == FGS_START_NOT_ALLOWED_EXCEPTION) {
                LogKit.w(AppStrings.ui_foreground_service_startup_restricted_service_stopped_arg0.format(arg0 = (e.message).toString()), e)
                stopSelf()
                return false
            }
            throw e
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = (packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntentFlags = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            }

            else -> {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, pendingIntentFlags
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(AppStrings.app_name)
            .setContentText(AppStrings.android_background_service_running)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            cpuWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "FolderSpan:FileShareCpu"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }

            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL
            }
            wifiLock = wifiManager.createWifiLock(
                wifiMode,
                "FolderSpan:FileShareWifi"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            LogKit.e(AppStrings.ui_failed_acquire_wake_lock_arg0.format(arg0 = (e.message).toString()), e)
        }
    }

    private fun releaseLocks() {
        try {
            cpuWakeLock?.let { item ->  if (item.isHeld) item.release() }
        } catch (e: Exception) {
            LogKit.e(AppStrings.ui_failed_release_cpu_wake_lock_arg0.format(arg0 = (e.message).toString()), e)
        } finally {
            cpuWakeLock = null
        }

        try {
            wifiLock?.let { item ->  if (item.isHeld) item.release() }
        } catch (e: Exception) {
            LogKit.e(AppStrings.ui_failed_release_wi_fi_wake_lock_arg0.format(arg0 = (e.message).toString()), e)
        } finally {
            wifiLock = null
        }
    }

    private fun stopForegroundService(reason: String) {
        if (!isStopping.compareAndSet(false, true)) {
            return
        }
        LogKit.i(AppStrings.ui_stop_foreground_service_arg0.format(arg0 = reason))
        serviceJob.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { e -> LogKit.w(AppStrings.ui_failed_stop_foreground_notification_arg0.format(arg0 = (e.message).toString()), e) }
        stopSelf()
    }

    companion object {
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val FGS_START_NOT_ALLOWED_EXCEPTION =
            "android.app.ForegroundServiceStartNotAllowedException"

        fun start(context: Context) {
            val intent = Intent(context, BackgroundService::class.java)

            // Android 8.0+ (API 26+) - 必须使用 startForegroundService
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) {
                // 如果启动前台服务失败，尝试启动普通服务
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                    // 忽略启动失败
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BackgroundService::class.java)
            context.stopService(intent)
        }
    }
}
