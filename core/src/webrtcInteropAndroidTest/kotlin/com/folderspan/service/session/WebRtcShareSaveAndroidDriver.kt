package com.folderspan.service.session

import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.folderspan.AndroidContextHolder
import com.folderspan.db.FolderSpanDatabase

internal fun createShareSaveTestDriver(): AndroidSqliteDriver {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    AndroidContextHolder.initialize(context)
    return AndroidSqliteDriver(FolderSpanDatabase.Schema, context, null)
}
