package com.folderspan.utils

import kotlinx.datetime.TimeZone

actual fun currentSyncTimeZone(): TimeZone = TimeZone.currentSystemDefault()
