package com.folderspan.service

import strings.AppStrings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.folderspan.utils.LogKit

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                LogKit.i(AppStrings.ui_received_auto_start_broadcast_arg0.format(arg0 = intent.action.toString()))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    intent.action == Intent.ACTION_BOOT_COMPLETED
                ) {
                    LogKit.w(AppStrings.ui_android_14_prohibits_boot_completed_starting_datasync_foreground_service)
                    return
                }
                BackgroundService.start(context.applicationContext)
            }
        }
    }
}
