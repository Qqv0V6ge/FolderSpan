package com.folderspan.di

import com.russhwolf.settings.Settings
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatformTools

fun initKoin() {
    val context = KoinPlatformTools.defaultContext()
    if (context.getOrNull() == null) {
        val koinApp = startKoin {
            modules(appModule())
        }
        // 强制初始化 Settings 以确保 SettingsUtils 被初始化
        koinApp.koin.get<Settings>()
    }
}
