package com.folderspan

import com.folderspan.di.initKoin
import com.folderspan.ui.screen.file.share.FileShareScreen
import com.folderspan.ui.state.main.MainState
import org.koin.mp.KoinPlatformTools

private const val QUICK_ACTION_OPEN_FILE_SHARE = "com.folderspan.openFileShare"

fun handleQuickAction(type: String?): Boolean {
    if (type != QUICK_ACTION_OPEN_FILE_SHARE) {
        return false
    }
    initKoin()
    val koin = KoinPlatformTools.defaultContext().get()
    val mainState = koin.get<MainState>()
    mainState.requestOpenScreen(FileShareScreen)
    return true
}
