package com.folderspan.crash

import com.folderspan.ui.state.main.CrashInfo

expect fun installPlatformCrashHandler(onCrash: (CrashInfo) -> Unit)

expect fun exitApp()
