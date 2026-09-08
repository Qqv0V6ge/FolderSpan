package com.folderspan.pro.presentation.screen.feedback

import com.folderspan.utils.ErrorLogFeedbackPayload
import kotlin.concurrent.Volatile

object ErrorLogFeedbackLaunch {
    @Volatile
    var pending: ErrorLogFeedbackPayload? = null

    fun offer(payload: ErrorLogFeedbackPayload) {
        pending = payload
    }

    fun consume(): ErrorLogFeedbackPayload? {
        val current = pending
        pending = null
        return current
    }
}
