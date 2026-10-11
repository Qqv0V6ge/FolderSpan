package com.folderspan.service.mcp.tools

import com.folderspan.data.file.FileProtocol
import com.folderspan.service.mcp.auth.McpTokenScope
import com.folderspan.service.mcp.automation.McpAutomationException
import com.folderspan.service.mcp.automation.McpFileFacade
import com.folderspan.service.mcp.automation.resolveMcpLinkShareAllowUpload
import com.folderspan.service.mcp.file.FileContentReader
import com.folderspan.service.mcp.file.FileEndpointResolver
import com.folderspan.service.mcp.file.FileGatewayTaskSubmitter
import com.folderspan.service.mcp.file.FileGatewayTransferCoordinator
import com.folderspan.service.mcp.file.LocalFileEndpointGateway
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TestTaskFailureResultStore
import com.folderspan.ui.state.main.TestTaskRuntimePersistenceStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpFileWriteToolTest {
    @Test
    fun toolsRequireFilesWriteAndExposeStrictSchemas() = runTest {
        val registry = McpToolRegistry()
        val directory = Files.createTempDirectory("mcp-write-tool-schema-").toFile()
        try {
            val target = File(directory, "notes.txt")
            val readToolNames = registry.list(setOf(McpTokenScope.FilesRead)).map { definition -> definition.name }
            val writeTools = registry.list(setOf(McpTokenScope.FilesWrite))
            val writeToolNames = writeTools.map { definition -> definition.name }

            assertFalse("folderspan_file_write" in readToolNames)
            assertFalse("folderspan_directory_create" in readToolNames)
            assertTrue("folderspan_file_write" in writeToolNames)
            assertTrue("folderspan_directory_create" in writeToolNames)
            assertTrue(writeTools.single { it.name == "folderspan_file_write" }.annotations?.destructiveHint == true)
            assertTrue(writeTools.single { it.name == "folderspan_directory_create" }.annotations?.destructiveHint == true)
            assertTrue(writeTools.single { it.name == "folderspan_file_rename" }.annotations?.destructiveHint == true)

            val writeSchema = writeTools.single { it.name == "folderspan_file_write" }.inputSchema
            assertEquals(false, writeSchema["additionalProperties"]?.jsonPrimitive?.content?.toBoolean())
            assertTrue(writeSchema["required"]?.toString()?.contains("data") == true)
            assertTrue(writeSchema["properties"]?.jsonObject?.keys?.containsAll(
                setOf("locator", "data", "encoding", "mode", "expectedSize", "expectedUpdatedAt")
            ) == true)

            val denied = registry.call("folderspan_file_write", writeArgs(target, "hello"), emptySet())
            assertTrue(denied.isError)
            assertEquals("permission_denied", denied.structuredContent?.get("code")?.jsonPrimitive?.content)
            assertFalse(target.exists())

            val unknownArgument = registry.call(
                "folderspan_file_write",
                buildJsonObject {
                    put("locator", locatorObject(target))
                    put("data", "hello")
                    put("unknown", true)
                },
                setOf(McpTokenScope.FilesWrite),
            )
            assertToolError(unknownArgument, "invalid_argument")

            val missingData = registry.call(
                "folderspan_file_write",
                buildJsonObject { put("locator", locatorObject(target)) },
                setOf(McpTokenScope.FilesWrite),
            )
            assertToolError(missingData, "invalid_argument")

            val invalidMode = registry.call(
                "folderspan_file_write",
                writeArgs(target, "hello", mode = "patch"),
                setOf(McpTokenScope.FilesWrite),
            )
            assertToolError(invalidMode, "invalid_argument")

            val negativePrecondition = registry.call(
                "folderspan_file_write",
                buildJsonObject {
                    put("locator", locatorObject(target))
                    put("data", "hello")
                    put("expectedSize", -1)
                },
                setOf(McpTokenScope.FilesWrite),
            )
            assertToolError(negativePrecondition, "invalid_argument")

        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun shareToolsRequireFilesReadInAdditionToFilesShare() = runTest {
        val registry = McpToolRegistry()
        val shareOnly = setOf(McpTokenScope.FilesShare)
        val listed = registry.list(shareOnly).map { definition -> definition.name }
        val args = buildJsonObject {
            put(
                "locators",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("protocol", FileProtocol.Local.name)
                            put("path", "/tmp/share.txt")
                        },
                    )
                },
            )
        }

        assertFalse("folderspan_files_share_link" in listed)
        assertFalse("folderspan_files_share_device" in listed)

        val deniedLink = registry.call("folderspan_files_share_link", args, shareOnly)
        val deniedDevice = registry.call(
            "folderspan_files_share_device",
            buildJsonObject {
                put("locators", args.getValue("locators"))
                put("deviceId", "device-1")
            },
            shareOnly,
        )
        assertToolError(deniedLink, "permission_denied")
        assertToolError(deniedDevice, "permission_denied")
    }

    @Test
    fun shareLinkAllowUploadRequiresFilesWrite() = runTest {
        val registry = McpToolRegistry()
        val shareAndRead = setOf(McpTokenScope.FilesShare, McpTokenScope.FilesRead)
        val args = buildJsonObject {
            put(
                "locators",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("protocol", FileProtocol.Local.name)
                            put("path", "/tmp/share.txt")
                        },
                    )
                },
            )
            put("allowUpload", true)
        }

        assertTrue("folderspan_files_share_link" in registry.list(shareAndRead).map { it.name })
        val denied = registry.call("folderspan_files_share_link", args, shareAndRead)
        assertToolError(denied, "permission_denied")
        assertEquals(
            "writable link share requires files.write",
            denied.structuredContent?.get("message")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun shareLinkAllowUploadIsRejectedWhenHostUploadSwitchIsOff() {
        assertFalse(resolveMcpLinkShareAllowUpload(requestedAllowUpload = false, hostAllowUpload = false))
        assertFalse(resolveMcpLinkShareAllowUpload(requestedAllowUpload = false, hostAllowUpload = true))
        val denied = assertFailsWith<McpAutomationException> {
            resolveMcpLinkShareAllowUpload(requestedAllowUpload = true, hostAllowUpload = false)
        }
        assertEquals("permission_denied", denied.code)
        assertTrue(resolveMcpLinkShareAllowUpload(requestedAllowUpload = true, hostAllowUpload = true))
    }

    @Test
    fun toolsWriteThroughTheRealLocalFacadeAndGateway() = runTest {
        val directory = Files.createTempDirectory("mcp-write-tool-local-").toRealPath().toFile()
        try {
            val gateway = LocalFileEndpointGateway()
            val resolver = FileEndpointResolver(mapOf(FileProtocol.Local to { gateway }))
            val fileFacade = McpFileFacade(
                resolver = resolver,
                reader = FileContentReader(resolver),
                submitter = FileGatewayTaskSubmitter(
                    taskState = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore()),
                    coordinator = FileGatewayTransferCoordinator(resolver),
                    scope = backgroundScope,
                ),
            )
            val registry = McpToolRegistry(fileFacade = fileFacade)
            val target = File(directory, "notes.txt")
            val createdDirectory = File(directory, "created")
            val scopes = setOf(McpTokenScope.FilesWrite)

            val overwritten = registry.call("folderspan_file_write", writeArgs(target, "hello"), scopes)
            val appended = registry.call("folderspan_file_write", writeArgs(target, "!", mode = "append"), scopes)
            val cleared = registry.call("folderspan_file_write", writeArgs(target, ""), scopes)
            val directoryResult = registry.call(
                "folderspan_directory_create",
                buildJsonObject { put("locator", locatorObject(createdDirectory)) },
                scopes,
            )

            assertFalse(overwritten.isError, overwritten.toString())
            assertEquals(5L, overwritten.structuredContent?.get("bytesWritten")?.jsonPrimitive?.content?.toLong())
            assertFalse(appended.isError)
            assertEquals(6L, appended.structuredContent?.get("entry")?.jsonObject?.get("size")?.jsonPrimitive?.content?.toLong())
            assertFalse(cleared.isError)
            assertEquals(0L, target.length())
            assertFalse(directoryResult.isError)
            assertTrue(createdDirectory.isDirectory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeArgs(file: File, data: String, mode: String? = null) = buildJsonObject {
        put("locator", locatorObject(file))
        put("data", data)
        mode?.let { value -> put("mode", value) }
    }

    private fun locatorObject(file: File) = buildJsonObject {
        put("protocol", FileProtocol.Local.name)
        put("path", file.absolutePath)
    }

    private fun assertToolError(result: com.folderspan.service.mcp.protocol.McpCallToolResult, code: String) {
        assertTrue(result.isError)
        assertEquals(code, result.structuredContent?.get("code")?.jsonPrimitive?.content)
    }
}
