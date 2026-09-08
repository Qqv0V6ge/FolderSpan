package com.folderspan.service.mcp.auth

import com.folderspan.db.FolderSpanDatabase
import com.folderspan.db.McpToken
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.executeAsOneOrNullAwait
import korlibs.crypto.SecureRandom
import korlibs.crypto.sha256
import kotlin.time.Clock

private const val TOKEN_PREFIX = "fmcp"
private const val LOOKUP_ID_BYTES = 8
private const val TOKEN_SECRET_BYTES = 32

enum class McpTokenScope(val value: String) {
    BookmarksRead("bookmarks.read"),
    BookmarksWrite("bookmarks.write"),
    FavoritesRead("favorites.read"),
    FavoritesWrite("favorites.write"),
    RecentsRead("recents.read"),
    RecentsWrite("recents.write"),
    TasksRead("tasks.read"),
    TasksControl("tasks.control"),
    DevicesRead("devices.read"),
    DevicesScan("devices.scan"),
    DevicesConnect("devices.connect"),
    NetworksRead("networks.read"),
    NetworksConnect("networks.connect"),
    SyncRead("sync.read"),
    SyncRun("sync.run"),
    FilesRead("files.read"),
    FilesWrite("files.write"),
    FilesShare("files.share");

    companion object {
        fun fromValue(value: String): McpTokenScope? = entries.firstOrNull { scope -> scope.value == value }
    }
}

data class McpTokenRecord(
    val lookupId: String,
    val name: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long?,
    val scopes: Set<McpTokenScope>,
)

/** Full token is deliberately returned only from create/rotate operations. */
data class McpOneTimeTokenSecret(
    val token: String,
    val record: McpTokenRecord,
)

data class McpTokenPrincipal(
    val lookupId: String,
    val name: String,
    val scopes: Set<McpTokenScope>,
)

class ParsedMcpToken internal constructor(
    val lookupId: String,
    internal val secretBytes: ByteArray,
)

fun parseMcpToken(token: String): ParsedMcpToken? {
    val parts = token.split('_')
    if (parts.size != 3 || parts[0] != TOKEN_PREFIX) return null
    if (parts[1].length != LOOKUP_ID_BYTES * 2 || parts[2].length != TOKEN_SECRET_BYTES * 2) return null
    val lookupId = parts[1].lowercase()
    val secret = parts[2].hexToByteArrayOrNull() ?: return null
    if (lookupId.hexToByteArrayOrNull()?.size != LOOKUP_ID_BYTES) return null
    return ParsedMcpToken(lookupId = lookupId, secretBytes = secret)
}

internal fun hashMcpTokenSecret(secret: ByteArray): String = secret.sha256().hexLower

internal fun constantTimeTokenHashEquals(expected: String, actual: String): Boolean {
    val expectedBytes = expected.encodeToByteArray()
    val actualBytes = actual.encodeToByteArray()
    var difference = expectedBytes.size xor actualBytes.size
    val length = maxOf(expectedBytes.size, actualBytes.size)
    for (index in 0 until length) {
        val expectedByte = expectedBytes.getOrElse(index) { 0 }
        val actualByte = actualBytes.getOrElse(index) { 0 }
        difference = difference or (expectedByte.toInt() xor actualByte.toInt())
    }
    return difference == 0
}

class McpTokenRepository(
    private val database: FolderSpanDatabase,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val randomBytes: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom::nextBytes) },
) {
    suspend fun create(name: String, scopes: Set<McpTokenScope>): McpOneTimeTokenSecret {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Token name must not be blank" }
        require(scopes.isNotEmpty()) { "At least one scope is required" }
        val generated = generateUniqueToken()
        val now = nowMillis()
        database.mcpTokenQueries.insert(
            lookupId = generated.lookupId,
            secretHash = hashMcpTokenSecret(generated.secretBytes),
            name = normalizedName,
            enabled = true,
            createdAt = now,
            updatedAt = now,
            lastUsedAt = null,
        ).awaitDatabaseReady()
        replaceScopes(generated.lookupId, scopes)
        return McpOneTimeTokenSecret(
            token = generated.fullToken,
            record = requireNotNull(get(generated.lookupId)),
        )
    }

    suspend fun rotate(lookupId: String): McpOneTimeTokenSecret? {
        val current = get(lookupId) ?: return null
        val secret = randomBytes(TOKEN_SECRET_BYTES).requireSize(TOKEN_SECRET_BYTES)
        val now = nowMillis()
        database.mcpTokenQueries.updateSecret(
            secretHash = hashMcpTokenSecret(secret),
            updatedAt = now,
            lookupId = lookupId,
        ).awaitDatabaseReady()
        return McpOneTimeTokenSecret(
            token = "${TOKEN_PREFIX}_${lookupId}_${secret.hexLowercase()}",
            record = current.copy(updatedAt = now, lastUsedAt = null),
        )
    }

    suspend fun authenticate(token: String): McpTokenPrincipal? {
        val parsed = parseMcpToken(token) ?: return null
        val stored = database.mcpTokenQueries.selectByLookupId(parsed.lookupId).executeAsOneOrNullAwait()
            ?: return null
        if (!stored.enabled) return null
        val actualHash = hashMcpTokenSecret(parsed.secretBytes)
        if (!constantTimeTokenHashEquals(stored.secretHash, actualHash)) return null
        val scopes = loadScopes(parsed.lookupId)
        database.mcpTokenQueries.updateLastUsed(nowMillis(), parsed.lookupId).awaitDatabaseReady()
        return McpTokenPrincipal(parsed.lookupId, stored.name, scopes)
    }

    suspend fun list(): List<McpTokenRecord> =
        database.mcpTokenQueries.selectAll().executeAsListAwait().map { token -> token.toRecord(loadScopes(token.lookupId)) }

    suspend fun get(lookupId: String): McpTokenRecord? =
        database.mcpTokenQueries.selectByLookupId(lookupId).executeAsOneOrNullAwait()
            ?.let { token -> token.toRecord(loadScopes(token.lookupId)) }

    suspend fun rename(lookupId: String, name: String): Boolean {
        if (get(lookupId) == null) return false
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Token name must not be blank" }
        database.mcpTokenQueries.updateName(normalizedName, nowMillis(), lookupId).awaitDatabaseReady()
        return true
    }

    suspend fun setEnabled(lookupId: String, enabled: Boolean): Boolean {
        if (get(lookupId) == null) return false
        database.mcpTokenQueries.updateEnabled(enabled, nowMillis(), lookupId).awaitDatabaseReady()
        return true
    }

    suspend fun setScopes(lookupId: String, scopes: Set<McpTokenScope>): Boolean {
        require(scopes.isNotEmpty()) { "At least one scope is required" }
        if (get(lookupId) == null) return false
        replaceScopes(lookupId, scopes)
        return true
    }

    suspend fun delete(lookupId: String): Boolean {
        if (get(lookupId) == null) return false
        database.mcpTokenScopeQueries.deleteByToken(lookupId).awaitDatabaseReady()
        database.mcpTokenQueries.deleteByLookupId(lookupId).awaitDatabaseReady()
        return true
    }

    private suspend fun replaceScopes(lookupId: String, scopes: Set<McpTokenScope>) {
        database.mcpTokenScopeQueries.deleteByToken(lookupId).awaitDatabaseReady()
        scopes.sortedBy { scope -> scope.value }.forEach { scope ->
            database.mcpTokenScopeQueries.insert(lookupId, scope.value).awaitDatabaseReady()
        }
    }

    private suspend fun loadScopes(lookupId: String): Set<McpTokenScope> =
        database.mcpTokenScopeQueries.selectByToken(lookupId).executeAsListAwait()
            .mapNotNull(McpTokenScope::fromValue)
            .toSet()

    private suspend fun generateUniqueToken(): GeneratedToken {
        repeat(8) {
            val lookupBytes = randomBytes(LOOKUP_ID_BYTES).requireSize(LOOKUP_ID_BYTES)
            val lookupId = lookupBytes.hexLowercase()
            if (database.mcpTokenQueries.selectByLookupId(lookupId).executeAsOneOrNullAwait() == null) {
                val secret = randomBytes(TOKEN_SECRET_BYTES).requireSize(TOKEN_SECRET_BYTES)
                return GeneratedToken(
                    lookupId = lookupId,
                    secretBytes = secret,
                    fullToken = "${TOKEN_PREFIX}_${lookupId}_${secret.hexLowercase()}",
                )
            }
        }
        error("Unable to allocate a unique token lookup ID")
    }

    private data class GeneratedToken(
        val lookupId: String,
        val secretBytes: ByteArray,
        val fullToken: String,
    )
}

private fun McpToken.toRecord(scopes: Set<McpTokenScope>): McpTokenRecord = McpTokenRecord(
    lookupId = lookupId,
    name = name,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
    scopes = scopes,
)

private fun ByteArray.requireSize(expected: Int): ByteArray {
    require(size == expected) { "Random source returned $size bytes, expected $expected" }
    return this
}

private fun ByteArray.hexLowercase(): String = joinToString("") { byte ->
    byte.toUByte().toString(16).padStart(2, '0')
}

private fun String.hexToByteArrayOrNull(): ByteArray? {
    if (length % 2 != 0 || any { char -> char.digitToIntOrNull(16) == null }) return null
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
