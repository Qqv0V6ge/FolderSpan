package com.folderspan.editor

import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import strings.AppStrings

private const val EDITOR_LINE_INDEX_CACHE_DIRECTORY = "file-editor-line-index"
private const val EDITOR_LINE_INDEX_CACHE_EXTENSION = ".fsli"
private const val DEFAULT_LINE_INDEX_CACHE_ENTRY_LIMIT = 32

interface EditorLineIndexCacheStore {
    suspend fun read(key: String): ByteArray?
    suspend fun write(key: String, bytes: ByteArray)
    suspend fun remove(key: String)
    suspend fun cleanup(maxEntries: Int = DEFAULT_LINE_INDEX_CACHE_ENTRY_LIMIT)
}

class ApplicationPrivateEditorLineIndexCache : EditorLineIndexCacheStore {
    override suspend fun read(key: String): ByteArray? = withContext(Dispatchers.Default) {
        val path = cachePath(key)
        if (PathUtils.exists(FileAccessPermission.Allowed, path)) FileUtils.readFile(FileAccessPermission.Allowed, path).getOrNull() else null
    }

    override suspend fun write(key: String, bytes: ByteArray) = withContext(Dispatchers.Default) {
        val path = cachePath(key)
        if (PathUtils.exists(FileAccessPermission.Allowed, path)) FileUtils.deleteFile(FileAccessPermission.Allowed, path).getOrThrow()
        check(FileUtils.createFile(FileAccessPermission.Allowed, path).getOrThrow()) { AppStrings.ui_cannot_create_a_line_index_cache }
        check(FileUtils.writeBytes(FileAccessPermission.Allowed, path, bytes.size.toLong(), bytes, 0L).getOrThrow()) {
            AppStrings.ui_cannot_write_to_the_index_cache
        }
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.Default) {
        val path = cachePath(key)
        if (PathUtils.exists(FileAccessPermission.Allowed, path)) FileUtils.deleteFile(FileAccessPermission.Allowed, path)
        Unit
    }

    override suspend fun cleanup(maxEntries: Int) = withContext(Dispatchers.Default) {
        require(maxEntries >= 0)
        val directory = cacheDirectory()
        val entries = PathUtils.getFileAndFolder(FileAccessPermission.Allowed, directory).getOrDefault(emptyList())
            .filter { !it.isDirectory && it.name.endsWith(EDITOR_LINE_INDEX_CACHE_EXTENSION) }
            .sortedByDescending { it.updatedDate }
        entries.drop(maxEntries).forEach { entry ->
            FileUtils.deleteFile(FileAccessPermission.Allowed, entry.path)
        }
    }

    private fun cachePath(key: String): String = joinPath(
        cacheDirectory(),
        key.filter { it.isLetterOrDigit() || it == '_' || it == '-' } +
            EDITOR_LINE_INDEX_CACHE_EXTENSION,
    )

    private fun cacheDirectory(): String {
        val directory = joinPath(PathUtils.getCachePath(), EDITOR_LINE_INDEX_CACHE_DIRECTORY)
        PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, directory)
        return directory
    }

    private fun joinPath(parent: String, child: String): String {
        val separator = PathUtils.getPathSeparator()
        return if (parent.endsWith(separator)) parent + child else parent + separator + child
    }
}

enum class EditorLineIndexBuildStatus {
    Idle,
    Indexing,
    Complete,
    Cancelled,
    Failed,
}

data class EditorLineIndexBuildState(
    val status: EditorLineIndexBuildStatus = EditorLineIndexBuildStatus.Idle,
    val progress: Float = 0f,
    val index: EditorSparseLineIndex? = null,
    val error: String? = null,
    val loadedFromCache: Boolean = false,
)

class EditorBackgroundLineIndexer(
    private val source: FileEditorContentSource,
    private val encoding: EditorTextEncoding,
    private val hasBom: Boolean,
    private val cache: EditorLineIndexCacheStore,
    private val taskCoordinator: FileEditorTaskCoordinator,
    private val onStateChange: (EditorLineIndexBuildState) -> Unit = {},
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(EditorLineIndexBuildState())
    val state: StateFlow<EditorLineIndexBuildState> = _state.asStateFlow()
    private var completion: CompletableDeferred<Result<EditorSparseLineIndex>>? = null
    private var job: Job? = null

    suspend fun start(): Job = mutex.withLock {
        job?.takeIf(Job::isActive)?.let { return@withLock it }
        val result = CompletableDeferred<Result<EditorSparseLineIndex>>()
        completion = result
        taskCoordinator.launch(FileEditorTaskKind.LineIndex) {
            publish(EditorLineIndexBuildState(EditorLineIndexBuildStatus.Indexing))
            try {
                val snapshot = source.currentSnapshot().getOrThrow()
                val key = editorLineIndexCacheKey(snapshot, encoding)
                val cached = cache.read(key)?.let(EditorSparseLineIndex::decode)
                if (
                    cached != null &&
                    cached.complete &&
                    cached.sourceSnapshot == snapshot &&
                    cached.encoding == encoding
                ) {
                    publish(EditorLineIndexBuildState(
                        status = EditorLineIndexBuildStatus.Complete,
                        progress = 1f,
                        index = cached,
                        loadedFromCache = true,
                    ))
                    result.complete(Result.success(cached))
                    return@launch
                }
                if (cached != null) cache.remove(key)
                val built = EditorSparseLineIndexer(source, encoding, hasBom).build { completed, total ->
                    publish(EditorLineIndexBuildState(
                        status = EditorLineIndexBuildStatus.Indexing,
                        progress = if (total <= 0L) 1f else
                            (completed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat(),
                    ))
                }.getOrThrow()
                cache.write(key, built.encode())
                cache.cleanup()
                publish(EditorLineIndexBuildState(
                    status = EditorLineIndexBuildStatus.Complete,
                    progress = 1f,
                    index = built,
                ))
                result.complete(Result.success(built))
            } catch (cancelled: CancellationException) {
                publish(EditorLineIndexBuildState(
                    status = EditorLineIndexBuildStatus.Cancelled,
                    progress = _state.value.progress,
                    error = cancelled.message,
                ))
                result.complete(Result.failure(cancelled))
            } catch (error: Throwable) {
                publish(EditorLineIndexBuildState(
                    status = EditorLineIndexBuildStatus.Failed,
                    progress = _state.value.progress,
                    error = error.message,
                ))
                result.complete(Result.failure(error))
            }
        }.also { job = it }
    }

    suspend fun await(): Result<EditorSparseLineIndex> {
        val existing = mutex.withLock { completion }
        if (existing != null) return existing.await()
        start()
        return mutex.withLock { completion!! }.await()
    }

    suspend fun cancel() {
        taskCoordinator.cancel(FileEditorTaskKind.LineIndex)
    }

    private fun publish(value: EditorLineIndexBuildState) {
        _state.value = value
        onStateChange(value)
    }
}
