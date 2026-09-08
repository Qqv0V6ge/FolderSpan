package com.folderspan.service.http.server.templates.route

import strings.AppStrings

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.test.ChineseLocalizationTest
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexTemplateTest : ChineseLocalizationTest() {
    @Test
    fun fileCardRendersSeparateBrowserPreviewAndDownloadLinks() {
        val file = file(
            name = AppStrings.ui_test_index_template_report_1_pdf,
            path = "/shared/%E6%8A%A5%E5%91%8A%201.pdf",
        )
        val html = createHTML(prettyPrint = false).div {
            val content = this
            with(IndexTemplate()) {
                content.renderFileGrid(listOf(file))
            }
        }

        assertContains(html, "href=\"/shared/%E6%8A%A5%E5%91%8A%201.pdf?preview=1\"")
        assertContains(html, "target=\"_blank\"")
        assertContains(html, "rel=\"noopener\"")
        assertContains(html, "type=\"application/pdf\"")
        assertContains(html, "data-preview-mime=\"application/pdf\"")
        assertContains(html, AppStrings.ui_test_index_template_aria_label_in_the_browser_open_report_1_pdf)
        assertContains(html, "href=\"/shared/%E6%8A%A5%E5%91%8A%201.pdf\"")
        assertContains(html, AppStrings.ui_test_index_template_download_report_1_pdf)
        assertContains(html, AppStrings.ui_test_index_template_aria_label_download_report_1_pdf)

        val contentStart = html.indexOf("class=\"file-card__content\"")
        val contentTagStart = html.lastIndexOf("<a", startIndex = contentStart)
        val contentEnd = html.indexOf("</a>", startIndex = contentStart)
        val actionsStart = html.indexOf("class=\"file-card__actions\"")
        val previewStart = html.indexOf("class=\"file-card__open\"")
        val previewEnd = html.indexOf("</a>", startIndex = previewStart)
        val downloadStart = html.indexOf("class=\"file-card__download\"")
        assertTrue(contentTagStart >= 0)
        assertTrue(contentStart >= 0)
        assertTrue(contentEnd > contentStart)
        assertTrue(actionsStart > contentEnd, AppStrings.ui_test_index_template_the_file_body_download_link_must_be_displayed_alongside)
        val contentLink = html.substring(contentTagStart, contentEnd)
        assertContains(contentLink, "href=\"/shared/%E6%8A%A5%E5%91%8A%201.pdf\"")
        assertContains(contentLink, AppStrings.ui_test_index_template_download_report_1_pdf)
        assertTrue("preview=1" !in contentLink, AppStrings.ui_test_index_template_click_on_the_file_subject_to_download_and_cannot)
        assertTrue(previewStart >= 0)
        assertTrue(previewEnd > previewStart)
        assertTrue(downloadStart > previewEnd, AppStrings.ui_test_index_template_the_download_link_must_be_displayed_alongside_the)
        assertTrue("file-card__open ripple" !in html)
        assertTrue("file-card__download ripple" !in html)
    }

    @Test
    fun unsupportedFileOnlyRendersDownloadAction() {
        val file = file(
            name = "gradle.properties",
            path = "/shared/gradle.properties",
            mineType = ".properties",
        )
        val html = createHTML(prettyPrint = false).div {
            val content = this
            with(IndexTemplate()) {
                content.renderFileGrid(listOf(file))
            }
        }

        assertTrue("preview=1" !in html)
        assertTrue("file-card__open" !in html)
        assertContains(html, "href=\"/shared/gradle.properties\"")
        assertContains(html, "download=\"gradle.properties\"")
        assertContains(html, AppStrings.ui_test_index_template_aria_label_download_gradle_properties)
    }

    @Test
    fun directoryCardKeepsNavigationBehavior() {
        val directory = file(
            name = AppStrings.ui_test_index_template_data,
            path = "/shared/%E8%B5%84%E6%96%99",
            isDirectory = true,
        )
        val html = createHTML(prettyPrint = false).div {
            val content = this
            with(IndexTemplate()) {
                content.renderFileGrid(listOf(directory))
            }
        }

        assertContains(html, "href=\"/shared/%E8%B5%84%E6%96%99\"")
        assertTrue("preview=1" !in html)
        assertTrue("download=" !in html)
    }

    @Test
    fun hiddenEntriesRenderWithDistinctCardClassAndKeepInteractions() {
        fun render(file: FileSimpleInfo): String = createHTML(prettyPrint = false).div {
            val content = this
            with(IndexTemplate()) {
                content.renderFileGrid(listOf(file))
            }
        }

        val hiddenFileHtml = render(
            file(
                name = ".secret.pdf",
                path = "/shared/.secret.pdf",
                isHidden = true,
            )
        )
        assertContains(hiddenFileHtml, "class=\"file-card file-card--file file-card--hidden\"")
        assertContains(hiddenFileHtml, "href=\"/shared/.secret.pdf\"")
        assertContains(hiddenFileHtml, "download=\".secret.pdf\"")

        val hiddenDirectoryHtml = render(
            file(
                name = ".private",
                path = "/shared/.private",
                isDirectory = true,
                isHidden = true,
            )
        )
        assertContains(
            hiddenDirectoryHtml,
            "class=\"file-card file-card--directory ripple file-card--hidden\"",
        )
        assertContains(hiddenDirectoryHtml, "href=\"/shared/.private\"")
        assertTrue("download=" !in hiddenDirectoryHtml)

        val visibleFileHtml = render(file(name = "visible.pdf", path = "/shared/visible.pdf"))
        assertContains(visibleFileHtml, "class=\"file-card file-card--file\"")
        assertTrue("file-card--hidden" !in visibleFileHtml)

        val visibleDirectoryHtml = render(
            file(
                name = "visible-dir",
                path = "/shared/visible-dir",
                isDirectory = true,
            )
        )
        assertContains(visibleDirectoryHtml, "class=\"file-card file-card--directory ripple\"")
        assertTrue("file-card--hidden" !in visibleDirectoryHtml)
    }

    @Test
    fun previewUrlPreservesExistingQueryAndFragment() {
        assertEquals(
            "/shared/file.txt?token=abc&preview=1#page",
            linkSharePreviewUrl("/shared/file.txt?token=abc#page"),
        )
    }

    private fun file(
        name: String,
        path: String,
        isDirectory: Boolean = false,
        isHidden: Boolean = false,
        mineType: String = if (isDirectory) "" else "application/pdf",
    ): FileSimpleInfo {
        return FileSimpleInfo(
            name = name,
            isDirectory = isDirectory,
            isHidden = isHidden,
            path = path,
            mineType = mineType,
            size = if (isDirectory) 2L else 128L,
            createdDate = 0L,
            updatedDate = 0L,
        )
    }
}
