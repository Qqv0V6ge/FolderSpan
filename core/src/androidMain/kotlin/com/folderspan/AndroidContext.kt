package com.folderspan

import android.content.Context
import java.nio.file.Paths
import org.apache.sshd.common.util.io.PathUtils as SshdPathUtils

object AndroidContextHolder {
    @Volatile
    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        applicationContext = appContext
        runCatching {
            SshdPathUtils.setUserHomeFolderResolver {
                Paths.get(appContext.filesDir.absolutePath)
            }
        }
    }

    fun get(): Context =
        applicationContext ?: error("Android context not initialized")
}

fun androidContext(): Context = AndroidContextHolder.get()
