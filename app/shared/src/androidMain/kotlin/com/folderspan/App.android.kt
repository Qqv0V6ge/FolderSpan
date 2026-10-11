package com.folderspan

import android.content.Intent
import androidx.core.net.toUri

actual fun openUrl(url: String?) {
    val uri = url?.toUri() ?: return
    val intent = Intent().apply {
        action = Intent.ACTION_VIEW
        data = uri
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    androidContext().startActivity(intent)
}
