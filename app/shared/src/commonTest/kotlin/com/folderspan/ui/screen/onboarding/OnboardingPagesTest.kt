package com.folderspan.ui.screen.onboarding

import strings.AppStrings

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.folderspan.data.file.FileProtocol
import com.folderspan.notification.RequestNotificationFactory
import com.folderspan.service.data.ConnectType
import com.folderspan.test.ChineseLocalizationTest
import com.folderspan.ui.screen.device.DeviceScreen
import com.folderspan.ui.screen.file.share.FileShareScreen
import com.folderspan.ui.screen.file.share.shareToDeviceListItemConnectTypeLabel
import com.folderspan.ui.screen.file.share.shareToDeviceListItemSupportingText
import com.folderspan.ui.screen.network.NetworkAddEntryScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

class OnboardingPagesTest : ChineseLocalizationTest() {
    @Test
    fun guideContainsFourWidgetPages() {
        assertEquals(4, onboardingPages.size)
        assertEquals(
            listOf(AppStrings.ui_quick_sharing, AppStrings.ui_device_sharing, AppStrings.ui_manipulate_files_across_devices, AppStrings.ui_third_party_network_disk),
            onboardingPages.map { item -> item.title }
        )
        assertEquals(3, onboardingPages.first().actions.size)
        assertEquals(AppStrings.ui_file, onboardingPages.first().actions[0].target)
        assertEquals(AppStrings.ui_confirm_that_menu_button_appears_right_file_you_want, onboardingPages.first().actions[0].action)
        assertEquals(AppStrings.ui_file_menu, onboardingPages.first().actions[1].target)
        assertEquals(AppStrings.ui_click_share_enter_quick_sharing_settings, onboardingPages.first().actions[1].action)
        assertEquals(AppStrings.ui_share_entrance, onboardingPages.first().actions[2].target)
        assertEquals(AppStrings.ui_scan_qr_code_copy_access_link_open_sharing_page, onboardingPages.first().actions[2].action)
        assertEquals(false, onboardingPages.first().actions.any { action -> action.target.contains(AppStrings.ui_test_onboarding_pages_entry) })
        assertEquals(false, onboardingPages.first().actions.any { action -> action.action.contains(AppStrings.ui_test_onboarding_pages_entry) })
        assertEquals(false, onboardingPages.first().actions.any { action -> action.target == AppStrings.ui_test_onboarding_pages_share_results })
        assertEquals(false, onboardingPages.first().actions.any { action -> action.action.contains(AppStrings.ui_test_onboarding_pages_effective_period) })
    }

    @Test
    fun guidePagesHaveStableUniqueKeysAndGuidedActions() {
        assertEquals(onboardingPages.size, onboardingPages.map { item -> item.key }.toSet().size)
        assertEquals(true, shouldShowOnboardingStepSection())
        assertEquals(AppStrings.ui_operation_steps, onboardingStepSectionTitle())
        onboardingPages.forEach { page ->
            assertTrue(page.key.isNotBlank(), "key should not be blank")
            assertTrue(page.description.isNotBlank(), "description should not be blank")
            assertTrue(page.actions.size >= 3, "${page.title} should include at least three guided actions")
            assertTrue(
                page.actions.all { item ->
                    item.target.isNotBlank() &&
                        item.action.isNotBlank()
                },
                "${page.title} should not include blank guided actions"
            )
        }
    }

    @Test
    fun guidePagesRenderTargetScreenWidgets() {
        onboardingPages.forEach { page ->
            assertEquals(page.widgetType, page.previewType)
            assertEquals(page.targetScreen()::class, page.previewScreen()::class)
        }
    }

    @Test
    fun guidePagesHaveDedicatedWidgetTypes() {
        assertEquals(
            listOf(
                OnboardingWidgetType.LinkShare,
                OnboardingWidgetType.DeviceShare,
                OnboardingWidgetType.RemoteFiles,
                OnboardingWidgetType.CloudDrive,
            ),
            onboardingPages.map { page -> page.widgetType }
        )
    }

    @Test
    fun guideWidgetsOpenMatchingScreens() {
        assertEquals(FileShareScreen, onboardingPages[0].targetScreen())
        assertIs<DeviceScreen>(onboardingPages[1].targetScreen())
        assertIs<DeviceScreen>(onboardingPages[2].targetScreen())
        assertIs<NetworkAddEntryScreen>(onboardingPages[3].targetScreen())
    }

    @Test
    fun contentLayoutUsesRowWhenWidthExceedsHeight() {
        assertEquals(
            OnboardingContentLayout.Row,
            resolveOnboardingContentLayout(width = 900.dp, height = 600.dp)
        )
    }

    @Test
    fun contentLayoutUsesColumnWhenHeightExceedsWidth() {
        assertEquals(
            OnboardingContentLayout.Column,
            resolveOnboardingContentLayout(width = 600.dp, height = 900.dp)
        )
    }

    @Test
    fun actionGuideHeaderIsHiddenInWideRowLayout() {
        assertEquals(
            false,
            shouldShowOnboardingActionHeader(OnboardingContentLayout.Row)
        )
        assertEquals(
            true,
            shouldShowOnboardingActionHeader(OnboardingContentLayout.Column)
        )
        assertEquals(true, shouldCenterOnboardingTargetPreviewContent())
        assertEquals(true, shouldAnimateOnboardingTargetPreviewContent())
        assertEquals(180, onboardingTargetPreviewEnterAnimationMillis())
        assertEquals(120, onboardingTargetPreviewExitAnimationMillis())
        assertEquals(false, shouldShowOnboardingTargetPreviewCardBackground())
        assertEquals(false, shouldShowOnboardingTargetPreviewTitle())
        assertEquals(false, shouldShowOnboardingTargetPreviewStepInfo())
    }

    @Test
    fun targetPreviewOnlyCentersContentInWideRowLayout() {
        assertEquals(true, shouldCenterOnboardingTargetPreviewContent(OnboardingContentLayout.Row))
        assertEquals(false, shouldCenterOnboardingTargetPreviewContent(OnboardingContentLayout.Column))
        assertEquals(420.dp, onboardingTargetPreviewMinHeight(OnboardingContentLayout.Row))
        assertEquals(0.dp, onboardingTargetPreviewMinHeight(OnboardingContentLayout.Column))
    }

    @Test
    fun actionGuideDoesNotShowStandaloneOpenAction() {
        assertEquals(false, shouldShowOnboardingOpenAction())
    }

    @Test
    fun actionGuideDoesNotShowCompletionSummary() {
        assertEquals(false, shouldShowOnboardingCompletionSummary())
    }

    @Test
    fun actionStepsCanJumpAndNormalizeSelection() {
        assertEquals(true, shouldAllowOnboardingStepJump())
        assertEquals(0, initialOnboardingStepIndex())
        assertEquals(0, resolveOnboardingStepSelection(requestedIndex = -1, stepCount = 3))
        assertEquals(1, resolveOnboardingStepSelection(requestedIndex = 1, stepCount = 3))
        assertEquals(2, resolveOnboardingStepSelection(requestedIndex = 8, stepCount = 3))
        assertEquals(0, resolveOnboardingStepSelection(requestedIndex = 8, stepCount = 0))
        assertEquals(true, isOnboardingStepClickable(stepIndex = 2, selectedStepIndex = 0, stepCount = 3))
    }

    @Test
    fun selectedActionUsesPairedSecondaryContainerColors() {
        val colorScheme = lightColorScheme(
            secondaryContainer = Color(0xFF102030),
            onSecondaryContainer = Color(0xFFF0E0D0),
            onSurface = Color(0xFF010203),
            onSurfaceVariant = Color(0xFF040506),
        )

        val colors = resolveOnboardingActionColors(
            colorScheme = colorScheme,
            selected = true,
            enabled = true,
        )

        assertEquals(colorScheme.secondaryContainer, colors.container)
        assertEquals(colorScheme.onSecondaryContainer, colors.title)
        assertEquals(colorScheme.onSecondaryContainer, colors.body)
    }

    @Test
    fun linkSharePreviewNodesUnlockBySelectedStep() {
        assertEquals(true, shouldShowLinkSharePreviewCardFileItem(selectedStepIndex = 0))
        assertEquals(false, shouldShowLinkSharePreviewInlineFileMenu(selectedStepIndex = 0))
        assertEquals(true, shouldShowLinkSharePreviewFileItemMenuButton(selectedStepIndex = 0))
        assertEquals(false, shouldExpandLinkSharePreviewFileItemMenu(selectedStepIndex = 0))
        assertEquals(false, shouldShowLinkSharePreviewCard(selectedStepIndex = 0))

        assertEquals(false, shouldShowLinkSharePreviewCardFileItem(selectedStepIndex = 1))
        assertEquals(true, shouldShowLinkSharePreviewInlineFileMenu(selectedStepIndex = 1))
        assertEquals(false, shouldShowLinkSharePreviewFileItemMenuButton(selectedStepIndex = 1))
        assertEquals(false, shouldExpandLinkSharePreviewFileItemMenu(selectedStepIndex = 1))
        assertEquals(false, resolveLinkSharePreviewFileItemMenuExpanded(requestedExpanded = false))
        assertEquals(false, shouldShowLinkSharePreviewCard(selectedStepIndex = 1))

        assertEquals(false, shouldShowLinkSharePreviewCardFileItem(selectedStepIndex = 2))
        assertEquals(false, shouldShowLinkSharePreviewInlineFileMenu(selectedStepIndex = 2))
        assertEquals(false, shouldShowLinkSharePreviewFileItemMenuButton(selectedStepIndex = 2))
        assertEquals(false, shouldExpandLinkSharePreviewFileItemMenu(selectedStepIndex = 2))
        assertEquals(true, shouldShowLinkSharePreviewCard(selectedStepIndex = 2))
    }

    @Test
    fun linkSharePreviewUsesCardWithoutLinkDeviceList() {
        assertEquals(true, shouldShowLinkSharePreviewCard(selectedStepIndex = 2))
        assertEquals(true, shouldUseRealLinkShareCardWidget())
        assertEquals(false, shouldUseExtractedLinkShareCardContent())
        assertEquals(true, shouldShowLinkSharePreviewCardFileItem(selectedStepIndex = 0))
        assertEquals(false, shouldShowLinkSharePreviewCardFileItem(selectedStepIndex = 1))
        assertEquals(true, shouldShowLinkSharePreviewInlineFileMenu(selectedStepIndex = 1))
        assertEquals(true, shouldShowLinkSharePreviewFileItemOutsideCard())
        assertEquals(false, shouldShowLinkSharePreviewFileItemInsideCard())
        assertEquals(false, shouldSelectLinkSharePreviewFileItem(selectedStepIndex = 0))
        assertEquals(true, shouldUseLinkSharePreviewFileItemMenu())
        assertEquals(true, shouldShowLinkSharePreviewFileItemMenuButton(selectedStepIndex = 0))
        assertEquals(false, shouldShowLinkSharePreviewFileItemMenuButton(selectedStepIndex = 1))
        assertEquals(true, shouldUseRealLinkSharePreviewFileItemDropdownMenu())
        assertEquals(false, shouldUseNavigatorBackedLinkSharePreviewFileItemMenu())
        assertEquals(true, linkSharePreviewFileItemMenuPermission().share)
        assertEquals(false, linkSharePreviewFileItemMenuPermission().info)
        assertEquals("report.pdf", linkSharePreviewCardFileItem().name)
        assertEquals(true, linkSharePreviewCardServerRunningOverride())
        assertEquals(false, shouldShowLinkSharePreviewStatusTabs())
        assertEquals(false, shouldUseRealLinkShareDeviceWidget())
        assertEquals(false, shouldUseRealShareToDeviceListWidget())
        assertEquals(false, shouldShowLinkSharePreviewDeviceList())
    }

    @Test
    fun linkSharePreviewFileUsesCurrentTimestamp() {
        val before = Clock.System.now().toEpochMilliseconds()

        val previewFile = linkSharePreviewCardFileItem()

        val after = Clock.System.now().toEpochMilliseconds()
        assertTrue(previewFile.createdDate in before..after)
        assertEquals(previewFile.createdDate, previewFile.updatedDate)
    }

    @Test
    fun deviceSharePreviewFirstTwoStepsMatchLinkSharePreview() {
        listOf(0, 1).forEach { selectedStepIndex ->
            assertEquals(
                shouldShowLinkSharePreviewCardFileItem(selectedStepIndex),
                shouldShowDeviceSharePreviewCardFileItem(selectedStepIndex)
            )
            assertEquals(
                shouldShowLinkSharePreviewInlineFileMenu(selectedStepIndex),
                shouldShowDeviceSharePreviewInlineFileMenu(selectedStepIndex)
            )
            assertEquals(
                shouldShowLinkSharePreviewFileItemMenuButton(selectedStepIndex),
                shouldShowDeviceSharePreviewFileItemMenuButton(selectedStepIndex)
            )
            assertEquals(
                shouldExpandLinkSharePreviewFileItemMenu(selectedStepIndex),
                shouldExpandDeviceSharePreviewFileItemMenu(selectedStepIndex)
            )
        }
    }

    @Test
    fun deviceShareGuideUsesShareToDeviceFlow() {
        val deviceSharePage = onboardingPages.first { page -> page.widgetType == OnboardingWidgetType.DeviceShare }

        assertEquals(AppStrings.ui_device_sharing, deviceSharePage.title)
        assertEquals(AppStrings.ui_select_device_send, deviceSharePage.label)
        assertEquals(AppStrings.ui_select_device_same_network_send_file, deviceSharePage.description)
        assertEquals(false, deviceSharePage.description.contains(AppStrings.ui_test_onboarding_pages_cloud))
        assertEquals(4, deviceSharePage.actions.size)
        assertEquals(AppStrings.ui_file, deviceSharePage.actions[0].target)
        assertEquals(AppStrings.ui_confirm_that_menu_button_appears_right_file_you_want, deviceSharePage.actions[0].action)
        assertEquals(AppStrings.ui_file_menu, deviceSharePage.actions[1].target)
        assertEquals(AppStrings.ui_click_share_enter_sharing_page, deviceSharePage.actions[1].action)
        assertEquals(AppStrings.ui_share_other_devices, deviceSharePage.actions[2].target)
        assertEquals(AppStrings.ui_select_device_same_network_send_files, deviceSharePage.actions[2].action)
        assertEquals(AppStrings.ui_receiver, deviceSharePage.actions[3].target)
        assertEquals(AppStrings.ui_other_party_agrees_device_sharing_request, deviceSharePage.actions[3].action)
    }

    @Test
    fun deviceSharePreviewThirdStepShowsShareToDeviceItemAsNewDisconnected() {
        val selectedStepIndex = 2
        val previewDevice = deviceSharePreviewSocketDevice()

        assertEquals(false, shouldShowDeviceSharePreviewCardFileItem(selectedStepIndex))
        assertEquals(false, shouldShowDeviceSharePreviewInlineFileMenu(selectedStepIndex))
        assertEquals(true, shouldShowDeviceShareToDeviceListItem(selectedStepIndex))
        assertEquals(false, shouldShowDeviceShareToDeviceHeader())
        assertEquals(false, shouldShowDeviceShareReceiverBanner(selectedStepIndex))
        assertEquals(ConnectType.New, previewDevice.connectType)
        assertEquals(AppStrings.ui_new_not_connected, shareToDeviceListItemConnectTypeLabel(previewDevice.connectType))
        assertEquals(AppStrings.ui_not_connected, shareToDeviceListItemSupportingText(sendStatus = null, sendMessage = null))
    }

    @Test
    fun deviceSharePreviewFourthStepShowsReceiverBanner() {
        val selectedStepIndex = 3
        val notification = RequestNotificationFactory.buildDeviceShareNotification(
            deviceId = deviceSharePreviewSocketDevice().id,
            deviceName = deviceSharePreviewSocketDevice().name
        ).notification

        assertEquals(false, shouldShowDeviceShareToDeviceListItem(selectedStepIndex))
        assertEquals(true, shouldShowDeviceShareReceiverBanner(selectedStepIndex))
        assertEquals(notification.title, deviceShareReceiverBannerTitle())
        assertEquals(notification.message, deviceShareReceiverBannerMessage())
        assertEquals(AppStrings.ui_agree, deviceShareReceiverBannerActionLabel())
        assertEquals(AppStrings.ui_reject, deviceShareReceiverBannerDismissLabel())
    }

    @Test
    fun remoteFilesGuideUsesCopyFolderToLocalFlow() {
        val remoteFilesPage = onboardingPages.first { page -> page.widgetType == OnboardingWidgetType.RemoteFiles }

        assertEquals(AppStrings.ui_manipulate_files_across_devices, remoteFilesPage.title)
        assertEquals(4, remoteFilesPage.actions.size)
        assertEquals(AppStrings.ui_equipment, remoteFilesPage.actions[0].target)
        assertEquals(AppStrings.ui_select_connected_device_enter_remote_file, remoteFilesPage.actions[0].action)
        assertEquals(AppStrings.ui_remote_folder, remoteFilesPage.actions[1].target)
        assertEquals(AppStrings.ui_select_folder_copy, remoteFilesPage.actions[1].action)
        assertEquals(AppStrings.ui_folder_menu, remoteFilesPage.actions[2].target)
        assertEquals(AppStrings.ui_display_complete_device_menu_click_copy, remoteFilesPage.actions[2].action)
        assertEquals(AppStrings.ui_local_directory, remoteFilesPage.actions[3].target)
        assertEquals(AppStrings.ui_switch_local_click_paste, remoteFilesPage.actions[3].action)
    }

    @Test
    fun cloudDriveGuideUsesNetworkSelectorThenRemoteFilesActions() {
        val remoteFilesPage = onboardingPages.first { page -> page.widgetType == OnboardingWidgetType.RemoteFiles }
        val cloudDrivePage = onboardingPages.first { page -> page.widgetType == OnboardingWidgetType.CloudDrive }

        assertEquals(AppStrings.ui_third_party_network_disk, cloudDrivePage.title)
        assertEquals(4, cloudDrivePage.actions.size)
        assertEquals(AppStrings.ui_network_location, cloudDrivePage.actions[0].target)
        assertEquals(AppStrings.ui_select_network_location_enter_network_disk_file, cloudDrivePage.actions[0].action)
        assertEquals(remoteFilesPage.actions[1], cloudDrivePage.actions[1])
        assertEquals(AppStrings.ui_folder_menu, cloudDrivePage.actions[2].target)
        assertEquals(AppStrings.ui_display_full_network_menu_click_copy, cloudDrivePage.actions[2].action)
        assertEquals(remoteFilesPage.actions[3], cloudDrivePage.actions[3])
    }

    @Test
    fun remoteFilesPreviewNodesMatchSelectedStep() {
        assertEquals(true, shouldShowRemoteFilesPreviewDeviceSelector(selectedStepIndex = 0))
        assertEquals(false, shouldShowRemoteFilesPreviewRemoteFolder(selectedStepIndex = 0))
        assertEquals(false, shouldShowRemoteFilesPreviewCopyMenu(selectedStepIndex = 0))
        assertEquals(false, shouldShowRemoteFilesPreviewLocalFolder(selectedStepIndex = 0))
        assertEquals(false, shouldShowRemoteFilesPreviewHomePasteBar(selectedStepIndex = 0))
        assertEquals(false, shouldShowRemoteFilesPreviewTransferProgress(selectedStepIndex = 0))

        assertEquals(false, shouldShowRemoteFilesPreviewDeviceSelector(selectedStepIndex = 1))
        assertEquals(true, shouldShowRemoteFilesPreviewRemoteFolder(selectedStepIndex = 1))
        assertEquals(false, shouldShowRemoteFilesPreviewCopyMenu(selectedStepIndex = 1))
        assertEquals(false, shouldShowRemoteFilesPreviewLocalFolder(selectedStepIndex = 1))
        assertEquals(false, shouldShowRemoteFilesPreviewHomePasteBar(selectedStepIndex = 1))
        assertEquals(false, shouldShowRemoteFilesPreviewTransferProgress(selectedStepIndex = 1))
        assertEquals(true, shouldShowRemoteFilesPreviewRemoteFolderMenuButton(selectedStepIndex = 1))

        assertEquals(false, shouldShowRemoteFilesPreviewDeviceSelector(selectedStepIndex = 2))
        assertEquals(false, shouldShowRemoteFilesPreviewRemoteFolder(selectedStepIndex = 2))
        assertEquals(true, shouldShowRemoteFilesPreviewCopyMenu(selectedStepIndex = 2))
        assertEquals(false, shouldShowRemoteFilesPreviewLocalFolder(selectedStepIndex = 2))
        assertEquals(false, shouldShowRemoteFilesPreviewHomePasteBar(selectedStepIndex = 2))
        assertEquals(false, shouldShowRemoteFilesPreviewTransferProgress(selectedStepIndex = 2))
        assertEquals(false, shouldShowRemoteFilesPreviewCopyMenuFolderCard())

        assertEquals(false, shouldShowRemoteFilesPreviewDeviceSelector(selectedStepIndex = 3))
        assertEquals(false, shouldShowRemoteFilesPreviewRemoteFolder(selectedStepIndex = 3))
        assertEquals(false, shouldShowRemoteFilesPreviewCopyMenu(selectedStepIndex = 3))
        assertEquals(true, shouldShowRemoteFilesPreviewLocalFolder(selectedStepIndex = 3))
        assertEquals(true, shouldShowRemoteFilesPreviewHomePasteBar(selectedStepIndex = 3))
        assertEquals(false, shouldShowRemoteFilesPreviewLocalPath(selectedStepIndex = 3))
        assertEquals(false, shouldShowRemoteFilesPreviewLocalPasteFolderCard())
        assertEquals(false, shouldShowRemoteFilesPreviewTransferProgress(selectedStepIndex = 3))
    }

    @Test
    fun remoteFilesPreviewUsesRealFileAndHomeWidgets() {
        assertEquals("Camera", remoteFilesPreviewRemoteFolder().name)
        assertEquals("/DCIM/Camera", remoteFilesPreviewRemoteFolder().path)
        assertEquals(true, remoteFilesPreviewRemoteFolder().isDirectory)
        assertEquals(FileProtocol.Device, remoteFilesPreviewRemoteFolder().protocol)
        assertEquals("Pixel 8", remoteFilesPreviewSocketDevice().name)
        assertEquals(ConnectType.Connect, remoteFilesPreviewSocketDevice().connectType)

        assertEquals("Downloads", remoteFilesPreviewLocalFolder().name)
        assertEquals("/Downloads", remoteFilesPreviewLocalFolder().path)
        assertEquals(true, remoteFilesPreviewLocalFolder().isDirectory)
        assertEquals(FileProtocol.Local, remoteFilesPreviewLocalFolder().protocol)

        assertEquals(true, shouldUseRealRemoteFilesPreviewDeviceDrawerListItem())
        assertEquals(false, shouldUseRemoteFilesPreviewDeviceAccessBlock())
        assertEquals(true, shouldShowRemoteFilesPreviewRemoteFolderMenuButton(selectedStepIndex = 1))
        assertEquals(true, shouldUseRealRemoteFilesPreviewFileCard())
        assertEquals(true, shouldUseRealRemoteFilesPreviewFileCardMenu())
        assertEquals(false, shouldUseRemoteFilesPreviewCopyOnlyMenu())
        assertEquals(false, shouldShowRemoteFilesPreviewCopyMenuFolderCard())
        assertEquals(false, remoteFilesPreviewDeviceMenuPermission().paste)
        assertEquals(true, remoteFilesPreviewDeviceMenuPermission().copy)
        assertEquals(true, remoteFilesPreviewDeviceMenuPermission().move)
        assertEquals(true, remoteFilesPreviewDeviceMenuPermission().delete)
        assertEquals(true, remoteFilesPreviewDeviceMenuPermission().rename)
        assertEquals(true, remoteFilesPreviewDeviceMenuPermission().setting)
        assertEquals(true, remoteFilesPreviewDeviceMenuPermission().favorite)
        assertEquals(true, remoteFilesPreviewDeviceMenuPermission().share)
        assertEquals(true, remoteFilesPreviewDeviceMenuPermission().info)
        assertEquals(true, shouldUseActualRemoteFilesPreviewHomeBottomBar())
        assertEquals(false, shouldUseRealRemoteFilesPreviewHomeBottomBarContent())
        assertEquals(true, shouldSeedRemoteFilesPreviewHomeBottomBarState())
        assertEquals(listOf(remoteFilesPreviewRemoteFolder()), remoteFilesPreviewHomeBottomBarPreviewState().selectedFiles)
        assertEquals(true, remoteFilesPreviewHomeBottomBarPreviewState().isPasteCopyFile)
        assertEquals(true, remoteFilesPreviewHomeBottomBarPreviewState().canPasteIntoCurrent)
        assertEquals(true, remoteFilesPreviewHomeBottomBarPreviewState().hasMenuPermission)
        assertEquals(true, remoteFilesPreviewHomeBottomBarPreviewState().canCreateInCurrentDesk)
        assertEquals(false, shouldUseRealRemoteFilesPreviewPathSwitch())
        assertEquals(true, shouldUseRemoteFilesPreviewInlineFileMenuItems())
        assertEquals(false, shouldUseRemoteFilesPreviewBlockingPopupMenu())
    }

    @Test
    fun cloudDrivePreviewUsesNetworkSelectorThenRemoteFilesFlow() {
        assertEquals(true, shouldShowCloudDrivePreviewNetworkSelector(selectedStepIndex = 0))
        assertEquals(false, shouldShowCloudDrivePreviewRemoteFolder(selectedStepIndex = 0))
        assertEquals(false, shouldShowCloudDrivePreviewCopyMenu(selectedStepIndex = 0))
        assertEquals(false, shouldShowCloudDrivePreviewLocalFolder(selectedStepIndex = 0))

        listOf(1, 2, 3).forEach { selectedStepIndex ->
            assertEquals(false, shouldShowCloudDrivePreviewNetworkSelector(selectedStepIndex))
            assertEquals(
                shouldShowRemoteFilesPreviewRemoteFolder(selectedStepIndex),
                shouldShowCloudDrivePreviewRemoteFolder(selectedStepIndex)
            )
            assertEquals(
                shouldShowRemoteFilesPreviewRemoteFolderMenuButton(selectedStepIndex),
                shouldShowCloudDrivePreviewRemoteFolderMenuButton(selectedStepIndex)
            )
            assertEquals(
                shouldShowRemoteFilesPreviewCopyMenu(selectedStepIndex),
                shouldShowCloudDrivePreviewCopyMenu(selectedStepIndex)
            )
            assertEquals(
                shouldShowRemoteFilesPreviewLocalFolder(selectedStepIndex),
                shouldShowCloudDrivePreviewLocalFolder(selectedStepIndex)
            )
        }
    }
}
