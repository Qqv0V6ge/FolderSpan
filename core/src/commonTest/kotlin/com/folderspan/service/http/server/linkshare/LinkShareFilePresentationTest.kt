package com.folderspan.service.http.server.linkshare

import strings.AppStrings

import com.folderspan.test.ChineseLocalizationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkShareFilePresentationTest : ChineseLocalizationTest() {
    @Test
    fun previewQueryUsesPreviewDeliveryForBrowserRequest() {
        val request = request("/manual.pdf?preview=1")

        assertEquals(LinkShareFileDelivery.Preview, request.resolveFileDelivery())
    }

    @Test
    fun apiRequestKeepsAttachmentDeliveryEvenWithPreviewQuery() {
        val request = request(
            rawUri = "/manual.pdf?preview=1",
            headers = mapOf("X-API-Request" to listOf("true")),
        )

        assertEquals(LinkShareFileDelivery.Attachment, request.resolveFileDelivery())
    }

    @Test
    fun mediaTypeResolutionUsesPlatformThenMetadataThenExtension() {
        assertEquals(
            "image/png",
            resolveLinkShareContentType(
                fileName = "photo.jpg",
                fileMimeTypeHint = "image/jpeg",
                platformMimeTypeHint = "image/png",
            ),
        )
        assertEquals(
            "application/pdf",
            resolveLinkShareContentType(
                fileName = "document.bin",
                fileMimeTypeHint = ".pdf",
                platformMimeTypeHint = "application/octet-stream",
            ),
        )
        assertEquals("application/json", resolveLinkShareContentType("data.json"))
        assertEquals(LINK_SHARE_DEFAULT_CONTENT_TYPE, resolveLinkShareContentType("README"))
    }

    @Test
    fun mediaTypeResolutionDoesNotDependOnUserAgent() {
        val chrome = request("/video.mp4", mapOf("User-Agent" to listOf("Chrome")))
        val safari = request("/video.mp4", mapOf("User-Agent" to listOf("Safari")))

        assertEquals(chrome.resolveFileDelivery(), safari.resolveFileDelivery())
        assertEquals("video/mp4", resolveLinkShareContentType("video.mp4"))
    }

    @Test
    fun previewEligibilityIncludesDisplayTypesAndRejectsUnknownOrBinaryFiles() {
        assertTrue(isLinkShareBrowserPreviewEligible("manual.pdf"))
        assertTrue(isLinkShareBrowserPreviewEligible("photo.png"))
        assertTrue(isLinkShareBrowserPreviewEligible("notes.txt"))
        assertFalse(isLinkShareBrowserPreviewEligible("gradle.properties", ".properties"))
        assertFalse(
            isLinkShareBrowserPreviewEligible(
                fileName = "gradle.properties",
                fileMimeTypeHint = ".properties",
                platformMimeTypeHint = "text/plain",
            ),
        )
        assertFalse(isLinkShareBrowserPreviewEligible("archive.zip", "application/zip"))
        assertFalse(isLinkShareBrowserPreviewEligible("module.wasm"))
    }

    @Test
    fun knownPreviewExtensionOverridesNonDisplayPlatformMimeForPreviewOnly() {
        val preview = resolveLinkShareFilePresentation(
            fileName = "manual.pdf",
            requestedDelivery = LinkShareFileDelivery.Preview,
            platformMimeTypeHint = "application/x-pdf",
        )
        val attachment = resolveLinkShareFilePresentation(
            fileName = "manual.pdf",
            requestedDelivery = LinkShareFileDelivery.Attachment,
            platformMimeTypeHint = "application/x-pdf",
        )

        assertEquals(LinkShareFileDelivery.Preview, preview.delivery)
        assertEquals("application/pdf", preview.contentType)
        assertTrue(
            preview.headers.first { item -> item.name == "Content-Disposition" }
                .value.startsWith("inline;"),
        )
        assertEquals(LinkShareFileDelivery.Attachment, attachment.delivery)
        assertEquals("application/x-pdf", attachment.contentType)
    }

    @Test
    fun unknownPreviewRequestFallsBackToAttachment() {
        val presentation = resolveLinkShareFilePresentation(
            fileName = "gradle.properties",
            requestedDelivery = LinkShareFileDelivery.Preview,
            fileMimeTypeHint = ".properties",
            platformMimeTypeHint = "text/plain",
        )

        assertEquals("text/plain", presentation.contentType)
        assertEquals(LinkShareFileDelivery.Attachment, presentation.delivery)
        assertTrue(
            presentation.headers.first { item -> item.name == "Content-Disposition" }
                .value.startsWith("attachment;"),
        )
    }

    @Test
    fun textPreviewDeclaresUtf8CharsetWithoutChangingAttachmentOrBinaryPreviewType() {
        val textPreview = resolveLinkShareFilePresentation(
            fileName = "notes.txt",
            requestedDelivery = LinkShareFileDelivery.Preview,
        )
        val textAttachment = resolveLinkShareFilePresentation(
            fileName = "notes.txt",
            requestedDelivery = LinkShareFileDelivery.Attachment,
        )
        val pdfPreview = resolveLinkShareFilePresentation(
            fileName = "manual.pdf",
            requestedDelivery = LinkShareFileDelivery.Preview,
        )

        assertEquals("text/plain; charset=UTF-8", textPreview.contentType)
        assertEquals("text/plain", textAttachment.contentType)
        assertEquals("application/pdf", pdfPreview.contentType)
    }

    @Test
    fun contentDispositionEncodesUnicodeAndRemovesHeaderBreaks() {
        val value = contentDisposition(
            LinkShareFileDelivery.Preview,
            "${AppStrings.ui_test_link_share_file_presentation_test}\r\nfile.pdf",
        )

        assertTrue(value.startsWith("inline; filename=\"____file.pdf\""))
        assertTrue(value.contains("filename*=UTF-8''%E6%B5%8B%E8%AF%95__file.pdf"))
        assertFalse(value.contains('\r'))
        assertFalse(value.contains('\n'))
    }

    @Test
    fun activeContentUsesSandboxEvenWhenMimeHintConflictsWithExtension() {
        val presentation = resolveLinkShareFilePresentation(
            fileName = "page.html",
            requestedDelivery = LinkShareFileDelivery.Preview,
            fileMimeTypeHint = "text/plain",
        )

        assertEquals(LinkShareFileDelivery.Preview, presentation.delivery)
        assertTrue(presentation.activeContent)
        assertEquals(
            LINK_SHARE_ACTIVE_CONTENT_CSP,
            presentation.headers.first { item -> item.name == "Content-Security-Policy" }.value,
        )
        assertFalse(LINK_SHARE_ACTIVE_CONTENT_CSP.contains("allow-scripts"))
        assertFalse(LINK_SHARE_ACTIVE_CONTENT_CSP.contains("allow-same-origin"))
    }

    @Test
    fun activeContentFallsBackToAttachmentWithoutSandboxSupport() {
        val presentation = resolveLinkShareFilePresentation(
            fileName = "vector.svg",
            requestedDelivery = LinkShareFileDelivery.Preview,
            sandboxSupported = false,
        )

        assertEquals(LinkShareFileDelivery.Attachment, presentation.delivery)
        assertTrue(presentation.activeContent)
        assertFalse(presentation.headers.any { item -> item.name == "Content-Security-Policy" })
        assertTrue(
            presentation.headers.first { item -> item.name == "Content-Disposition" }
                .value.startsWith("attachment;"),
        )
    }

    private fun request(
        rawUri: String,
        headers: Map<String, List<String>> = emptyMap(),
    ): LinkShareHttpRequest {
        return LinkShareHttpRequest.from(
            method = "GET",
            rawUri = rawUri,
            headers = headers,
        )
    }
}
