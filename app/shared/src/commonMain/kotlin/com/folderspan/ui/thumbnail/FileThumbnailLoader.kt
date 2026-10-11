package com.folderspan.ui.thumbnail

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import com.folderspan.PlatformType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.DeviceType
import com.folderspan.editor.FileEditorContentSource
import com.folderspan.ui.state.file.FileState
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random
import kotlin.time.Clock
import strings.AppStrings

@Stable
interface FileThumbnailLoader {
    suspend fun load(file: FileSimpleInfo, targetSizePx: Int): ImageBitmap?

    fun close()
}

internal class DefaultFileThumbnailLoader(
    private val openContent: suspend (FileSimpleInfo) -> Result<FileEditorContentSource>,
    private val decoder: suspend (FileThumbnailDecodeSource, Int) -> ImageBitmap,
    private val remoteDebounceMillis: Long,
) : FileThumbnailLoader {
    constructor(fileState: FileState) : this(
        openContent = { file -> fileState.openEditorContent(file, canWrite = false) },
        decoder = ::decodeFileThumbnail,
        remoteDebounceMillis = THUMBNAIL_REMOTE_DEBOUNCE_MILLIS,
    )

    private data class InFlight(
        val deferred: Deferred<ImageBitmap?>,
        var subscribers: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateMutex = Mutex()
    private val decodeSemaphore = Semaphore(2)
    private val remoteReadSemaphore = Semaphore(if (PlatformType == DeviceType.JS) 1 else 2)
    private val cache = ByteSizeLruCache<FileThumbnailKey, ImageBitmap>(
        maxBytes = thumbnailBitmapCacheMaxBytes(),
        sizeOf = { image -> image.width.toLong() * image.height.toLong() * 4L },
    )
    private val inFlight = mutableMapOf<FileThumbnailKey, InFlight>()

    override suspend fun load(file: FileSimpleInfo, targetSizePx: Int): ImageBitmap? {
        if (!file.isThumbnailEligible()) return null
        val key = file.thumbnailKey(targetSizePx)

        val flight = stateMutex.withLock {
            cache.get(key)?.let { return it }
            inFlight[key]?.also { existing ->
                existing.subscribers += 1
            } ?: InFlight(
                deferred = scope.async { loadUncached(file, key) },
                subscribers = 1,
            ).also { created -> inFlight[key] = created }
        }

        return try {
            flight.deferred.await()
        } finally {
            stateMutex.withLock {
                val current = inFlight[key]
                if (current === flight) {
                    current.subscribers -= 1
                    if (current.subscribers <= 0) {
                        inFlight.remove(key)
                        if (current.deferred.isActive) current.deferred.cancel()
                    }
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    private suspend fun loadUncached(
        file: FileSimpleInfo,
        key: FileThumbnailKey,
    ): ImageBitmap? {
        return try {
            if (file.protocol != FileProtocol.Local && remoteDebounceMillis > 0L) {
                delay(remoteDebounceMillis)
            }
            val image = if (file.protocol == FileProtocol.Local) {
                prepareAndDecode(file, key.targetSizePx)
            } else {
                remoteReadSemaphore.withPermit { prepareAndDecode(file, key.targetSizePx) }
            }
            if (image != null) {
                stateMutex.withLock { cache.put(key, image) }
            }
            image
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun prepareAndDecode(
        file: FileSimpleInfo,
        targetSizePx: Int,
    ): ImageBitmap? {
        val content = openContent(file).getOrNull() ?: return null
        var stagedPath: String? = null
        return try {
            val localPath = content.localPath?.takeUnless { PlatformType == DeviceType.JS }
            val source = when {
                localPath != null -> FileThumbnailDecodeSource(localPath = localPath)
                PlatformType != DeviceType.JS -> {
                    stagedPath = content.stageToTemporaryFile()
                    FileThumbnailDecodeSource(localPath = stagedPath)
                }
                else -> FileThumbnailDecodeSource(bytes = content.readAllBytes())
            }
            decodeSemaphore.withPermit {
                decoder(source, targetSizePx)
            }
        } finally {
            stagedPath?.let { path -> FileUtils.deleteFile(FileAccessPermission.Allowed, path) }
            content.close()
        }
    }
}

private suspend fun FileEditorContentSource.stageToTemporaryFile(): String {
    require(size in 1..REMOTE_THUMBNAIL_MAX_BYTES) { AppStrings.ui_image_source_file_too_large }
    val separator = PathUtils.getPathSeparator()
    val cacheDirectory = PathUtils.getCachePath().trimEnd('/', '\\') + separator + "file-thumbnails"
    check(FileUtils.createFolder(FileAccessPermission.Allowed, cacheDirectory).getOrThrow()) {
        AppStrings.ui_unable_to_create_a_cache_directory_for_thumbnails
    }
    val path = cacheDirectory + separator +
        "thumbnail-${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt()}.tmp"
    check(FileUtils.createFile(FileAccessPermission.Allowed, path).getOrThrow()) {
        AppStrings.ui_temporary_file_cannot_be_created
    }
    try {
        var offset = 0L
        while (offset < size) {
            currentCoroutineContext().ensureActive()
            val end = minOf(size, offset + THUMBNAIL_READ_CHUNK_BYTES)
            val chunk = readRange(offset, end).getOrThrow()
            check(
                FileUtils.writeBytes(
                    permission = FileAccessPermission.Allowed,
                    path = path,
                    fileSize = size,
                    data = chunk,
                    offset = offset,
                ).getOrThrow()
            ) { AppStrings.ui_failed_to_write_temporary_thumbnail_file }
            offset = end
        }
        return path
    } catch (error: Throwable) {
        FileUtils.deleteFile(FileAccessPermission.Allowed, path)
        throw error
    }
}

private suspend fun FileEditorContentSource.readAllBytes(): ByteArray {
    require(size in 1..REMOTE_THUMBNAIL_MAX_BYTES) { AppStrings.ui_image_source_file_too_large }
    val result = ByteArray(size.toInt())
    var offset = 0L
    while (offset < size) {
        currentCoroutineContext().ensureActive()
        val end = minOf(size, offset + THUMBNAIL_READ_CHUNK_BYTES)
        val chunk = readRange(offset, end).getOrThrow()
        chunk.copyInto(result, destinationOffset = offset.toInt())
        offset = end
    }
    return result
}
