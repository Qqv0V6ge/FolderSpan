package com.folderspan.pro.data.mapper

import com.folderspan.pro.core.common.apiMessage
import kotlinx.serialization.json.JsonElement

fun JsonElement.displaySuccessMessageOrNull(): String? =
    apiMessage()
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("OK", ignoreCase = true) }
