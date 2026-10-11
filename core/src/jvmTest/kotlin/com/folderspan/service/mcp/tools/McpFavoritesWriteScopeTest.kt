package com.folderspan.service.mcp.tools

import com.folderspan.data.file.FileProtocol
import com.folderspan.service.mcp.auth.McpTokenScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpFavoritesWriteScopeTest {
    @Test
    fun favoritesWriteCannotListOrCallAddWithoutFilesRead() = runTest {
        val registry = McpToolRegistry()
        val writeOnly = setOf(McpTokenScope.FavoritesWrite)
        val names = registry.list(writeOnly).map { definition -> definition.name }

        assertFalse("folderspan_favorites_add" in names)
        assertFalse("folderspan_favorites_remove" in names)
        assertFalse("folderspan_file_info" in names)
        assertFalse("folderspan_file_read" in names)

        val deniedAdd = registry.call("folderspan_favorites_add", sampleLocatorArguments(), writeOnly)
        val deniedInfo = registry.call("folderspan_file_info", sampleLocatorArguments(), writeOnly)
        assertTrue(deniedAdd.isError)
        assertTrue(deniedInfo.isError)
        assertEquals("permission_denied", deniedAdd.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals("permission_denied", deniedInfo.structuredContent?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun favoritesAddRequiresFilesReadAndFavoritesWrite() = runTest {
        val registry = McpToolRegistry()
        val readOnly = setOf(McpTokenScope.FilesRead)
        val both = setOf(McpTokenScope.FavoritesWrite, McpTokenScope.FilesRead)

        assertFalse("folderspan_favorites_add" in registry.list(readOnly).map { definition -> definition.name })
        assertTrue("folderspan_favorites_add" in registry.list(both).map { definition -> definition.name })
        assertTrue("folderspan_favorites_remove" in registry.list(both).map { definition -> definition.name })
        assertTrue("folderspan_favorites_pin" in registry.list(setOf(McpTokenScope.FavoritesWrite)).map { definition -> definition.name })

        val deniedReadOnly = registry.call("folderspan_favorites_add", sampleLocatorArguments(), readOnly)
        assertTrue(deniedReadOnly.isError)
        assertEquals("permission_denied", deniedReadOnly.structuredContent?.get("code")?.jsonPrimitive?.content)
    }
}

private fun sampleLocatorArguments() = buildJsonObject {
    put(
        "locator",
        buildJsonObject {
            put("protocol", FileProtocol.Local.name)
            put("path", "/tmp/notes.txt")
        },
    )
}
