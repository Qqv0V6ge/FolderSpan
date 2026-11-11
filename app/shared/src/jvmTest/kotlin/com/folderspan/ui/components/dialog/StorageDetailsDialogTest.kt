package com.folderspan.ui.components.dialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.service.network.*
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StorageDetailsDialogTest {
    private val path = StoragePath("/file")
    private val caps = StorageAdvancedCapabilities(
        features = StorageAdvancedFeature.entries.associateWith { StorageFeatureAvailability.AVAILABLE },
        shareRoles = setOf("reader", "writer"), shareExpiry = true,
    )

    @Test
    fun onlyBackendsWithRealAdvancedOperationAcceptanceCanLeaveTheValidationGate() {
        for (protocol in listOf("GoogleDrive", "Dropbox", "Nextcloud", "Seafile")) {
            assertFalse(storageServiceValidationPending(protocol), protocol)
        }
        for (protocol in listOf("FTP", "SFTP", "SMB", "WebDav", "S3",
            "NFS", "HttpDirectory", "unknown", "", "nextcloud", "seafile")) {
            assertTrue(storageServiceValidationPending(protocol), protocol)
        }
    }

    @Test
    fun unvalidatedBackendCannotExecuteEvenWhenBackendReportsCapability() = runComposeUiTest {
        var invoked = false
        setContent {
            MaterialTheme {
                StorageDetailsDialog(
                    StorageDetailsDialogUiState("file", StorageDetailsMode.Versions, true,
                        capabilities = caps, versions = listOf(StorageVersion(path, "old"))),
                    onDismiss = {}, onRestore = { invoked = true },
                )
            }
        }
        onNodeWithText(AppStrings.storage_service_validation_pending).assertIsDisplayed()
        onNodeWithText(AppStrings.storage_restore_version).assertIsNotEnabled().performClick()
        assertFalse(invoked)
        onNodeWithText(AppStrings.storage_confirm_action).assertDoesNotExist()
    }

    @Test
    fun restoreNeedsExplicitConfirmationAndExplainsGoogleRetention() = runComposeUiTest {
        var restored: StorageVersion? = null
        val version = StorageVersion(path, "old", 25)
        setContent {
            MaterialTheme {
                StorageDetailsDialog(
                    StorageDetailsDialogUiState("file", StorageDetailsMode.Versions, false,
                        capabilities = caps, versions = listOf(version), googleDrive = true),
                    onDismiss = {}, onRestore = { restored = it },
                )
            }
        }
        onNodeWithText(AppStrings.storage_restore_version).performClick()
        assertEquals(null, restored)
        onNodeWithText(AppStrings.storage_restore_confirmation + "\n" + AppStrings.storage_google_restore_retention).assertIsDisplayed()
        onNode(hasText(AppStrings.storage_confirm_action) and hasClickAction()).performClick()
        assertEquals(version, restored)
    }

    @Test
    fun ownerOrInheritedGrantCannotBeRevoked() = runComposeUiTest {
        var invoked = false
        setContent {
            MaterialTheme {
                StorageDetailsDialog(
                    StorageDetailsDialogUiState("file", StorageDetailsMode.Sharing, false, capabilities = caps,
                        shares = listOf(StorageShare(path, "owner", StorageShareKind.INVITATION, "owner",
                            recipient = "owner@example.com", canRevoke = false))),
                    onDismiss = {}, onRevokeShare = { invoked = true },
                )
            }
        }
        onNodeWithText(AppStrings.storage_revoke_share).assertIsNotEnabled().performClick()
        assertFalse(invoked)
    }

    @Test
    fun versionListAndRestoreConfirmationRemainAccessibleAcrossWindowSizes() {
        for ((width, height) in listOf(360 to 720, 900 to 360, 1600 to 900)) {
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
                val versions = (1..8).map { StorageVersion(path, "historical-version-$it", it.toLong()) }
                var restored: StorageVersion? = null
                var loadedMore = 0
                setContent {
                    MaterialTheme {
                        StorageDetailsDialog(
                            StorageDetailsDialogUiState("long-file-name.txt", StorageDetailsMode.Versions, false,
                                capabilities = caps, versions = versions, nextCursor = "next-page"),
                            onDismiss = {}, onRestore = { restored = it }, onLoadMore = { loadedMore++ },
                        )
                    }
                }
                onNodeWithText("historical-version-8").performScrollTo().assertIsDisplayed()
                onNodeWithText(AppStrings.storage_load_more).performScrollTo().assertIsDisplayed().performClick()
                assertEquals(1, loadedMore)
                onAllNodesWithText(AppStrings.storage_restore_version).onLast()
                    .performScrollTo().assertIsDisplayed().performClick()
                onNodeWithText(AppStrings.storage_restore_confirmation).assertIsDisplayed()
                onAllNodesWithText(AppStrings.ui_cancel).onLast().assertIsDisplayed().performClick()
                assertEquals(null, restored)
                onNodeWithText(AppStrings.storage_restore_confirmation).assertDoesNotExist()
                onAllNodesWithText(AppStrings.storage_restore_version).onLast().performScrollTo().performClick()
                onNode(hasText(AppStrings.storage_confirm_action) and hasClickAction()).assertIsDisplayed().performClick()
                assertEquals(versions.last(), restored)
            }
        }
    }

    @Test
    fun sharingListInvitationAndRevokeConfirmationsRemainAccessibleAcrossWindowSizes() {
        for ((width, height) in listOf(360 to 720, 900 to 360, 1600 to 900)) {
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
                val shares = (1..8).map {
                    StorageShare(path, "share-$it", StorageShareKind.INVITATION, "reader",
                        recipient = "recipient-$it@example.test")
                }
                var revoked: StorageShare? = null
                var created: StorageShareRequest? = null
                setContent {
                    MaterialTheme {
                        StorageDetailsDialog(
                            StorageDetailsDialogUiState("long-file-name.txt", StorageDetailsMode.Sharing, false,
                                capabilities = caps, shares = shares),
                            onDismiss = {}, onRevokeShare = { revoked = it }, onCreateShare = { created = it },
                        )
                    }
                }
                onNodeWithText("recipient-8@example.test").performScrollTo().assertIsDisplayed()
                onAllNodesWithText(AppStrings.storage_revoke_share).onLast()
                    .performScrollTo().assertIsDisplayed().performClick()
                onNodeWithText(AppStrings.storage_revoke_confirmation).assertIsDisplayed()
                onAllNodesWithText(AppStrings.ui_cancel).onLast().assertIsDisplayed().performClick()
                assertEquals(null, revoked)
                onNodeWithText(AppStrings.storage_revoke_confirmation).assertDoesNotExist()
                onAllNodesWithText(AppStrings.storage_revoke_share).onLast().performScrollTo().performClick()
                onNode(hasText(AppStrings.storage_confirm_action) and hasClickAction()).assertIsDisplayed().performClick()
                assertEquals(shares.last(), revoked)
                onNodeWithText(AppStrings.storage_invite_user).performScrollTo().assertIsDisplayed().performClick()
                onNodeWithText(AppStrings.storage_share_recipient).performScrollTo()
                    .assertIsDisplayed().performTextInput("new-recipient@example.test")
                onNodeWithText(AppStrings.storage_role_write).performScrollTo().performClick()
                onNodeWithText(AppStrings.storage_share_expiry).performScrollTo()
                    .assertIsDisplayed().performTextInput("2027-01-01T00:00:00Z")
                onNode(hasText(AppStrings.storage_create_share) and hasClickAction())
                    .performScrollTo().assertIsDisplayed().performClick()
                onNodeWithText(AppStrings.storage_share_confirmation).assertIsDisplayed()
                onAllNodesWithText(AppStrings.ui_cancel).onLast().assertIsDisplayed().performClick()
                assertEquals(null, created)
                onNode(hasText(AppStrings.storage_create_share) and hasClickAction()).performScrollTo().performClick()
                onNode(hasText(AppStrings.storage_confirm_action) and hasClickAction()).assertIsDisplayed().performClick()
                val request = assertNotNull(created)
                assertEquals(StorageShareKind.INVITATION, request.kind)
                assertEquals("writer", request.role)
                assertEquals("new-recipient@example.test", request.recipient)
                assertTrue(request.expiresAt != null)
            }
        }
    }

    @Test
    fun shareFormPreservesExactSettingsAndRejectsUnsupportedExpiry() {
        val request = storageShareFormRequest(caps, StorageShareKind.INVITATION, "writer", "person@example.com", "2027-01-01T00:00:00Z").getOrThrow()
        val recipient = requireNotNull(request.recipient)
        assertEquals("person@example.com", recipient)
        assertEquals("writer", request.role)
        assertTrue(request.expiresAt != null)
        assertTrue(storageShareFormRequest(caps.copy(shareExpiry = false), request.kind, request.role,
            recipient, "2027-01-01T00:00:00Z").isFailure)
        assertTrue(storageShareFormRequest(caps, request.kind, "owner", recipient, "").isFailure)
        assertTrue(storageShareFormRequest(caps, request.kind, request.role, "", "").isFailure)
        assertTrue(storageShareFormRequest(caps, request.kind, request.role, recipient, "tomorrow").isFailure)
    }
}
