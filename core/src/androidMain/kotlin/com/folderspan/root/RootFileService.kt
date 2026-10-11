package com.folderspan.root

import android.content.Intent
import android.os.IBinder
import com.folderspan.shizuku.ShizukuFileService
import com.topjohnwu.superuser.ipc.RootService

class RootFileService : RootService() {
    override fun onBind(intent: Intent): IBinder = ShizukuFileService()
}
