package com.folderspan.ui.screen.file.share

import strings.AppStrings

import com.folderspan.service.data.ConnectType
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.state.file.FileShareStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileShareScreenStatusTextTest : ChineseLocalizationTest() {
    @Test
    fun unansweredShareUsesNeutralCopyInsteadOfFailure() {
        assertEquals(
            AppStrings.ui_wait_other_party_receive,
            shareToDeviceListItemSupportingText(FileShareStatus.WAITING, null)
        )
        assertEquals(
            AppStrings.ui_other_party_did_not_receive,
            shareToDeviceListItemSupportingText(FileShareStatus.REJECTED, null)
        )
        assertEquals(
            AppStrings.ui_sending_not_completed_please_try_again,
            shareToDeviceListItemSupportingText(FileShareStatus.ERROR, null)
        )
    }

    @Test
    fun activeShareStatusTakesPrecedenceOverDeviceConnectionFailure() {
        assertEquals(
            AppStrings.ui_waiting_receive,
            shareToDeviceListItemStatusLabel(
                sendStatus = FileShareStatus.WAITING,
                sendMessage = null,
                connectType = ConnectType.Fail,
            )
        )
        assertEquals(
            AppStrings.ui_not_received,
            shareToDeviceListItemStatusLabel(
                sendStatus = FileShareStatus.REJECTED,
                sendMessage = AppStrings.ui_other_party_did_not_receive,
                connectType = ConnectType.Fail,
            )
        )
    }

    @Test
    fun explicitRejectionRemainsDistinctFromNoResponse() {
        assertEquals(
            AppStrings.ui_rejected,
            shareToDeviceListItemStatusLabel(
                sendStatus = FileShareStatus.REJECTED,
                sendMessage = AppStrings.ui_other_party_refused_receive,
                connectType = ConnectType.Fail,
            )
        )
        assertEquals(
            AppStrings.ui_other_party_refused_receive,
            shareToDeviceListItemSupportingText(
                sendStatus = FileShareStatus.REJECTED,
                sendMessage = AppStrings.ui_other_party_refused_receive,
            )
        )
    }

    @Test
    fun everyStartedShareCanBeCancelledIncludingError() {
        assertFalse(shareToDeviceListItemCanCancel(null))
        FileShareStatus.entries.forEach { status ->
            assertTrue(shareToDeviceListItemCanCancel(status), "status=$status")
        }
    }
}
