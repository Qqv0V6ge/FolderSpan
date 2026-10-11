package com.folderspan.ui.components.avatar

import com.folderspan.pro.presentation.screen.profile.AvatarPictureOutcome

internal class AvatarPictureRequestCoordinator {
    data class Request(
        val id: Long,
        val maxOutputBytes: Long,
        val onOutcome: (AvatarPictureOutcome) -> Unit,
    )

    private var nextId = 0L
    private var pending: Request? = null

    fun start(
        maxOutputBytes: Long,
        onOutcome: (AvatarPictureOutcome) -> Unit,
    ): Request {
        require(maxOutputBytes > 0)
        cancelPending()
        return Request(
            id = ++nextId,
            maxOutputBytes = maxOutputBytes,
            onOutcome = onOutcome,
        ).also { pending = it }
    }

    fun current(): Request? = pending

    fun finish(id: Long, outcome: AvatarPictureOutcome) {
        val request = pending?.takeIf { it.id == id } ?: return
        pending = null
        request.onOutcome(outcome)
    }

    fun dispose() {
        cancelPending()
    }

    private fun cancelPending() {
        val request = pending ?: return
        pending = null
        request.onOutcome(AvatarPictureOutcome.Cancelled)
    }
}
