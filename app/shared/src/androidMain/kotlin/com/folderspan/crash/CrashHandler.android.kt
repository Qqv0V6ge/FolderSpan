package com.folderspan.crash

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.folderspan.androidContext
import com.folderspan.ui.state.main.CrashInfo
import kotlin.system.exitProcess

actual fun installPlatformCrashHandler(onCrash: (CrashInfo) -> Unit) {
    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
        runCatching { onCrash(CrashInfo.fromThrowable(throwable)) }
        scheduleRestart()
        exitApp()
    }
}

actual fun exitApp() {
    Process.killProcess(Process.myPid())
    exitProcess(0)
}

@SuppressLint("NewApi")
private fun scheduleRestart() {
    val context = runCatching { androidContext() }.getOrNull() ?: return
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val triggerAt = System.currentTimeMillis() + 300

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.set(AlarmManager.RTC, triggerAt, pendingIntent)
            return
        }
        alarmManager.setExact(AlarmManager.RTC, triggerAt, pendingIntent)
    } catch (_: SecurityException) {
        alarmManager.set(AlarmManager.RTC, triggerAt, pendingIntent)
    }
}
