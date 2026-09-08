package com.folderspan.ui.components.avatar

import com.folderspan.pro.presentation.screen.profile.AvatarPictureOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AvatarPictureRequestCoordinatorTest {
    @Test
    fun supersedingAndDisposingCancelEachPendingRequestExactlyOnce() {
        val coordinator = AvatarPictureRequestCoordinator()
        val first = mutableListOf<AvatarPictureOutcome>()
        val second = mutableListOf<AvatarPictureOutcome>()

        coordinator.start(1_000, first::add)
        coordinator.start(2_000, second::add)
        assertEquals(1, first.size)
        assertIs<AvatarPictureOutcome.Cancelled>(first.single())

        coordinator.dispose()
        coordinator.dispose()
        assertEquals(1, second.size)
        assertIs<AvatarPictureOutcome.Cancelled>(second.single())
    }

    @Test
    fun staleCompletionCannotCompleteReplacementRequest() {
        val coordinator = AvatarPictureRequestCoordinator()
        val first = mutableListOf<AvatarPictureOutcome>()
        val second = mutableListOf<AvatarPictureOutcome>()
        val staleId = coordinator.start(1_000, first::add).id
        val currentId = coordinator.start(2_000, second::add).id

        coordinator.finish(staleId, AvatarPictureOutcome.Failed("stale"))
        assertEquals(0, second.size)

        coordinator.finish(currentId, AvatarPictureOutcome.Cancelled)
        assertEquals(1, second.size)
    }
}
