package com.folderspan.service.http.server.linkshare

import strings.AppStrings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkShareDownloadScriptEscapingTest {
    @Test
    fun bashEscapesDoubleQuoteDollarAndBacktickWithoutSplittingLines() {
        val escaped = escapeLinkShareDownloadScriptValue(
            "Mozilla/5.0\"\$`",
            LinkShareDownloadScriptKind.Bash,
        )
        assertEquals("Mozilla/5.0\\\"\\\$\\`", escaped)
        assertFalse('\n' in escaped)
        assertFalse('\r' in escaped)
    }

    @Test
    fun powershellEscapesQuoteDollarAndBacktick() {
        val escaped = escapeLinkShareDownloadScriptValue(
            "ok\"\$`",
            LinkShareDownloadScriptKind.PowerShell,
        )
        assertEquals("ok`\"`\$``", escaped)
    }

    @Test
    fun cmdStripsMetacharactersIncludingPercent() {
        val escaped = escapeLinkShareDownloadScriptValue(
            "dir%name!&|<>^\"",
            LinkShareDownloadScriptKind.Cmd,
        )
        assertEquals("dir_name_______", escaped)
        assertTrue(escaped.all { char -> char != '%' && char != '"' && char != '!' })
    }

    @Test
    fun controlCharactersBecomeUnderscore() {
        val escaped = escapeLinkShareDownloadScriptValue(
            "ok\nInjected\r\u0000",
            LinkShareDownloadScriptKind.Bash,
        )
        assertEquals("ok_Injected__", escaped)
        assertFalse('\n' in escaped)
        assertFalse('\r' in escaped)
        assertFalse('\u0000' in escaped)
    }

    @Test
    fun targetDirKeepsUnicodeButDropsSeparators() {
        assertEquals(AppStrings.ui_project, sanitizeLinkShareScriptFileName(AppStrings.ui_project))
        assertEquals("abc", sanitizeLinkShareScriptFileName("a/b\\c"))
        assertEquals("folderspan-download", sanitizeLinkShareScriptFileName(".."))
        assertEquals("folderspan-download", sanitizeLinkShareScriptFileName("."))
        assertEquals("", sanitizeLinkShareScriptFileName(""))
    }

    @Test
    fun bashRootPathIsPercentEncoded() {
        assertEquals(
            "/share/project%22%24%60",
            encodeLinkShareScriptRootPath("/share/project\"\$`", LinkShareDownloadScriptKind.Bash),
        )
    }

    @Test
    fun cmdRootPathDoesNotIntroducePercentVariables() {
        val encoded = encodeLinkShareScriptRootPath(
            "/share/dir%name",
            LinkShareDownloadScriptKind.Cmd,
        )
        assertEquals("/share/dir_name", encoded)
        assertFalse('%' in encoded)
    }

    @Test
    fun ordinaryUserAgentIsUnchanged() {
        val userAgent = "Mozilla/5.0"
        assertEquals(userAgent, escapeLinkShareDownloadScriptValue(userAgent, LinkShareDownloadScriptKind.Bash))
        assertEquals(userAgent, escapeLinkShareDownloadScriptValue(userAgent, LinkShareDownloadScriptKind.PowerShell))
        assertEquals(userAgent, escapeLinkShareDownloadScriptValue(userAgent, LinkShareDownloadScriptKind.Cmd))
    }
}
