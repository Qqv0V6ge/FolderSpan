package com.folderspan.service.mcp.tools

import com.folderspan.data.file.FileProtocol
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.mcp.auth.McpTokenScope
import com.folderspan.service.mcp.automation.McpAutomationException
import com.folderspan.service.mcp.automation.McpAutomationFacade
import com.folderspan.service.mcp.automation.McpFileFacade
import com.folderspan.service.mcp.automation.toMcpAutomationException
import com.folderspan.service.mcp.file.DEFAULT_FILE_RANGE_BYTES
import com.folderspan.service.mcp.file.FileContentEncoding
import com.folderspan.service.mcp.file.FileConflictPolicy
import com.folderspan.service.mcp.file.FileEndpointRef
import com.folderspan.service.mcp.file.FileLocator
import com.folderspan.service.mcp.file.FileWriteMode
import com.folderspan.service.mcp.file.MAX_FILE_WRITE_BYTES
import com.folderspan.service.mcp.protocol.McpCallToolResult
import com.folderspan.service.mcp.protocol.McpContent
import com.folderspan.service.mcp.protocol.McpToolAnnotations
import com.folderspan.service.mcp.protocol.McpToolDefinition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement

data class McpRegisteredTool(
    val definition: McpToolDefinition,
    val requiredScopes: Set<McpTokenScope>,
    val handler: suspend (JsonObject) -> JsonElement,
) {
    constructor(
        definition: McpToolDefinition,
        scope: McpTokenScope,
        handler: suspend (JsonObject) -> JsonElement,
    ) : this(definition, setOf(scope), handler)

    init {
        require(requiredScopes.isNotEmpty()) { "MCP tool must declare at least one scope" }
    }
}

class McpToolRegistry(
    private val facade: McpAutomationFacade? = null,
    tools: List<McpRegisteredTool> = emptyList(),
    private val fileFacade: McpFileFacade? = facade?.file,
) {
    private val tools = linkedMapOf<String, McpRegisteredTool>()

    init {
        (tools.ifEmpty { builtInTools() }).forEach(::register)
    }

    fun register(tool: McpRegisteredTool) {
        require(tool.definition.name.startsWith("folderspan_")) { "FolderSpan tool name must use the folderspan_ prefix" }
        require(this.tools.put(tool.definition.name, tool) == null) { "duplicate MCP tool: ${tool.definition.name}" }
    }

    fun list(scopes: Set<McpTokenScope>): List<McpToolDefinition> = tools.values
        .filter { tool -> scopes.containsAll(tool.requiredScopes) }
        .map { tool -> tool.definition }

    suspend fun call(
        name: String,
        arguments: JsonObject,
        scopes: Set<McpTokenScope>,
    ): McpCallToolResult {
        val tool = tools[name] ?: return errorResult("not_found", "tool was not found")
        if (!scopes.containsAll(tool.requiredScopes)) {
            return errorResult("permission_denied", "token does not allow this tool")
        }
        return try {
            McpJsonSchemaValidator.validate(arguments, tool.definition.inputSchema)
            if (
                name == "folderspan_files_share_link" &&
                arguments.boolean("allowUpload") == true &&
                McpTokenScope.FilesWrite !in scopes
            ) {
                return errorResult("permission_denied", "writable link share requires files.write")
            }
            val result = tool.handler(arguments)
            val structured = result as? JsonObject ?: buildJsonObject { put("value", result) }
            McpCallToolResult(
                content = listOf(McpContent(type = "text", text = TOOL_JSON.encodeToString(result))),
                structuredContent = structured,
            )
        } catch (error: Throwable) {
            val mapped = error.toMcpAutomationException()
            errorResult(mapped.code, mapped.message ?: "operation failed")
        }
    }

    private fun errorResult(code: String, message: String): McpCallToolResult {
        val data = buildJsonObject {
            put("code", code)
            put("message", message)
        }
        return McpCallToolResult(
            content = listOf(McpContent(type = "text", text = TOOL_JSON.encodeToString(data))),
            structuredContent = data,
            isError = true,
        )
    }

    private fun builtInTools(): List<McpRegisteredTool> {
        val facade by lazy(LazyThreadSafetyMode.NONE) {
            checkNotNull(this@McpToolRegistry.facade) { "McpAutomationFacade is required to call built-in tools" }
        }
        val fileFacade by lazy(LazyThreadSafetyMode.NONE) {
            checkNotNull(this@McpToolRegistry.fileFacade) { "McpFileFacade is required to call built-in file tools" }
        }
        return listOf(
        tool("folderspan_bookmarks_list", "List scoped bookmarks.", McpTokenScope.BookmarksRead, scopePageSchema(), readOnly = true) { args ->
            json(facade.catalog.listBookmarks(args.protocol(), args.sourceId(), args.string("cursor"), args.int("limit")))
        },
        tool("folderspan_bookmarks_create", "Create a scoped bookmark.", McpTokenScope.BookmarksWrite, bookmarkMutationSchema(includeId = false)) { args ->
            json(
                facade.catalog.createBookmark(
                    args.protocol(), args.sourceId(), args.requiredString("name"), args.requiredString("bookmarkType"),
                    args.requiredString("path"), args.string("icon"), args.long("sort"),
                ),
            )
        },
        tool("folderspan_bookmarks_update", "Update a scoped bookmark.", McpTokenScope.BookmarksWrite, bookmarkMutationSchema(includeId = true)) { args ->
            json(
                facade.catalog.updateBookmark(
                    args.protocol(), args.sourceId(), args.requiredLong("id"), args.requiredString("name"),
                    args.requiredString("bookmarkType"), args.requiredString("path"), args.string("icon"), args.long("sort"),
                ),
            )
        },
        tool("folderspan_bookmarks_delete", "Delete a scoped bookmark.", McpTokenScope.BookmarksWrite, scopeIdSchema(), destructive = true) { args ->
            jsonObject("deleted", facade.catalog.deleteBookmark(args.protocol(), args.sourceId(), args.requiredLong("id")))
        },
        tool("folderspan_favorites_list", "List favorites.", McpTokenScope.FavoritesRead, pageSchema(), readOnly = true) { args ->
            json(facade.catalog.listFavorites(args.string("cursor"), args.int("limit")))
        },
        tool(
            "folderspan_favorites_add",
            "Add a favorite by file locator.",
            McpTokenScope.FavoritesWrite,
            locatorSchema(),
            extraScopes = setOf(McpTokenScope.FilesRead),
        ) { args ->
            json(facade.catalog.addFavorite(args.locator()))
        },
        tool(
            "folderspan_favorites_remove",
            "Remove a favorite by file locator.",
            McpTokenScope.FavoritesWrite,
            locatorSchema(),
            destructive = true,
            extraScopes = setOf(McpTokenScope.FilesRead),
        ) { args ->
            jsonObject("removed", facade.catalog.removeFavorite(args.locator()))
        },
        tool("folderspan_favorites_pin", "Pin or unpin a favorite.", McpTokenScope.FavoritesWrite, idBooleanSchema("pinned")) { args ->
            json(facade.catalog.pinFavorite(args.requiredLong("id"), args.requiredBoolean("pinned")))
        },
        tool("folderspan_recents_list", "List existing recent records.", McpTokenScope.RecentsRead, pageSchema(), readOnly = true) { args ->
            json(facade.catalog.listRecents(args.string("cursor"), args.int("limit")))
        },
        tool("folderspan_recents_delete", "Delete recent records.", McpTokenScope.RecentsWrite, idsSchema(), destructive = true) { args ->
            jsonObject("deletedCount", facade.catalog.deleteRecents(args.longArray("ids")))
        },
        tool("folderspan_recents_clear", "Clear all recent records.", McpTokenScope.RecentsWrite, emptySchema(), destructive = true) {
            jsonObject("deletedCount", facade.catalog.clearRecents())
        },
        tool("folderspan_tasks_list", "List FolderSpan file-operation tasks.", McpTokenScope.TasksRead, pageSchema(), readOnly = true) { args ->
            json(facade.task.list(args.string("cursor"), args.int("limit")))
        },
        tool("folderspan_tasks_get", "Get a file-operation task.", McpTokenScope.TasksRead, idSchema(), readOnly = true) { args ->
            json(facade.task.get(args.requiredLong("id")))
        },
        tool("folderspan_tasks_pause", "Pause a running task.", McpTokenScope.TasksControl, idSchema()) { args ->
            json(facade.task.pause(args.requiredLong("id")))
        },
        tool("folderspan_tasks_resume", "Resume a paused task.", McpTokenScope.TasksControl, idSchema()) { args ->
            json(facade.task.resume(args.requiredLong("id")))
        },
        tool("folderspan_tasks_cancel", "Cancel a running or paused task.", McpTokenScope.TasksControl, idSchema(), destructive = true) { args ->
            json(facade.task.cancel(args.requiredLong("id")))
        },
        tool("folderspan_tasks_delete", "Delete a terminal task.", McpTokenScope.TasksControl, idSchema(), destructive = true) { args ->
            jsonObject("deleted", facade.task.delete(args.requiredLong("id")))
        },
        tool("folderspan_devices_list", "List currently online HTTP devices by status.", McpTokenScope.DevicesRead, emptySchema(), readOnly = true) {
            json(facade.device.list(DeviceTransportType.Session))
        },
        tool("folderspan_device_connect", "Connect a discovered online HTTP device.", McpTokenScope.DevicesConnect, stringIdSchema()) { args ->
            json(facade.device.connect(args.requiredString("id"), DeviceTransportType.Session))
        },
        tool("folderspan_webrtc_devices_list", "List currently discovered online WebRTC peers.", McpTokenScope.DevicesRead, emptySchema(), readOnly = true) {
            json(facade.device.list(DeviceTransportType.WebRtc))
        },
        tool("folderspan_webrtc_device_connect", "Connect a currently discovered WebRTC peer.", McpTokenScope.DevicesConnect, stringIdSchema()) { args ->
            json(facade.device.connect(args.requiredString("id"), DeviceTransportType.WebRtc))
        },
        tool("folderspan_networks_list", "List configured network sources without credentials.", McpTokenScope.NetworksRead, pageSchema(), readOnly = true) { args ->
            json(facade.network.list(args.string("cursor"), args.int("limit")))
        },
        tool("folderspan_network_connect", "Probe and connect a configured network source.", McpTokenScope.NetworksConnect, idSchema()) { args ->
            json(facade.network.connect(args.requiredLong("id")))
        },
        tool("folderspan_sync_list", "List synchronization tasks.", McpTokenScope.SyncRead, pageSchema(), readOnly = true) { args ->
            json(facade.sync.list(args.string("cursor"), args.int("limit")))
        },
        tool("folderspan_sync_run", "Run an idle synchronization task now.", McpTokenScope.SyncRun, idSchema()) { args ->
            json(facade.sync.run(args.requiredLong("id")))
        },
        tool("folderspan_files_list", "List direct children of a file locator.", McpTokenScope.FilesRead, locatorPageSchema(), readOnly = true) { args ->
            json(fileFacade.list(args.locator(), args.string("cursor"), args.int("limit")))
        },
        tool("folderspan_file_info", "Read metadata for a file locator.", McpTokenScope.FilesRead, locatorSchema(), readOnly = true) { args ->
            json(fileFacade.info(args.locator()))
        },
        tool("folderspan_file_read", "Read a bounded file range as UTF-8 or base64.", McpTokenScope.FilesRead, fileReadSchema(), readOnly = true) { args ->
            json(
                fileFacade.read(
                    args.locator(),
                    args.long("offset") ?: 0L,
                    args.int("length") ?: DEFAULT_FILE_RANGE_BYTES,
                    parseContentEncoding(args.string("encoding") ?: "utf8"),
                ),
            )
        },
        tool(
            "folderspan_file_write",
            "Create, replace, or append bounded UTF-8 or base64 file content.",
            McpTokenScope.FilesWrite,
            fileWriteSchema(),
            destructive = true,
        ) { args ->
            json(
                fileFacade.write(
                    locator = args.locator(),
                    data = args.requiredStringAllowingEmpty("data"),
                    encoding = parseContentEncoding(args.string("encoding") ?: "utf8"),
                    mode = parseWriteMode(args.string("mode") ?: "overwrite"),
                    expectedSize = args.long("expectedSize"),
                    expectedUpdatedAt = args.long("expectedUpdatedAt"),
                ),
            )
        },
        tool(
            "folderspan_directory_create",
            "Create one directory, or return an existing directory.",
            McpTokenScope.FilesWrite,
            locatorSchema(),
            destructive = true,
        ) { args ->
            json(fileFacade.createDirectory(args.locator()))
        },
        tool("folderspan_file_rename", "Rename one endpoint item.", McpTokenScope.FilesWrite, renameSchema(), destructive = true) { args ->
            json(fileFacade.rename(args.locator(), args.requiredString("newName")))
        },
        tool("folderspan_files_copy", "Start an asynchronous cross-endpoint copy task.", McpTokenScope.FilesWrite, transferSchema()) { args ->
            jsonObject("taskId", fileFacade.copy(args.locators("sources"), args.locator("target"), args.conflictPolicy()))
        },
        tool("folderspan_files_move", "Start an asynchronous cross-endpoint move task.", McpTokenScope.FilesWrite, transferSchema(), destructive = true) { args ->
            jsonObject("taskId", fileFacade.move(args.locators("sources"), args.locator("target"), args.conflictPolicy()))
        },
        tool("folderspan_files_delete", "Start an asynchronous delete task.", McpTokenScope.FilesWrite, locatorsSchema(), destructive = true) { args ->
            jsonObject("taskId", fileFacade.delete(args.locators("sources")))
        },
        tool(
            "folderspan_files_share_link",
            "Create a time-limited LAN link share.",
            McpTokenScope.FilesShare,
            shareLinkSchema(),
            extraScopes = setOf(McpTokenScope.FilesRead),
        ) { args ->
            json(
                fileFacade.shareLink(
                    args.locators("locators"),
                    args.boolean("allowHidden") ?: false,
                    args.boolean("allowUpload") ?: false,
                ),
            )
        },
        tool(
            "folderspan_files_share_device",
            "Start the existing share flow to an online connected device.",
            McpTokenScope.FilesShare,
            shareDeviceSchema(),
            extraScopes = setOf(McpTokenScope.FilesRead),
        ) { args ->
            json(
                fileFacade.shareDevice(
                    args.locators("locators"),
                    args.requiredString("deviceId"),
                    args.boolean("allowHidden") ?: false,
                ),
            )
        },
        )
    }

    private fun tool(
        name: String,
        description: String,
        scope: McpTokenScope,
        schema: JsonObject,
        readOnly: Boolean = false,
        destructive: Boolean = false,
        extraScopes: Set<McpTokenScope> = emptySet(),
        handler: suspend (JsonObject) -> JsonElement,
    ): McpRegisteredTool = McpRegisteredTool(
        definition = McpToolDefinition(
            name = name,
            description = description,
            inputSchema = schema,
            annotations = McpToolAnnotations(
                readOnlyHint = readOnly,
                destructiveHint = destructive,
                openWorldHint = false,
            ),
        ),
        requiredScopes = setOf(scope) + extraScopes,
        handler = handler,
    )
}

object McpJsonSchemaValidator {
    fun validate(value: JsonElement, schema: JsonObject, path: String = "$") {
        val nullable = schema["nullable"]?.jsonPrimitive?.booleanOrNull == true
        if (value is JsonNull && nullable) return
        schema["type"]?.jsonPrimitive?.contentOrNull?.let { type -> validateType(value, type, path) }
        schema["enum"]?.jsonArray?.let { allowed ->
            if (value !in allowed) invalid("$path must be one of the allowed values")
        }
        when (value) {
            is JsonObject -> validateObject(value, schema, path)
            is JsonArray -> validateArray(value, schema, path)
            is JsonPrimitive -> validatePrimitive(value, schema, path)
        }
    }

    private fun validateType(value: JsonElement, type: String, path: String) {
        val valid = when (type) {
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && value.longOrNull != null
            "number" -> value is JsonPrimitive && value.jsonPrimitive.content.toDoubleOrNull() != null
            "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
            "null" -> value is JsonNull
            else -> false
        }
        if (!valid) invalid("$path must be $type")
    }

    private fun validateObject(value: JsonObject, schema: JsonObject, path: String) {
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { item -> item.jsonPrimitive.contentOrNull }
        required.forEach { key -> if (value[key] == null || value[key] is JsonNull) invalid("$path.$key is required") }
        if (schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false) {
            value.keys.firstOrNull { key -> key !in properties }?.let { key -> invalid("$path.$key is not allowed") }
        }
        value.forEach { (key, child) ->
            (properties[key] as? JsonObject)?.let { childSchema -> validate(child, childSchema, "$path.$key") }
        }
    }

    private fun validateArray(value: JsonArray, schema: JsonObject, path: String) {
        val minimum = schema["minItems"]?.jsonPrimitive?.intOrNull
        val maximum = schema["maxItems"]?.jsonPrimitive?.intOrNull
        if (minimum != null && value.size < minimum) invalid("$path must contain at least $minimum items")
        if (maximum != null && value.size > maximum) invalid("$path must contain at most $maximum items")
        (schema["items"] as? JsonObject)?.let { itemSchema ->
            value.forEachIndexed { index, child -> validate(child, itemSchema, "$path[$index]") }
        }
    }

    private fun validatePrimitive(value: JsonPrimitive, schema: JsonObject, path: String) {
        if (value.isString) {
            val minimum = schema["minLength"]?.jsonPrimitive?.intOrNull
            val maximum = schema["maxLength"]?.jsonPrimitive?.intOrNull
            if (minimum != null && value.content.length < minimum) invalid("$path is too short")
            if (maximum != null && value.content.length > maximum) invalid("$path is too long")
        }
        val number = value.longOrNull ?: return
        schema["minimum"]?.jsonPrimitive?.longOrNull?.let { minimum -> if (number < minimum) invalid("$path is below minimum") }
        schema["maximum"]?.jsonPrimitive?.longOrNull?.let { maximum -> if (number > maximum) invalid("$path exceeds maximum") }
    }

    private fun invalid(message: String): Nothing = throw McpAutomationException("invalid_argument", message)
}

private fun emptySchema(): JsonObject = objectSchema(emptyMap())

private fun pageSchema(): JsonObject = objectSchema(
    mapOf(
        "cursor" to stringSchema(),
        "limit" to integerSchema(minimum = 1, maximum = 200),
    ),
)

private fun scopeSchemaProperties(): Map<String, JsonObject> = mapOf(
    "protocol" to enumSchema(FileEndpointRef.SUPPORTED_PROTOCOLS.map { item -> item.name }),
    "sourceId" to stringSchema(),
)

private fun scopePageSchema(): JsonObject = objectSchema(scopeSchemaProperties() + pageProperties(), setOf("protocol"))

private fun bookmarkMutationSchema(includeId: Boolean): JsonObject = objectSchema(
    scopeSchemaProperties() + buildMap {
        if (includeId) put("id", integerSchema(minimum = 1))
        put("name", stringSchema(minLength = 1, maxLength = 256))
        put("bookmarkType", enumSchema(listOf("home", "image", "audio", "video", "document", "download", "custom")))
        put("path", stringSchema(minLength = 1))
        put("icon", stringSchema(maxLength = 2048))
        put("sort", integerSchema(minimum = 0))
    },
    scopeSchemaProperties().keys + setOf("name", "bookmarkType", "path") + if (includeId) setOf("id") else emptySet(),
)

private fun scopeIdSchema(): JsonObject = objectSchema(scopeSchemaProperties() + ("id" to integerSchema(minimum = 1)), scopeSchemaProperties().keys + "id")

private fun idSchema(): JsonObject = objectSchema(mapOf("id" to integerSchema(minimum = 1)), setOf("id"))

private fun stringIdSchema(): JsonObject = objectSchema(mapOf("id" to stringSchema(minLength = 1)), setOf("id"))

private fun idBooleanSchema(name: String): JsonObject = objectSchema(
    mapOf("id" to integerSchema(minimum = 1), name to booleanSchema()),
    setOf("id", name),
)

private fun idsSchema(): JsonObject = objectSchema(
    mapOf("ids" to arraySchema(integerSchema(minimum = 1), minItems = 1, maxItems = 200)),
    setOf("ids"),
)

private fun locatorObjectSchema(): JsonObject = objectSchema(
    mapOf(
        "protocol" to enumSchema(FileEndpointRef.SUPPORTED_PROTOCOLS.map { item -> item.name }),
        "sourceId" to stringSchema(),
        "path" to stringSchema(minLength = 1),
    ),
    setOf("protocol", "path"),
)

private fun locatorSchema(): JsonObject = objectSchema(mapOf("locator" to locatorObjectSchema()), setOf("locator"))

private fun locatorPageSchema(): JsonObject = objectSchema(mapOf("locator" to locatorObjectSchema()) + pageProperties(), setOf("locator"))

private fun fileReadSchema(): JsonObject = objectSchema(
    mapOf(
        "locator" to locatorObjectSchema(),
        "offset" to integerSchema(minimum = 0),
        "length" to integerSchema(minimum = 1, maximum = 4 * 1024 * 1024L),
        "encoding" to enumSchema(listOf("utf8", "base64")),
    ),
    setOf("locator"),
)

private fun fileWriteSchema(): JsonObject = objectSchema(
    mapOf(
        "locator" to locatorObjectSchema(),
        "data" to stringSchema(maxLength = ((MAX_FILE_WRITE_BYTES + 2) / 3) * 4),
        "encoding" to enumSchema(listOf("utf8", "base64")),
        "mode" to enumSchema(listOf("overwrite", "append")),
        "expectedSize" to integerSchema(minimum = 0),
        "expectedUpdatedAt" to integerSchema(minimum = 0),
    ),
    setOf("locator", "data"),
)

private fun renameSchema(): JsonObject = objectSchema(
    mapOf("locator" to locatorObjectSchema(), "newName" to stringSchema(minLength = 1, maxLength = 1024)),
    setOf("locator", "newName"),
)

private fun locatorsSchema(): JsonObject = objectSchema(
    mapOf("sources" to arraySchema(locatorObjectSchema(), minItems = 1, maxItems = 200)),
    setOf("sources"),
)

private fun transferSchema(): JsonObject = objectSchema(
    mapOf(
        "sources" to arraySchema(locatorObjectSchema(), minItems = 1, maxItems = 200),
        "target" to locatorObjectSchema(),
        "conflictPolicy" to enumSchema(listOf("error", "skip", "overwrite", "rename")),
    ),
    setOf("sources", "target"),
)

private fun shareLinkSchema(): JsonObject = objectSchema(
    mapOf(
        "locators" to arraySchema(locatorObjectSchema(), minItems = 1, maxItems = 200),
        "allowHidden" to booleanSchema(),
        "allowUpload" to booleanSchema(),
    ),
    setOf("locators"),
)

private fun shareDeviceSchema(): JsonObject = objectSchema(
    mapOf(
        "locators" to arraySchema(locatorObjectSchema(), minItems = 1, maxItems = 200),
        "deviceId" to stringSchema(minLength = 1, maxLength = 256),
        "allowHidden" to booleanSchema(),
    ),
    setOf("locators", "deviceId"),
)

private fun pageProperties(): Map<String, JsonObject> = mapOf(
    "cursor" to stringSchema(),
    "limit" to integerSchema(minimum = 1, maximum = 200),
)

private fun objectSchema(properties: Map<String, JsonObject>, required: Set<String> = emptySet()): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(properties))
    if (required.isNotEmpty()) put("required", JsonArray(required.sorted().map(::JsonPrimitive)))
    put("additionalProperties", false)
}

private fun stringSchema(minLength: Int? = null, maxLength: Int? = null): JsonObject = buildJsonObject {
    put("type", "string")
    minLength?.let { value -> put("minLength", value) }
    maxLength?.let { value -> put("maxLength", value) }
}

private fun integerSchema(minimum: Long? = null, maximum: Long? = null): JsonObject = buildJsonObject {
    put("type", "integer")
    minimum?.let { value -> put("minimum", value) }
    maximum?.let { value -> put("maximum", value) }
}

private fun booleanSchema(): JsonObject = buildJsonObject { put("type", "boolean") }

private fun enumSchema(values: List<String>): JsonObject = buildJsonObject {
    put("type", "string")
    put("enum", JsonArray(values.map(::JsonPrimitive)))
}

private fun arraySchema(items: JsonObject, minItems: Int? = null, maxItems: Int? = null): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", items)
    minItems?.let { value -> put("minItems", value) }
    maxItems?.let { value -> put("maxItems", value) }
}

private fun JsonObject.protocol(): FileProtocol {
    val value = requiredString("protocol")
    return FileEndpointRef.SUPPORTED_PROTOCOLS.firstOrNull { item -> item.name.equals(value, ignoreCase = true) }
        ?: throw McpAutomationException("invalid_argument", "protocol is invalid")
}

private fun JsonObject.sourceId(): String = string("sourceId").orEmpty()

private fun JsonObject.locator(name: String = "locator"): FileLocator {
    val value = this[name] as? JsonObject ?: throw McpAutomationException("invalid_argument", "$name is required")
    val protocol = value.protocol()
    return try {
        FileLocator(FileEndpointRef(protocol, value.sourceId()), value.requiredString("path"))
    } catch (error: IllegalArgumentException) {
        throw McpAutomationException("invalid_argument", error.message ?: "locator is invalid")
    }
}

private fun JsonObject.locators(name: String): List<FileLocator> =
    (this[name] as? JsonArray)?.mapIndexed { index, item ->
        val objectValue = item as? JsonObject
            ?: throw McpAutomationException("invalid_argument", "$name[$index] must be an object")
        buildJsonObject { put("locator", objectValue) }.locator()
    } ?: throw McpAutomationException("invalid_argument", "$name is required")

private fun JsonObject.conflictPolicy(): FileConflictPolicy = when (string("conflictPolicy") ?: "error") {
    "error" -> FileConflictPolicy.Error
    "skip" -> FileConflictPolicy.Skip
    "overwrite" -> FileConflictPolicy.Overwrite
    "rename" -> FileConflictPolicy.Rename
    else -> throw McpAutomationException("invalid_argument", "conflictPolicy is invalid")
}

private fun parseContentEncoding(value: String): FileContentEncoding = when (value) {
    "utf8" -> FileContentEncoding.Utf8
    "base64" -> FileContentEncoding.Base64
    else -> throw McpAutomationException("invalid_argument", "encoding is invalid")
}

private fun parseWriteMode(value: String): FileWriteMode = when (value) {
    "overwrite" -> FileWriteMode.Overwrite
    "append" -> FileWriteMode.Append
    else -> throw McpAutomationException("invalid_argument", "mode is invalid")
}

private fun JsonObject.requiredString(name: String): String = string(name)?.takeIf(String::isNotBlank)
    ?: throw McpAutomationException("invalid_argument", "$name is required")

private fun JsonObject.requiredStringAllowingEmpty(name: String): String = string(name)
    ?: throw McpAutomationException("invalid_argument", "$name is required")

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.requiredLong(name: String): Long = long(name)
    ?: throw McpAutomationException("invalid_argument", "$name is required")

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

private fun JsonObject.requiredBoolean(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull
    ?: throw McpAutomationException("invalid_argument", "$name is required")

private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.longArray(name: String): List<Long> = (this[name] as? JsonArray)
    ?.map { item -> item.jsonPrimitive.longOrNull ?: throw McpAutomationException("invalid_argument", "$name must contain integers") }
    ?: throw McpAutomationException("invalid_argument", "$name is required")

private inline fun <reified T> json(value: T): JsonElement = TOOL_JSON.encodeToJsonElement(value)

private fun jsonObject(name: String, value: Boolean): JsonObject = buildJsonObject { put(name, value) }
private fun jsonObject(name: String, value: Int): JsonObject = buildJsonObject { put(name, value) }
private fun jsonObject(name: String, value: Long): JsonObject = buildJsonObject { put(name, value) }

private val TOOL_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
}
