package com.folderspan.utils

import androidx.compose.runtime.Composable
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.test.createInMemorySettings
import com.folderspan.ui.navigation.*
import kotlin.test.*

class SoftwareConsentTest {
    @Test
    fun localAndSystemFileOperationsDoNotRequestConsent() {
        SettingsUtils.init(createInMemorySettings())
        SettingsUtils.dismissSoftwareConsentRequest()
        assertTrue(ensureFileSoftwareConsent(FileProtocol.Local, ""))
        assertTrue(ensureFileSoftwareConsent(FileProtocol.Share, SYSTEM_SHARE_DESK_ID))
        assertFalse(SettingsUtils.softwareConsentRequested.value)
        for (protocol in listOf(FileProtocol.Device, FileProtocol.Network, FileProtocol.Share)) {
            assertFalse(ensureFileSoftwareConsent(protocol, "remote"))
            assertTrue(SettingsUtils.softwareConsentRequested.value)
            SettingsUtils.dismissSoftwareConsentRequest()
        }
        SettingsUtils.setWelcomeAgreementAccepted(true)
        assertTrue(ensureFileSoftwareConsent(FileProtocol.Network, "remote"))
        assertFalse(SettingsUtils.softwareConsentRequested.value)
    }

    @Test
    fun openingARemoteHistoryEntryRequestsConsentBeforeResolvingItsDevice() {
        SettingsUtils.init(createInMemorySettings())
        SettingsUtils.dismissSoftwareConsentRequest()
        val file = com.folderspan.data.file.FileSimpleInfo.pathFileSimpleInfo("/remote/report.txt")
            .copy(protocol = FileProtocol.Network, protocolId = "saved-network", isDirectory = false)
        com.folderspan.ui.state.main.HomeState().openFile(file)
        assertTrue(SettingsUtils.softwareConsentRequested.value)
    }

    @Test
    fun allNavigatorsBlockProtectedDestinationsWithoutChangingTheStack() {
        for (navigator in listOf(MutableAppNavigator(AppRoute.Home), BackStackAppNavigator(mutableListOf(AppRoute.Home)))) {
            SettingsUtils.init(createInMemorySettings())
            SettingsUtils.dismissSoftwareConsentRequest()
            val protectedScreen = object : AppScreenRoute {
                override val requiresSoftwareConsent = true
                @Composable override fun Content() {}
            }
            val route = protectedScreen.toAppRoute()
            navigator.push(route)
            assertEquals(listOf(AppRoute.Home), navigator.routes)
            assertNull(AppRoutePayloadStore.resolve(route as AppRoute.Payload))
            assertTrue(SettingsUtils.softwareConsentRequested.value)
            SettingsUtils.dismissSoftwareConsentRequest()
            navigator.replaceRoot(protectedScreen.toAppRoute())
            assertEquals(listOf(AppRoute.Home), navigator.routes)
            SettingsUtils.setWelcomeAgreementAccepted(true)
            navigator.push(protectedScreen)
            assertEquals(2, navigator.routes.size)
            AppRoutePayloadStore.release(navigator.routes)
        }
    }
}
