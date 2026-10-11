package com.folderspan.service.http.server

import strings.AppStrings

import com.folderspan.shared.generated.resources.Res
import com.folderspan.test.ChineseLocalizationTest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareDownloadScriptResourceTest : ChineseLocalizationTest() {

    @Test
    fun cmdDownloadScriptResourceIsAvailable() = runBlocking {
        val content = Res.readBytes("files/share-file/shell/script.bat").decodeToString()

        assertTrue(content.startsWith("@echo off"))
        assertContains(content, "where curl")
        assertContains(content, "call :ProcessFilesAndDirectories")
        assertContains(content, "curl -s -f -H \"X-API-Request: true\"")
        assertContains(content, AppStrings.ui_test_share_download_script_resource_rem_declaration_of_global_variable)
        assertContains(content, AppStrings.ui_test_share_download_script_resource_rem_parse_json_list_directory)
        assertFalse(content.contains("powershell -NoProfile"))
        assertContains(content, "#API_SERVER#")
        assertContains(content, "#ROOT_PATH#")
    }
}
