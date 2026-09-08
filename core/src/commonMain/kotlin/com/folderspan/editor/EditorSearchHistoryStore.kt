package com.folderspan.editor

import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.SyncSnapshotChangeNotifier
import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

const val EDITOR_SEARCH_HISTORY_LIMIT: Int = 50

@Serializable
data class EditorSearchHistoryEntry(
    val mode: EditorSearchMode,
    val queries: List<String>,
    val encoding: EditorTextEncoding,
    val caseSensitive: Boolean,
    val wholeWord: Boolean,
    val direction: EditorSearchDirection,
    val updatedAt: Long,
)

class EditorSearchHistoryStore(
    private val settings: Settings,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun list(): List<EditorSearchHistoryEntry> = decode(
        settings.getString(SettingsUtils.KEY_EDITOR_SEARCH_HISTORY, "[]"),
    )

    fun record(request: EditorSearchRequest): List<EditorSearchHistoryEntry> {
        val normalizedQueries = request.queries.filter(String::isNotEmpty)
        if (normalizedQueries.isEmpty()) return list()
        val current = list()
        val recordedAt = when (val currentMaximumUpdatedAt = current.maxOfOrNull(EditorSearchHistoryEntry::updatedAt)) {
            null -> now()
            Long.MAX_VALUE -> Long.MAX_VALUE
            else -> maxOf(now(), currentMaximumUpdatedAt + 1L)
        }
        val incoming = EditorSearchHistoryEntry(
            mode = request.mode,
            queries = normalizedQueries,
            encoding = request.encoding,
            caseSensitive = request.caseSensitive,
            wholeWord = request.wholeWord,
            direction = request.direction,
            updatedAt = recordedAt,
        )
        val next = normalize(listOf(incoming) + current)
        persist(next, notify = true)
        return next
    }

    fun remove(entry: EditorSearchHistoryEntry): List<EditorSearchHistoryEntry> {
        val current = list()
        val identity = entry.identity()
        val next = current.filterNot { it.identity() == identity }
        if (next != current) persist(next, notify = true)
        return next
    }

    fun clear(): List<EditorSearchHistoryEntry> {
        val current = list()
        if (current.isNotEmpty()) persist(emptyList(), notify = true)
        return emptyList()
    }

    fun replace(entries: List<EditorSearchHistoryEntry>, notify: Boolean = false): List<EditorSearchHistoryEntry> {
        val next = normalize(entries)
        persist(next, notify)
        return next
    }

    fun encodeSnapshot(): String = json.encodeToString(
        ListSerializer(EditorSearchHistoryEntry.serializer()),
        list(),
    )

    fun applySnapshot(serialized: String): List<EditorSearchHistoryEntry> = replace(decode(serialized))

    private fun decode(serialized: String): List<EditorSearchHistoryEntry> = runCatching {
        json.decodeFromString(ListSerializer(EditorSearchHistoryEntry.serializer()), serialized)
    }.getOrDefault(emptyList()).let(::normalize)

    private fun normalize(entries: List<EditorSearchHistoryEntry>): List<EditorSearchHistoryEntry> {
        val seen = mutableSetOf<EditorSearchHistoryIdentity>()
        return entries
            .asSequence()
            .filter { entry -> entry.queries.isNotEmpty() && entry.queries.all(String::isNotEmpty) }
            .sortedByDescending(EditorSearchHistoryEntry::updatedAt)
            .filter { entry -> seen.add(entry.identity()) }
            .take(EDITOR_SEARCH_HISTORY_LIMIT)
            .toList()
    }

    private fun persist(entries: List<EditorSearchHistoryEntry>, notify: Boolean) {
        settings.putString(
            SettingsUtils.KEY_EDITOR_SEARCH_HISTORY,
            json.encodeToString(ListSerializer(EditorSearchHistoryEntry.serializer()), entries),
        )
        if (notify) SyncSnapshotChangeNotifier.onEditorSearchHistoryChanged()
    }
}

private data class EditorSearchHistoryIdentity(
    val queries: List<String>,
)

private fun EditorSearchHistoryEntry.identity() = EditorSearchHistoryIdentity(
    queries = queries,
)
