package com.folderspan.security

import strings.AppStrings

import com.folderspan.test.ChineseLocalizationTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidViewIntentSecurityTest : ChineseLocalizationTest() {
    @Test
    fun externalTextUsesTheCommonTextEntryAndKeepsFilesInTheShareList() {
        val handler = Files.readString(
            projectRoot().resolve(
                "app/shared/src/androidMain/kotlin/com/folderspan/share/ShareIntentHandler.android.kt"
            )
        )
        val sendHandler = handler
            .substringAfter("private fun handleSend(")
            .substringBefore("private fun handleSendMultiple(")

        assertTrue("ClipboardTextOpenBus.publish(sharedText)" in sendHandler)
        assertTrue("openHomeScreen()" in sendHandler)
        assertTrue("onComplete()" in sendHandler)
        assertFalse("clipboardUrlDownloadCoordinator" in sendHandler)
        assertTrue("fileShareState.updateIncomingFiles(normalizeShareListDropFiles(files))" in handler)
        assertTrue("mainState.requestOpenScreen(FileShareScreen)" in handler)
        val opensShare = handler
            .substringAfter("fun opensFileShareScreen(")
            .substringBefore("fun handle(")
        assertTrue("Intent.ACTION_VIEW -> hasGrantedContentViewUri(intent)" in opensShare)
        val viewHandler = handler
            .substringAfter("private fun handleView(")
            .substringBefore("private fun hasGrantedContentViewUri(")
        assertTrue("handleSharedFiles(listOf(uri), onComplete)" in viewHandler)
        assertFalse("handleOpenFile" in viewHandler)
        assertFalse("openHomeScreen()" in viewHandler)
        assertTrue("ShareHandler.handleDroppedFilesForShare" in handler)
    }

    @Test
    fun exportedViewIntentAcceptsAnyGrantedContentUriWithoutLoadingIt() {
        val manifest = Files.readString(
            projectRoot().resolve("app/androidApp/src/main/AndroidManifest.xml")
        )
        val viewFilter = Regex("""<intent-filter>[\s\S]*?</intent-filter>""")
            .findAll(manifest)
            .map { match -> match.value }
            .firstOrNull { filter -> "android.intent.action.VIEW" in filter }
            .orEmpty()

        assertTrue(viewFilter.isNotEmpty(), "ACTION_VIEW intent filter must remain explicit")
        assertTrue("android:scheme=\"content\"" in viewFilter)
        assertTrue("android:mimeType=\"*/*\"" in viewFilter)
        assertFalse("android:scheme=\"file\"" in viewFilter)
        assertFalse("android.intent.category.BROWSABLE" in viewFilter)

        val handler = Files.readString(
            projectRoot().resolve(
                "app/shared/src/androidMain/kotlin/com/folderspan/share/ShareIntentHandler.android.kt"
            )
        )
        val viewGuard = Regex(
            """private fun hasGrantedContentViewUri\(intent: Intent\): Boolean \{[\s\S]*?\n    \}"""
        ).find(handler)?.value.orEmpty()
        assertTrue(viewGuard.isNotEmpty(), "ACTION_VIEW runtime guard must remain explicit")
        assertTrue("grantedShareUri(intent, intent.data)" in viewGuard)
        val grantedUriGuard = Regex(
            """private fun grantedShareUri\(intent: Intent, uri: Uri\?\): Uri\? \{[\s\S]*?\n    \}"""
        ).find(handler)?.value.orEmpty()
        assertTrue(grantedUriGuard.isNotEmpty(), "content URI permission guard must remain explicit")
        assertTrue("uri.scheme != ContentResolver.SCHEME_CONTENT" in grantedUriGuard)
        assertTrue("hasSystemUriGrant(uri)" in grantedUriGuard)
        val systemGrantGuard = Regex(
            """private fun hasSystemUriGrant\(uri: Uri\): Boolean \{[\s\S]*?\n    \}"""
        ).find(handler)?.value.orEmpty()
        assertTrue(systemGrantGuard.isNotEmpty(), "system URI permission check must remain explicit")
        assertTrue("Intent.FLAG_GRANT_READ_URI_PERMISSION" in systemGrantGuard)
        assertFalse("isSupportedViewMimeType" in handler)

        val shareHandler = Files.readString(
            projectRoot().resolve("core/src/androidMain/kotlin/com/folderspan/utils/ShareHandler.kt")
        )
        val shareListPath = shareHandler
            .substringAfter("fun handleSharedFilesForShare(")
            .substringBefore(AppStrings.ui_test_android_view_intent_security_process_drag_and_drop_import_across)
        assertTrue("getFileInfoFromUri(activity.contentResolver, uri)" in shareListPath)
        assertTrue("publishIncomingShareDesk(fileState, uniqueUris, sharedFiles)" in shareListPath)
        assertFalse("openInputStream" in shareListPath)
        val deskPublisher = shareHandler
            .substringAfter("private fun publishIncomingShareDesk(")
            .substringBefore("fun handleOpenFile(")
        assertTrue("SharedUriFileRegistry.put" in deskPublisher)
        assertTrue("fileState.updateDesk" in deskPublisher)
        assertTrue("ensureSystemShareDesk()" in deskPublisher)
    }

    private fun projectRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath()
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent
        }
        error("Could not locate project root")
    }
}
