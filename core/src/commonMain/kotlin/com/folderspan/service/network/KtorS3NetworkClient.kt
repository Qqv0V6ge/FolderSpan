package com.folderspan.service.network

import com.folderspan.data.main.network.Network
import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.service.http.client.createNoProxyHttpClient
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant
import strings.AppStrings

private const val S3_LIST_DELIMITER = "/"
private const val S3_STREAM_BUFFER_SIZE = 64 * 1024
private const val S3_SINGLE_UPLOAD_THRESHOLD = 16L * 1024L * 1024L
private const val S3_DEFAULT_MULTIPART_SIZE = 8L * 1024L * 1024L
private const val S3_MIN_MULTIPART_SIZE = 5L * 1024L * 1024L
private const val S3_MAX_MULTIPART_PARTS = 10_000L
private const val S3_MULTIPART_STATE_VERSION = "S3-MPU-V1"
private const val S3_EMPTY_PAYLOAD_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
private val S3_ERROR_RESPONSE_REGEX = "(?is)<\\s*Error\\b".toRegex()

internal class KtorS3NetworkClient(
    private val network: Network,
    overrideClient: HttpClient? = null,
) : NetworkClient, ChunkReadableNetworkClient {
    private val extras = network.extras.s3
    private val bucketName = extras.bucket.trim()
    private val region = extras.region.trim()
    private val endpointInput: String? = extras.endpoint.trim().ifBlank { network.host.trim() }.ifBlank { null }
    private val forcePathStyle = extras.forcePathStyle
    private val accessKeyId = network.username.trim()
    private val secretAccessKey = network.password
    private val sessionToken = extras.sessionToken.trim().ifBlank { null }

    private val client: HttpClient = overrideClient ?: createNoProxyHttpClient {
        expectSuccess = false
    }
    private val endpointConfig = resolveEndpointConfig()

    override suspend fun list(
        path: String,
        requestId: String?,
        batchId: String?
    ): Result<List<NetworkFileEntry>> {
        val normalizedPath = normalizeRemotePath(path)
        val prefix = listPrefixFromPath(normalizedPath)
        val currentComparablePath = comparablePath(normalizedPath)

        return runWithConfig(operation = AppStrings.ui_list, detail = normalizedPath) {
            val entriesByPath = LinkedHashMap<String, NetworkFileEntry>()
            var continuationToken: String? = null
            do {
                val query = linkedMapOf(
                    "list-type" to "2",
                    "delimiter" to S3_LIST_DELIMITER
                )
                if (prefix.isNotEmpty()) {
                    query["prefix"] = prefix
                }
                if (!continuationToken.isNullOrBlank()) {
                    query["continuation-token"] = continuationToken
                }

                val response = signedRequest(
                    method = HttpMethod.Get,
                    key = null,
                    query = query
                )
                if (!response.status.isSuccess()) {
                    return@runWithConfig failureWithResponse(AppStrings.ui_failed_to_retrieve_s3_list, response)
                }
                val page = parseListObjectsPage(response.bodyAsText())

                page.commonPrefixes.forEach { rawPrefix ->
                    val dirKey = rawPrefix.trim().trimEnd('/')
                    if (dirKey.isBlank() || containsUnsafeNetworkPathSegment(dirKey)) return@forEach
                    val entryPath = "/$dirKey"
                    if (comparablePath(entryPath) == currentComparablePath) return@forEach
                    val dirName = dirKey.substringAfterLast('/', dirKey)
                    if (dirName.isBlank() || isUnsafeNetworkPathSegment(dirName)) return@forEach
                    if (!entriesByPath.containsKey(entryPath)) {
                        entriesByPath[entryPath] = NetworkFileEntry(
                            name = dirName,
                            path = entryPath,
                            isDirectory = true,
                            size = -1L,
                            createdDate = -1L,
                            updatedDate = -1L,
                            isHidden = dirName.startsWith('.')
                        )
                    }
                }

                page.contents.forEach { item ->
                    val key = item.key.trim()
                    if (key.isBlank()) return@forEach
                    if (prefix.isNotEmpty() && !key.startsWith(prefix)) return@forEach
                    val relative = if (prefix.isEmpty()) key else key.removePrefix(prefix)
                    if (relative.isBlank()) return@forEach
                    if (relative.contains('/') || isUnsafeNetworkPathSegment(relative.trimEnd('/'))) return@forEach

                    val isDirectory = key.endsWith('/')
                    val normalizedKey = if (isDirectory) key.trimEnd('/') else key
                    if (normalizedKey.isBlank()) return@forEach
                    val entryPath = "/$normalizedKey"
                    if (comparablePath(entryPath) == currentComparablePath) return@forEach

                    val name = normalizedKey.substringAfterLast('/', normalizedKey)
                    if (name.isBlank()) return@forEach
                    val updated = parseInstant(item.lastModified)
                    if (!entriesByPath.containsKey(entryPath)) {
                        entriesByPath[entryPath] = NetworkFileEntry(
                            name = name,
                            path = entryPath,
                            isDirectory = isDirectory,
                            size = if (isDirectory) -1L else item.size,
                            createdDate = if(isDirectory) -1L else updated,
                            updatedDate =  if(isDirectory) -1L else updated,
                            isHidden = name.startsWith('.')
                        )
                    }
                }

                continuationToken = page.nextContinuationToken
            } while (!continuationToken.isNullOrBlank())

            val entries = entriesByPath.values.toList()
            LogKit.i(AppStrings.ui_s3_list_arg0_count_arg1.format(arg0 = (normalizedPath), arg1 = (entries.size).toString()))
            Result.success(entries)
        }
    }

    override suspend fun download(
        remotePath: String,
        localPath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> {
        val key = normalizeObjectKey(remotePath, keepTrailingSlash = false)
        if (key.isBlank()) return Result.failure(IllegalArgumentException(AppStrings.ui_s3_object_path_cannot_be_empty))

        return runWithConfig(operation = AppStrings.ui_download, detail = key) {
            val expectedTotal = size.coerceAtLeast(0L)
            val existingSize = localExistingFileSize(localPath)
            if (existingSize > 0L && expectedTotal > 0L && existingSize >= expectedTotal) {
                onProgress(expectedTotal, expectedTotal)
                LogKit.i(AppStrings.ui_s3_download_skip_local_already_complete_arg0.format(arg0 = (key)))
                return@runWithConfig Result.success(true)
            }

            val resumeHeaders = if (existingSize > 0L) {
                mapOf(HttpHeaders.Range to "bytes=$existingSize-")
            } else {
                emptyMap()
            }
            executeSignedRequest(
                method = HttpMethod.Get,
                key = key,
                extraHeaders = resumeHeaders
            ) { response ->
                if (existingSize > 0L && response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                    val remoteTotal = parseUnsatisfiedRangeTotal(response.headers[HttpHeaders.ContentRange])
                    if (remoteTotal != null && existingSize >= remoteTotal) {
                        onProgress(remoteTotal, remoteTotal)
                        LogKit.i(AppStrings.ui_s3_download_service_end_range_not_satisfiable_considered_complete_arg0.format(arg0 = (key)))
                        return@executeSignedRequest Result.success(true)
                    }
                }
                if (!response.status.isSuccess()) {
                    return@executeSignedRequest failureWithResponse(AppStrings.ui_download_failed_from_s3, response)
                }

                val isPartialResponse = existingSize > 0L && response.status == HttpStatusCode.PartialContent
                val writeOffset = if (isPartialResponse) {
                    val contentRange = parseContentRange(response.headers[HttpHeaders.ContentRange])
                    if (contentRange == null || contentRange.start != existingSize) {
                        return@executeSignedRequest Result.failure(
                            Exception(AppStrings.ui_s3_continuation_failed_the_server_returned_a_range_that_does_not_match_the)
                        )
                    }
                    existingSize
                } else {
                    0L
                }

                val totalBytes = if (isPartialResponse) {
                    val contentRange = parseContentRange(response.headers[HttpHeaders.ContentRange])
                    contentRange?.total ?: (existingSize + (response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                        ?: 0L))
                } else {
                    response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: expectedTotal
                }

                val prepareResult = prepareLocalFileForDownload(
                    localPath = localPath,
                    keepExisting = isPartialResponse
                )
                if (prepareResult.isFailure) {
                    val error = prepareResult.exceptionOrNull() ?: Exception(AppStrings.ui_create_file_failure)
                    return@executeSignedRequest Result.failure(error)
                }

                onProgress(writeOffset, if (totalBytes > 0L) totalBytes else writeOffset)
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(S3_STREAM_BUFFER_SIZE)
                var offset = writeOffset
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    val chunk = buffer.copyOf(read)
                    val fileSize = if (totalBytes > 0L) totalBytes else offset + read
                    val writeResult = FileUtils.writeBytes(
                        FileAccessPermission.Allowed,
                        localPath,
                        fileSize,
                        chunk,
                        offset,
                    )
                    if (writeResult.isFailure) {
                        val error = writeResult.exceptionOrNull() ?: Exception(AppStrings.ui_write_failed)
                        return@executeSignedRequest Result.failure(error)
                    }
                    offset += read
                    val progressTotal = if (totalBytes > 0L) totalBytes else offset
                    onProgress(offset, progressTotal)
                }
                val progressTotal = if (totalBytes > 0L) totalBytes else offset
                onProgress(offset, progressTotal)
                LogKit.i(AppStrings.ui_s3_download_success_arg0.format(arg0 = (key)))
                Result.success(true)
            }
        }
    }

    override suspend fun downloadByChunks(
        remotePath: String,
        size: Long,
        onChunk: suspend (chunk: ByteArray, totalBytes: Long) -> Result<Unit>,
    ): Result<Boolean> {
        val key = normalizeObjectKey(remotePath, keepTrailingSlash = false)
        if (key.isBlank()) return Result.failure(IllegalArgumentException(AppStrings.ui_s3_object_path_cannot_be_empty))

        return runWithConfig(operation = AppStrings.ui_streamed_download, detail = key) {
            executeSignedRequest(
                method = HttpMethod.Get,
                key = key,
            ) { response ->
                if (!response.status.isSuccess()) {
                    return@executeSignedRequest failureWithResponse(AppStrings.network_s3_stream_download_failed, response)
                }

                val totalBytes = response.contentLength()?.takeIf { it > 0L } ?: size.coerceAtLeast(0L)
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(S3_STREAM_BUFFER_SIZE)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    val chunkResult = onChunk(buffer.copyOf(read), totalBytes)
                    if (chunkResult.isFailure) {
                        return@executeSignedRequest Result.failure(
                            chunkResult.exceptionOrNull() ?: Exception(AppStrings.network_s3_stream_download_failed)
                        )
                    }
                }
                LogKit.i(AppStrings.ui_s3_stream_download_successful_arg0.format(arg0 = (key)))
                Result.success(true)
            }
        }
    }

    override suspend fun upload(
        localPath: String,
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> {
        val key = normalizeObjectKey(remotePath, keepTrailingSlash = false)
        if (key.isBlank()) return Result.failure(IllegalArgumentException(AppStrings.ui_s3_object_path_cannot_be_empty))

        val localSize = resolveLocalFileSize(localPath, size)
        if (localSize < 0L) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_local_file_does_not_exist_arg0.format(arg0 = (localPath))))
        }

        return runWithConfig(operation = AppStrings.ui_upload, detail = key) {
            val remoteSizeResult = headObjectSize(key)
            if (remoteSizeResult.isFailure) {
                return@runWithConfig Result.failure(
                    remoteSizeResult.exceptionOrNull() ?: Exception(AppStrings.ui_s3_query_failed)
                )
            }
            val remoteSize = remoteSizeResult.getOrNull()
            if (remoteSize != null && remoteSize == localSize) {
                onProgress(localSize, localSize)
                LogKit.i(AppStrings.ui_s3_upload_skip_server_complete_arg0.format(arg0 = (key)))
                return@runWithConfig Result.success(true)
            }

            val ensureDirectories = ensureParentDirectoryMarkersForObject(key)
            if (ensureDirectories.isFailure) return@runWithConfig ensureDirectories

            if (localSize >= S3_SINGLE_UPLOAD_THRESHOLD) {
                val multipartResult = uploadMultipartWithResume(
                    localPath = localPath,
                    key = key,
                    localSize = localSize,
                    onProgress = onProgress
                )
                if (multipartResult.isFailure) return@runWithConfig multipartResult
                LogKit.i(AppStrings.ui_s3_multipart_upload_complete_arg0.format(arg0 = (key)))
                return@runWithConfig Result.success(true)
            }

            val singleUpload = uploadSingleObject(localPath, key, localSize, onProgress)
            if (singleUpload.isFailure) return@runWithConfig singleUpload
            LogKit.i(AppStrings.ui_s3_upload_successful_arg0.format(arg0 = (key)))
            Result.success(true)
        }
    }

    override suspend fun uploadFromSource(
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit,
        readChunk: suspend () -> ByteArray?,
    ): Result<Boolean> {
        requireKnownUploadSize(size)?.let { return it }
        val key = normalizeObjectKey(remotePath, keepTrailingSlash = false)
        if (key.isBlank()) return Result.failure(IllegalArgumentException(AppStrings.ui_s3_object_path_cannot_be_empty))
        return runWithConfig(operation = AppStrings.ui_upload, detail = key) {
            val ensureDirectories = ensureParentDirectoryMarkersForObject(key)
            if (ensureDirectories.isFailure) return@runWithConfig ensureDirectories
            if (size == 0L) {
                val empty = putEmptyObject(key)
                if (empty.isSuccess) onProgress(0L, 0L)
                return@runWithConfig empty
            }
            if (size >= S3_SINGLE_UPLOAD_THRESHOLD) {
                val multipartResult = uploadMultipartFromSource(
                    key = key,
                    size = size,
                    onProgress = onProgress,
                    readChunk = readChunk,
                )
                if (multipartResult.isFailure) return@runWithConfig multipartResult
                LogKit.i(AppStrings.ui_s3_multipart_upload_complete_arg0.format(arg0 = (key)))
                return@runWithConfig Result.success(true)
            }
            val singleUpload = uploadSingleObjectFromSource(key, size, onProgress, readChunk)
            if (singleUpload.isFailure) return@runWithConfig singleUpload
            LogKit.i(AppStrings.ui_s3_upload_successful_arg0.format(arg0 = (key)))
            Result.success(true)
        }
    }

    override suspend fun copyFile(sourcePath: String, targetPath: String): Result<Boolean> {
        val sourceKey = normalizeObjectKey(sourcePath, keepTrailingSlash = false)
        val targetKey = normalizeObjectKey(targetPath, keepTrailingSlash = false)
        if (sourceKey.isBlank() || targetKey.isBlank()) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_s3_file_path_cannot_be_empty))
        }
        return runWithConfig(operation = AppStrings.ui_copy_files, detail = "$sourceKey -> $targetKey") {
            val result = copyObject(sourceKey, targetKey)
            if (result.isFailure) return@runWithConfig result
            LogKit.i(AppStrings.ui_s3_copy_file_success_arg0_arg1.format(arg0 = (sourceKey), arg1 = (targetKey)))
            Result.success(true)
        }
    }

    override suspend fun rename(path: String, newPath: String): Result<Boolean> {
        val sourceKey = normalizeObjectKey(path, keepTrailingSlash = true)
        val targetKey = normalizeObjectKey(newPath, keepTrailingSlash = true)
        if (sourceKey.isBlank() || targetKey.isBlank()) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_path_cannot_be_empty))
        }
        return runWithConfig(operation = AppStrings.ui_rename, detail = "$sourceKey -> $targetKey") {
            val sourceIsDirectory = isDirectoryKey(sourceKey)
            if (!sourceIsDirectory) {
                val copy = copyObject(sourceKey, targetKey.trimEnd('/'))
                if (copy.isFailure) return@runWithConfig copy
                val delete = deleteObject(sourceKey.trimEnd('/'))
                if (delete.isFailure) return@runWithConfig delete
            } else {
                val sourcePrefix = ensureDirectoryKey(sourceKey)
                val targetPrefix = ensureDirectoryKey(targetKey)
                val move = movePrefix(sourcePrefix, targetPrefix)
                if (move.isFailure) return@runWithConfig move
            }
            LogKit.i(AppStrings.ui_s3_rename_succeeded_arg0_arg1.format(arg0 = (sourceKey), arg1 = (targetKey)))
            Result.success(true)
        }
    }

    override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> {
        val key = normalizeObjectKey(path, keepTrailingSlash = isDirectory)
        if (key.isBlank()) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_not_supported_for_deleting_s3_root_directory))
        }
        return runWithConfig(operation = AppStrings.ui_delete, detail = key) {
            val result = if (isDirectory) {
                deletePrefix(ensureDirectoryKey(key))
            } else {
                deleteObject(key.trimEnd('/'))
            }
            if (result.isFailure) return@runWithConfig result
            LogKit.i(AppStrings.ui_s3_deleted_successfully_arg0.format(arg0 = (key)))
            Result.success(true)
        }
    }

    override suspend fun createFolder(path: String): Result<Boolean> {
        val key = normalizeObjectKey(path, keepTrailingSlash = true)
        if (key.isBlank()) return Result.success(true)
        val folderKey = ensureDirectoryKey(key)
        return runWithConfig(operation = AppStrings.ui_create_directory, detail = folderKey) {
            val ensureResult = ensureDirectoryMarkerHierarchy(folderKey)
            if (ensureResult.isFailure) return@runWithConfig ensureResult
            LogKit.i(AppStrings.ui_s3_creates_a_directory_successfully_arg0.format(arg0 = (folderKey)))
            Result.success(true)
        }
    }

    override suspend fun createFile(path: String): Result<Boolean> {
        val key = normalizeObjectKey(path, keepTrailingSlash = false)
        if (key.isBlank()) return Result.failure(IllegalArgumentException(AppStrings.ui_s3_object_path_cannot_be_empty))
        return runWithConfig(operation = AppStrings.ui_create_file, detail = key) {
            val ensureDirectories = ensureParentDirectoryMarkersForObject(key)
            if (ensureDirectories.isFailure) return@runWithConfig ensureDirectories

            val response = putEmptyObject(key)
            if (response.isFailure) {
                return@runWithConfig Result.failure(
                    response.exceptionOrNull() ?: Exception(AppStrings.ui_s3_creates_a_file_failure)
                )
            }
            LogKit.i(AppStrings.ui_s3_creates_the_file_successfully_arg0.format(arg0 = (key)))
            Result.success(true)
        }
    }

    private suspend fun uploadSingleObject(
        localPath: String,
        key: String,
        localSize: Long,
        onProgress: (Long, Long) -> Unit,
    ): Result<Boolean> {
        val response = signedRequest(
            method = HttpMethod.Put,
            key = key,
            extraHeaders = mapOf(
                HttpHeaders.ContentType to ContentType.Application.OctetStream.toString()
            ),
            bodyBuilder = {
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override val contentLength: Long? = localSize.takeIf { it >= 0L }

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        var doneBytes = 0L
                        FileUtils.readFileChunks(
                            FileAccessPermission.Allowed,
                            localPath,
                            1024L * 1024L,
                        ).collect { result ->
                            val chunk = result.getOrElse { error -> throw error }.second
                            if (chunk.isEmpty()) return@collect
                            channel.writeFully(chunk)
                            doneBytes += chunk.size
                            val total = if (localSize > 0L) localSize else doneBytes
                            onProgress(doneBytes, total)
                        }
                        val total = if (localSize > 0L) localSize else doneBytes
                        onProgress(doneBytes, total)
                    }
                })
            }
        )
        return if (response.status.isSuccess()) {
            Result.success(true)
        } else {
            failureWithResponse(AppStrings.network_s3_upload_failed, response)
        }
    }

    private suspend fun uploadSingleObjectFromSource(
        key: String,
        size: Long,
        onProgress: (Long, Long) -> Unit,
        readChunk: suspend () -> ByteArray?,
    ): Result<Boolean> {
        val response = signedRequest(
            method = HttpMethod.Put,
            key = key,
            extraHeaders = mapOf(
                HttpHeaders.ContentType to ContentType.Application.OctetStream.toString()
            ),
            bodyBuilder = {
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override val contentLength: Long? = size.takeIf { it >= 0L }

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        pumpUploadChunks(
                            size = size,
                            onProgress = onProgress,
                            readChunk = readChunk,
                            writeChunk = { chunk -> channel.writeFully(chunk) },
                        ).getOrElse { error -> throw error }
                    }
                })
            }
        )
        return if (response.status.isSuccess()) {
            Result.success(true)
        } else {
            failureWithResponse(AppStrings.network_s3_upload_failed, response)
        }
    }

    private suspend fun uploadMultipartFromSource(
        key: String,
        size: Long,
        onProgress: (Long, Long) -> Unit,
        readChunk: suspend () -> ByteArray?,
    ): Result<Boolean> {
        val partSize = resolveMultipartPartSize(size)
        val totalParts = ((size + partSize - 1L) / partSize).toInt()
        if (totalParts <= 1) {
            return uploadSingleObjectFromSource(key, size, onProgress, readChunk)
        }
        val createResult = createMultipartUpload(key)
        if (createResult.isFailure) {
            return Result.failure(createResult.exceptionOrNull() ?: Exception(AppStrings.ui_failed_to_create_s3_bucket))
        }
        val uploadId = createResult.getOrNull().orEmpty()
        if (uploadId.isBlank()) {
            return Result.failure(Exception(AppStrings.ui_s3_create_partition_upload_failed_uploadid_is_empty))
        }
        val partBuffer = ByteArray(partSize.toInt())
        var filled = 0
        var partNumber = 1
        var doneBytes = 0L
        val partEtags = linkedMapOf<Int, String>()
        try {
            suspend fun flushPart() {
                if (filled <= 0) return
                val payload = partBuffer.copyOf(filled)
                val uploadPartResult = uploadMultipartPart(
                    key = key,
                    uploadId = uploadId,
                    partNumber = partNumber,
                    payload = payload,
                )
                if (uploadPartResult.isFailure) {
                    throw uploadPartResult.exceptionOrNull() ?: Exception(AppStrings.network_s3_multipart_upload_failed)
                }
                val eTag = uploadPartResult.getOrNull()?.trim().orEmpty().trim('"')
                if (eTag.isBlank()) {
                    throw Exception(AppStrings.ui_s3_upload_chunk_failed_response_missing_etag)
                }
                partEtags[partNumber] = eTag
                doneBytes += payload.size
                onProgress(doneBytes.coerceAtMost(size), size)
                partNumber++
                filled = 0
            }
            while (true) {
                val chunk = readChunk() ?: break
                if (chunk.isEmpty()) continue
                var offset = 0
                while (offset < chunk.size) {
                    val copySize = minOf(partBuffer.size - filled, chunk.size - offset)
                    chunk.copyInto(partBuffer, filled, offset, offset + copySize)
                    filled += copySize
                    offset += copySize
                    if (filled == partBuffer.size) {
                        flushPart()
                    }
                }
            }
            flushPart()
            if (partEtags.size < totalParts) {
                throw Exception(AppStrings.ui_s3_multipart_upload_failed_incomplete_partition)
            }
            val completeResult = completeMultipartUpload(key, uploadId, partEtags)
            if (completeResult.isFailure) {
                throw completeResult.exceptionOrNull() ?: Exception(AppStrings.network_s3_complete_multipart_upload_failed)
            }
            onProgress(size, size)
            return Result.success(true)
        } catch (cancel: CancellationException) {
            runCatching { abortMultipartUpload(key, uploadId) }
            throw cancel
        } catch (error: Exception) {
            runCatching { abortMultipartUpload(key, uploadId) }
            return Result.failure(error)
        }
    }

    private suspend fun uploadMultipartWithResume(
        localPath: String,
        key: String,
        localSize: Long,
        onProgress: (Long, Long) -> Unit,
    ): Result<Boolean> {
        val partSize = resolveMultipartPartSize(localSize)
        val totalParts = ((localSize + partSize - 1L) / partSize).toInt()
        if (totalParts <= 1) {
            return uploadSingleObject(localPath, key, localSize, onProgress)
        }

        val statePath = buildMultipartStatePath(localPath, key, localSize)
        val resumeState = loadMultipartResumeState(statePath, key, localSize, partSize)
        var uploadId = resumeState?.uploadId
        var uploadedEtags = resumeState?.partEtags?.toMutableMap() ?: linkedMapOf()
        val uploadedPartSizes = linkedMapOf<Int, Long>()

        if (!uploadId.isNullOrBlank()) {
            val listResult = listMultipartUploadedParts(key, uploadId)
            if (listResult.isSuccess) {
                uploadedEtags = linkedMapOf()
                uploadedPartSizes.clear()
                listResult.getOrDefault(emptyList()).forEach { part ->
                    uploadedEtags[part.partNumber] = part.eTag
                    uploadedPartSizes[part.partNumber] = part.size
                }
            } else {
                LogKit.w(AppStrings.ui_s3_continuation_status_invalid_re_create_slices_upload_arg0.format(arg0 = (listResult.exceptionOrNull()?.message).toString()))
                clearMultipartState(statePath)
                uploadId = null
                uploadedEtags = linkedMapOf()
                uploadedPartSizes.clear()
            }
        }

        if (uploadId.isNullOrBlank()) {
            val createResult = createMultipartUpload(key)
            if (createResult.isFailure) {
                return Result.failure(createResult.exceptionOrNull() ?: Exception(AppStrings.ui_failed_to_create_s3_bucket))
            }
            uploadId = createResult.getOrNull()
            if (uploadId.isNullOrBlank()) {
                return Result.failure(Exception(AppStrings.ui_s3_create_partition_upload_failed_uploadid_is_empty))
            }
            uploadedEtags.clear()
            uploadedPartSizes.clear()
            persistMultipartResumeState(
                statePath = statePath,
                uploadId = uploadId,
                key = key,
                fileSize = localSize,
                partSize = partSize,
                partEtags = uploadedEtags
            )
        }

        val stableUploadId = uploadId
        val safeUploadId = stableUploadId.ifBlank {
            return Result.failure(Exception(AppStrings.ui_s3_continuation_failed_uploadid_is_empty))
        }

        uploadedEtags.keys.forEach { partNumber ->
            if (!uploadedPartSizes.containsKey(partNumber)) {
                uploadedPartSizes[partNumber] = calculatePartLength(partNumber, localSize, partSize)
            }
        }
        var doneBytes = 0L
        uploadedPartSizes.values.forEach { value -> doneBytes += value }
        if (doneBytes > localSize) doneBytes = localSize
        onProgress(doneBytes, localSize)

        for (partNumber in 1..totalParts) {
            if (uploadedEtags.containsKey(partNumber)) continue

            val start = (partNumber - 1L) * partSize
            val endExclusive = minOf(start + partSize, localSize)
            if (endExclusive <= start) continue

            val partBytesResult = withBlockingFileIo {
                FileUtils.readFileRange(FileAccessPermission.Allowed, localPath, start, endExclusive)
            }
            if (partBytesResult.isFailure) {
                return Result.failure(partBytesResult.exceptionOrNull() ?: Exception(AppStrings.ui_read_the_chunk_failed))
            }
            val partBytes = partBytesResult.getOrDefault(ByteArray(0))
            val expectedSize = endExclusive - start
            if (partBytes.size.toLong() != expectedSize) {
                return Result.failure(
                    Exception(AppStrings.ui_reading_chunk_failed_expected_arg0_bytes_actual_arg1_bytes.format(arg0 = (expectedSize).toString(), arg1 = (partBytes.size).toString()))
                )
            }

            val uploadPartResult = uploadMultipartPart(
                key = key,
                uploadId = safeUploadId,
                partNumber = partNumber,
                payload = partBytes
            )
            if (uploadPartResult.isFailure) {
                return Result.failure(uploadPartResult.exceptionOrNull() ?: Exception(AppStrings.network_s3_multipart_upload_failed))
            }
            val eTag = uploadPartResult.getOrNull()?.trim().orEmpty().trim('"')
            if (eTag.isBlank()) {
                return Result.failure(Exception(AppStrings.ui_s3_upload_chunk_failed_response_missing_etag))
            }
            uploadedEtags[partNumber] = eTag
            uploadedPartSizes[partNumber] = partBytes.size.toLong()
            doneBytes += partBytes.size
            if (doneBytes > localSize) doneBytes = localSize
            onProgress(doneBytes, localSize)

            persistMultipartResumeState(
                statePath = statePath,
                uploadId = safeUploadId,
                key = key,
                fileSize = localSize,
                partSize = partSize,
                partEtags = uploadedEtags
            )
        }

        if (uploadedEtags.size < totalParts) {
            return Result.failure(Exception(AppStrings.ui_s3_multipart_upload_failed_incomplete_partition))
        }
        val completeResult = completeMultipartUpload(
            key = key,
            uploadId = safeUploadId,
            partEtags = uploadedEtags
        )
        if (completeResult.isFailure) return completeResult

        clearMultipartState(statePath)
        onProgress(localSize, localSize)
        return Result.success(true)
    }

    private suspend fun createMultipartUpload(key: String): Result<String> {
        val response = signedRequest(
            method = HttpMethod.Post,
            key = key,
            query = mapOf("uploads" to "")
        )
        if (!response.status.isSuccess()) {
            return failureWithResponse(AppStrings.ui_failed_to_create_s3_bucket, response)
        }
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        val uploadId = extractTagValue(body, "UploadId")?.let(::unescapeXml)?.trim().orEmpty()
        if (uploadId.isBlank()) {
            return Result.failure(Exception(AppStrings.ui_s3_create_partition_upload_failed_uploadid_is_empty))
        }
        return Result.success(uploadId)
    }

    private suspend fun listMultipartUploadedParts(
        key: String,
        uploadId: String,
    ): Result<List<S3MultipartPart>> {
        val parts = mutableListOf<S3MultipartPart>()
        var marker: Int? = null
        do {
            val query = linkedMapOf("uploadId" to uploadId)
            marker?.let { value -> query["part-number-marker"] = value.toString() }
            val response = signedRequest(
                method = HttpMethod.Get,
                key = key,
                query = query
            )
            if (response.status == HttpStatusCode.NotFound) {
                return Result.failure(Exception(AppStrings.ui_s3_upload_partition_does_not_exist))
            }
            if (!response.status.isSuccess()) {
                return failureWithResponse(AppStrings.ui_s3_query_failed_to_upload_part, response)
            }
            val xml = runCatching { response.bodyAsText() }.getOrDefault("")
            val page = parseListPartsPage(xml)
            parts += page.parts
            marker = if (page.isTruncated) page.nextPartNumberMarker else null
        } while (marker != null)

        return Result.success(
            parts
                .distinctBy { it.partNumber }
                .sortedBy { it.partNumber }
        )
    }

    private suspend fun uploadMultipartPart(
        key: String,
        uploadId: String,
        partNumber: Int,
        payload: ByteArray,
    ): Result<String> {
        val response = signedRequest(
            method = HttpMethod.Put,
            key = key,
            query = mapOf(
                "uploadId" to uploadId,
                "partNumber" to partNumber.toString()
            ),
            extraHeaders = mapOf(
                HttpHeaders.ContentType to ContentType.Application.OctetStream.toString()
            ),
            bodyBuilder = {
                setBody(payload)
            }
        )
        if (!response.status.isSuccess()) {
            return failureWithResponse(AppStrings.network_s3_multipart_upload_failed, response)
        }
        val eTag = response.headers["ETag"]?.trim().orEmpty()
        if (eTag.isBlank()) {
            return Result.failure(Exception(AppStrings.ui_s3_upload_chunk_failed_response_missing_etag))
        }
        return Result.success(eTag.trim('"'))
    }

    private suspend fun completeMultipartUpload(
        key: String,
        uploadId: String,
        partEtags: Map<Int, String>,
    ): Result<Boolean> {
        val sortedParts = partEtags.entries.sortedBy { it.key }
        val requestBody = buildString {
            append("<CompleteMultipartUpload>")
            sortedParts.forEach { (partNumber, eTagValue) ->
                append("<Part>")
                append("<PartNumber>")
                append(partNumber)
                append("</PartNumber>")
                append("<ETag>\"")
                append(escapeXml(eTagValue.trim('"')))
                append("\"</ETag>")
                append("</Part>")
            }
            append("</CompleteMultipartUpload>")
        }
        val response = signedRequest(
            method = HttpMethod.Post,
            key = key,
            query = mapOf("uploadId" to uploadId),
            extraHeaders = mapOf(
                HttpHeaders.ContentType to "application/xml"
            ),
            bodyBuilder = {
                setBody(requestBody.encodeToByteArray())
            }
        )
        if (!response.status.isSuccess()) {
            return failureWithResponse(AppStrings.network_s3_complete_multipart_upload_failed, response)
        }
        return Result.success(true)
    }

    private suspend fun abortMultipartUpload(key: String, uploadId: String): Result<Boolean> {
        val response = signedRequest(
            method = HttpMethod.Delete,
            key = key,
            query = mapOf("uploadId" to uploadId),
        )
        return if (response.status.isSuccess() || response.status == HttpStatusCode.NotFound) {
            Result.success(true)
        } else {
            failureWithResponse(AppStrings.network_s3_multipart_upload_failed, response)
        }
    }

    private suspend fun headObjectSize(key: String): Result<Long?> {
        val response = signedRequest(method = HttpMethod.Head, key = key)
        return when {
            response.status.isSuccess() -> {
                Result.success(response.headers[HttpHeaders.ContentLength]?.toLongOrNull())
            }

            response.status == HttpStatusCode.NotFound -> Result.success(null)
            else -> failureWithResponse(AppStrings.ui_s3_query_failed, response)
        }
    }

    private suspend fun ensureParentDirectoryMarkersForObject(key: String): Result<Boolean> {
        return ensureDirectoryMarkers(
            keys = buildDirectoryMarkerKeys(key, includeSelf = false),
            failureMessage = AppStrings.ui_failed_to_create_parent_directory
        )
    }

    private suspend fun ensureDirectoryMarkerHierarchy(key: String): Result<Boolean> {
        return ensureDirectoryMarkers(
            keys = buildDirectoryMarkerKeys(key, includeSelf = true),
            failureMessage = AppStrings.ui_s3_creates_a_directory_error
        )
    }

    private suspend fun ensureDirectoryMarkers(
        keys: List<String>,
        failureMessage: String,
    ): Result<Boolean> {
        keys.forEach { directoryKey ->
            if (objectExists(directoryKey)) return@forEach

            val createResult = putEmptyObject(directoryKey, failureMessage)
            if (createResult.isFailure) return createResult
            LogKit.i(AppStrings.ui_s3_creates_a_directory_tag_successfully_arg0.format(arg0 = (directoryKey)))
        }
        return Result.success(true)
    }

    private suspend fun putEmptyObject(
        key: String,
        failureMessage: String = AppStrings.ui_object_creation_failed,
    ): Result<Boolean> {
        val response = signedRequest(
            method = HttpMethod.Put,
            key = key,
            extraHeaders = mapOf(
                HttpHeaders.ContentType to ContentType.Application.OctetStream.toString()
            ),
            bodyBuilder = { setBody(ByteArray(0)) }
        )
        return if (response.status.isSuccess()) {
            Result.success(true)
        } else {
            failureWithResponse(failureMessage, response)
        }
    }

    private fun resolveMultipartPartSize(localSize: Long): Long {
        if (localSize <= 0L) return S3_DEFAULT_MULTIPART_SIZE
        val minByPartCount = (localSize + S3_MAX_MULTIPART_PARTS - 1L) / S3_MAX_MULTIPART_PARTS
        val required = maxOf(minByPartCount, S3_MIN_MULTIPART_SIZE)
        return maxOf(required, S3_DEFAULT_MULTIPART_SIZE)
    }

    private fun calculatePartLength(partNumber: Int, localSize: Long, partSize: Long): Long {
        val start = (partNumber - 1L) * partSize
        if (start >= localSize) return 0L
        val end = minOf(start + partSize, localSize)
        return end - start
    }

    private fun buildMultipartStatePath(localPath: String, key: String, fileSize: Long): String {
        val seed = "$bucketName|$region|$key|$fileSize"
        val digest = S3SigV4Crypto.sha256Hex(seed.encodeToByteArray()).take(20)
        return "$localPath.s3.resume.$digest.state"
    }

    private suspend fun loadMultipartResumeState(
        statePath: String,
        key: String,
        fileSize: Long,
        partSize: Long,
    ): S3MultipartResumeState? = withBlockingFileIo {
        val lines = runCatching {
            FileUtils.readFileLines(FileAccessPermission.Allowed, statePath)
        }.getOrDefault(emptyList())
        if (lines.isEmpty()) return@withBlockingFileIo null

        var version: String? = null
        var uploadId: String? = null
        var storedKey: String? = null
        var storedFileSize: Long? = null
        var storedPartSize: Long? = null
        val partEtags = linkedMapOf<Int, String>()

        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("version=") -> version = line.removePrefix("version=").trim()
                line.startsWith("uploadId=") -> uploadId = line.removePrefix("uploadId=").trim()
                line.startsWith("key=") -> storedKey = line.removePrefix("key=").trim()
                line.startsWith("fileSize=") -> storedFileSize = line.removePrefix("fileSize=").trim().toLongOrNull()
                line.startsWith("partSize=") -> storedPartSize = line.removePrefix("partSize=").trim().toLongOrNull()
                line.startsWith("part=") -> {
                    val payload = line.removePrefix("part=").trim()
                    val splitIndex = payload.indexOf(':')
                    if (splitIndex <= 0) return@forEach
                    val partNumber = payload.substring(0, splitIndex).toIntOrNull() ?: return@forEach
                    val eTag = payload.substring(splitIndex + 1).trim().trim('"')
                    if (partNumber > 0 && eTag.isNotBlank()) {
                        partEtags[partNumber] = eTag
                    }
                }
            }
        }

        if (version != S3_MULTIPART_STATE_VERSION) return@withBlockingFileIo null
        if (uploadId.isNullOrBlank()) return@withBlockingFileIo null
        if (storedKey != key) return@withBlockingFileIo null
        if (storedFileSize != fileSize) return@withBlockingFileIo null
        if (storedPartSize != partSize) return@withBlockingFileIo null

        S3MultipartResumeState(
            uploadId = uploadId,
            partEtags = partEtags
        )
    }

    private suspend fun persistMultipartResumeState(
        statePath: String,
        uploadId: String,
        key: String,
        fileSize: Long,
        partSize: Long,
        partEtags: Map<Int, String>,
    ) = withBlockingFileIo {
        val content = buildString {
            append("version=")
            append(S3_MULTIPART_STATE_VERSION)
            append('\n')
            append("uploadId=")
            append(uploadId)
            append('\n')
            append("key=")
            append(key)
            append('\n')
            append("fileSize=")
            append(fileSize)
            append('\n')
            append("partSize=")
            append(partSize)
            append('\n')
            partEtags.entries.sortedBy { it.key }.forEach { (partNumber, eTagValue) ->
                append("part=")
                append(partNumber)
                append(':')
                append(eTagValue.trim('"'))
                append('\n')
            }
        }

        runCatching {
            runCatching { FileUtils.deleteFile(FileAccessPermission.Allowed, statePath) }
            val createResult = FileUtils.createFile(FileAccessPermission.Allowed, statePath)
            if (createResult.isFailure) {
                throw createResult.exceptionOrNull() ?: Exception(AppStrings.ui_create_the_continuation_status_file_failed)
            }
            FileUtils.appendToFile(FileAccessPermission.Allowed, statePath, content)
        }.onFailure { error ->
            LogKit.w(AppStrings.ui_failed_to_save_s3_resume_state_arg0.format(arg0 = (error.message).toString()))
        }
    }

    private suspend fun clearMultipartState(statePath: String) = withBlockingFileIo {
        runCatching {
            FileUtils.deleteFile(FileAccessPermission.Allowed, statePath)
        }
    }

    private suspend fun localExistingFileSize(localPath: String): Long = withBlockingFileIo {
        val localFile = FileUtils.getFile(FileAccessPermission.Allowed, localPath).getOrNull()
        if (localFile == null || localFile.isDirectory) {
            0L
        } else {
            localFile.size.coerceAtLeast(0L)
        }
    }

    private suspend fun prepareLocalFileForDownload(localPath: String, keepExisting: Boolean): Result<Boolean> =
        withBlockingFileIo {
            if (!keepExisting) {
                runCatching { FileUtils.deleteFile(FileAccessPermission.Allowed, localPath) }
            }
            val exists = FileUtils.getFile(FileAccessPermission.Allowed, localPath).isSuccess
            if (exists) {
                Result.success(true)
            } else {
                FileUtils.createFile(FileAccessPermission.Allowed, localPath)
            }
        }

    private fun parseContentRange(value: String?): S3ContentRange? {
        if (value.isNullOrBlank()) return null
        val match = CONTENT_RANGE_REGEX.find(value.trim()) ?: return null
        val start = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return null
        val end = match.groupValues.getOrNull(2)?.toLongOrNull() ?: return null
        val totalRaw = match.groupValues.getOrNull(3).orEmpty()
        val total = if (totalRaw == "*") null else totalRaw.toLongOrNull()
        return S3ContentRange(start = start, end = end, total = total)
    }

    private fun parseUnsatisfiedRangeTotal(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val match = CONTENT_RANGE_NOT_SATISFIABLE_REGEX.find(value.trim()) ?: return null
        return match.groupValues.getOrNull(1)?.toLongOrNull()
    }

    private suspend fun <T> runWithConfig(
        operation: String,
        detail: String,
        block: suspend () -> Result<T>,
    ): Result<T> {
        if (bucketName.isBlank()) return Result.failure(IllegalArgumentException(AppStrings.ui_s3_bucket_cannot_be_empty))
        if (accessKeyId.isBlank()) return Result.failure(IllegalArgumentException(AppStrings.ui_s3_access_key_cannot_be_empty))
        if (secretAccessKey.isBlank()) return Result.failure(IllegalArgumentException(AppStrings.ui_secret_key_cannot_be_empty))
        if (region.isBlank()) return Result.failure(IllegalArgumentException(AppStrings.ui_region_cannot_be_empty))
        if (endpointInput.isNullOrBlank()) return Result.failure(IllegalArgumentException(AppStrings.ui_endpoint_cannot_be_empty))

        val endpointLabel = endpointInput
        LogKit.i(AppStrings.ui_s3_arg0_starts_arg1.format(arg0 = (operation), arg1 = (detail)))
        LogKit.i(AppStrings.ui_s3_connection_bucket_arg0_region_arg1_endpoint_arg2_forcepathstyle_arg3.format(arg0 = (bucketName), arg1 = (region), arg2 = (endpointLabel), arg3 = (forcePathStyle).toString()))
        return try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            LogKit.w(AppStrings.ui_s3_arg0_fails_arg1.format(arg0 = (operation), arg1 = (t.message).toString()), t)
            Result.failure(t)
        }
    }

    private suspend fun signedRequest(
        method: HttpMethod,
        key: String?,
        query: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        signedHeaders: Map<String, String> = emptyMap(),
        payloadHashOverride: String? = null,
        bodyBuilder: (HttpRequestBuilder.() -> Unit)? = null,
    ): HttpResponse {
        val plan = buildSignedRequestPlan(
            method = method,
            key = key,
            query = query,
            extraHeaders = extraHeaders,
            signedHeaders = signedHeaders,
            payloadHashOverride = payloadHashOverride,
            bodyBuilder = bodyBuilder,
        )
        return client.request {
            applySignedRequestPlan(plan)
        }
    }

    private suspend fun <T> executeSignedRequest(
        method: HttpMethod,
        key: String?,
        query: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        signedHeaders: Map<String, String> = emptyMap(),
        payloadHashOverride: String? = null,
        bodyBuilder: (HttpRequestBuilder.() -> Unit)? = null,
        block: suspend (HttpResponse) -> Result<T>,
    ): Result<T> {
        val plan = buildSignedRequestPlan(
            method = method,
            key = key,
            query = query,
            extraHeaders = extraHeaders,
            signedHeaders = signedHeaders,
            payloadHashOverride = payloadHashOverride,
            bodyBuilder = bodyBuilder,
        )
        return client.prepareRequest {
            applySignedRequestPlan(plan)
        }.execute { response ->
            block(response)
        }
    }

    private fun buildSignedRequestPlan(
        method: HttpMethod,
        key: String?,
        query: Map<String, String>,
        extraHeaders: Map<String, String>,
        signedHeaders: Map<String, String>,
        payloadHashOverride: String?,
        bodyBuilder: (HttpRequestBuilder.() -> Unit)?,
    ): SignedRequestPlan {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val dateStamp = "${now.year}${twoDigits(now.month.ordinal + 1)}${twoDigits(now.day)}"
        val amzDate = "${dateStamp}T${twoDigits(now.hour)}${twoDigits(now.minute)}${twoDigits(now.second)}Z"

        val requestHost = endpointConfig.hostWithBucket(bucketName)
        val canonicalHostHeader = endpointConfig.canonicalHost(bucketName)
        val path = endpointConfig.pathForKey(bucketName, key, ::awsPercentEncode)
        val payloadHash = payloadHashOverride ?: "UNSIGNED-PAYLOAD"

        val signingHeaders = linkedMapOf(
            "host" to canonicalHostHeader,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate
        )
        if (!sessionToken.isNullOrBlank()) {
            signingHeaders["x-amz-security-token"] = sessionToken
        }
        signedHeaders.forEach { (headerName, value) ->
            signingHeaders[headerName.lowercase()] = value.trim()
        }

        val canonicalQuery = canonicalQueryString(query)
        val canonicalHeaders = signingHeaders.entries
            .sortedBy { it.key }
            .joinToString(separator = "") { (name, value) ->
                "$name:${normalizeHeaderValue(value)}\n"
            }
        val signedHeadersValue = signingHeaders.keys
            .map { it.lowercase() }
            .sorted()
            .joinToString(";")
        val canonicalRequest = buildString {
            append(method.value.uppercase())
            append('\n')
            append(path)
            append('\n')
            append(canonicalQuery)
            append('\n')
            append(canonicalHeaders)
            append('\n')
            append(signedHeadersValue)
            append('\n')
            append(payloadHash)
        }
        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign = buildString {
            append("AWS4-HMAC-SHA256\n")
            append(amzDate)
            append('\n')
            append(credentialScope)
            append('\n')
            append(S3SigV4Crypto.sha256Hex(canonicalRequest.encodeToByteArray()))
        }
        val signingKey = S3SigV4Crypto.signingKey(
            secretKey = secretAccessKey,
            dateStamp = dateStamp,
            region = region,
            service = "s3"
        )
        val signature = S3SigV4Crypto.hmacSha256Hex(signingKey, stringToSign.encodeToByteArray())
        val authorization =
            "AWS4-HMAC-SHA256 Credential=$accessKeyId/$credentialScope, SignedHeaders=$signedHeadersValue, Signature=$signature"
        val requestUrl = endpointConfig.requestUrl(requestHost, path, query, ::awsPercentEncode)
        return SignedRequestPlan(
            method = method,
            requestUrl = requestUrl,
            signingHeaders = signingHeaders,
            extraHeaders = extraHeaders,
            authorization = authorization,
            bodyBuilder = bodyBuilder,
        )
    }

    private fun HttpRequestBuilder.applySignedRequestPlan(plan: SignedRequestPlan) {
        method = plan.method
        url.takeFrom(plan.requestUrl)
        plan.signingHeaders.forEach { (name, value) ->
            if (name != "host") {
                header(name, value)
            }
        }
        plan.extraHeaders.forEach { (name, value) ->
            if (!plan.signingHeaders.containsKey(name.lowercase())) {
                header(name, value)
            }
        }
        header(HttpHeaders.Authorization, plan.authorization)
        plan.bodyBuilder?.invoke(this)
    }

    private suspend fun movePrefix(sourcePrefix: String, targetPrefix: String): Result<Boolean> {
        if (sourcePrefix == targetPrefix) return Result.success(true)
        val sourceKeys = listAllKeys(sourcePrefix).getOrElse { error -> return Result.failure(error) }
        if (sourceKeys.isEmpty()) return Result.success(true)
        sourceKeys.forEach { sourceKey ->
            val suffix = sourceKey.removePrefix(sourcePrefix)
            val targetKey = targetPrefix + suffix
            val copy = copyObject(sourceKey, targetKey)
            if (copy.isFailure) return copy
        }
        return deleteKeys(sourceKeys)
    }

    private suspend fun deletePrefix(prefix: String): Result<Boolean> {
        val keys = listAllKeys(prefix).getOrElse { error -> return Result.failure(error) }
        if (keys.isEmpty()) {
            return deleteObject(prefix).recoverCatching { true }
        }
        return deleteKeys(keys)
    }

    private suspend fun listAllKeys(prefix: String): Result<List<String>> {
        val keys = mutableListOf<String>()
        var continuationToken: String? = null
        do {
            val query = linkedMapOf("list-type" to "2", "prefix" to prefix)
            if (!continuationToken.isNullOrBlank()) {
                query["continuation-token"] = continuationToken
            }
            val response = signedRequest(
                method = HttpMethod.Get,
                key = null,
                query = query
            )
            if (!response.status.isSuccess()) {
                return failureWithResponse(AppStrings.ui_failed_to_retrieve_s3_list, response)
            }
            val page = parseListObjectsPage(response.bodyAsText())
            page.contents.forEach { item ->
                val key = item.key.trim()
                if (key.isNotBlank()) keys += key
            }
            continuationToken = page.nextContinuationToken
        } while (!continuationToken.isNullOrBlank())
        return Result.success(keys)
    }

    private suspend fun deleteKeys(keys: List<String>): Result<Boolean> {
        keys.forEach { key ->
            val result = deleteObject(key)
            if (result.isFailure) return result
        }
        return Result.success(true)
    }

    private suspend fun deleteObject(key: String): Result<Boolean> {
        val response = signedRequest(method = HttpMethod.Delete, key = key)
        return if (response.status.isSuccess()) {
            Result.success(true)
        } else {
            failureWithResponse(AppStrings.ui_failed_to_delete_from_s3, response)
        }
    }

    private suspend fun copyObject(sourceKey: String, targetKey: String): Result<Boolean> {
        val normalizedTarget = targetKey.trim().ifBlank {
            return Result.failure(IllegalArgumentException(AppStrings.ui_target_path_cannot_be_empty))
        }
        val copySourceValue = buildCopySource(sourceKey)
        val response = signedRequest(
            method = HttpMethod.Put,
            key = normalizedTarget,
            signedHeaders = mapOf("x-amz-copy-source" to copySourceValue),
            payloadHashOverride = S3_EMPTY_PAYLOAD_SHA256,
            bodyBuilder = {
                setBody(ByteArray(0))
            }
        )
        if (!response.status.isSuccess()) {
            if (
                response.status == HttpStatusCode.MethodNotAllowed ||
                response.status == HttpStatusCode.NotImplemented
            ) {
                return Result.failure(
                    NetworkUnsupportedException(
                        AppStrings.ui_s3_server_does_not_support_copyobject_http_arg0.format(arg0 = (response.status.value).toString())
                    )
                )
            }
            return failureWithResponse(AppStrings.ui_s3_copy_failed, response)
        }
        val responseBody = response.bodyAsText()
        val validationError = validateCopyObjectResponse(responseBody)
        if (validationError != null) {
            LogKit.w(validationError.message.orEmpty())
            return Result.failure(validationError)
        }
        return Result.success(true)
    }

    private suspend fun isDirectoryKey(key: String): Boolean {
        if (key.endsWith('/')) return true
        val normalizedKey = key.trimEnd('/')
        if (objectExists(normalizedKey)) return false
        val prefix = ensureDirectoryKey(normalizedKey)
        return listHasAnyObject(prefix)
    }

    private suspend fun objectExists(key: String): Boolean {
        val response = runCatching {
            signedRequest(method = HttpMethod.Head, key = key)
        }.getOrElse { return false }
        return response.status.isSuccess()
    }

    private suspend fun listHasAnyObject(prefix: String): Boolean {
        val response = runCatching {
            signedRequest(
                method = HttpMethod.Get,
                key = null,
                query = linkedMapOf(
                    "list-type" to "2",
                    "prefix" to prefix,
                    "max-keys" to "1"
                )
            )
        }.getOrElse { return false }
        if (!response.status.isSuccess()) return false
        val page = parseListObjectsPage(response.bodyAsText())
        return page.contents.isNotEmpty() || page.commonPrefixes.isNotEmpty()
    }

    private suspend fun <T> failureWithResponse(message: String, response: HttpResponse): Result<T> {
        val body = runCatching { response.bodyAsText() }.getOrElse { "" }
        val errorMessage = buildString {
            append(message)
            append(": HTTP ")
            append(response.status.value)
            if (body.isNotBlank()) {
                append(' ')
                append(body.trim())
            }
        }
        LogKit.w(errorMessage)
        return Result.failure(Exception(errorMessage))
    }

    private suspend fun resolveLocalFileSize(localPath: String, hintedSize: Long): Long {
        if (hintedSize >= 0L) {
            return hintedSize
        }
        return withBlockingFileIo {
            FileUtils.getFile(FileAccessPermission.Allowed, localPath).getOrNull()?.size ?: -1L
        }
    }

    private suspend fun <T> withBlockingFileIo(block: () -> T): T = withContext(Dispatchers.Default) {
        block()
    }

    private fun resolveEndpointConfig(): S3EndpointConfig {
        if (!endpointInput.isNullOrBlank()) {
            val parsed = Url(endpointInput)
            val port = parsed.port.takeIf { it != parsed.protocol.defaultPort }
            return S3EndpointConfig(
                protocol = parsed.protocol,
                host = parsed.host,
                port = port,
                basePath = parsed.encodedPath.trimEnd('/'),
                pathStyle = forcePathStyle
            )
        }
        val resolvedHost = if (region.isBlank()) {
            "s3.amazonaws.com"
        } else {
            "s3.$region.amazonaws.com"
        }
        return S3EndpointConfig(
            protocol = URLProtocol.HTTPS,
            host = resolvedHost,
            port = null,
            basePath = "",
            pathStyle = forcePathStyle
        )
    }

    private fun normalizeRemotePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/"
        val normalized = if (network.pathSeparator != "/") {
            trimmed.replace(network.pathSeparator, "/")
        } else {
            trimmed
        }
        val withSlash = if (normalized.startsWith('/')) normalized else "/$normalized"
        requireSafeNetworkWritePath(withSlash)
        return withSlash
    }

    private fun normalizeObjectKey(path: String, keepTrailingSlash: Boolean): String {
        val normalizedPath = normalizeRemotePath(path)
        var key = normalizedPath.removePrefix("/").trim()
        if (!keepTrailingSlash) {
            key = key.trimEnd('/')
        }
        return key
    }

    private fun listPrefixFromPath(path: String): String {
        val key = normalizeObjectKey(path, keepTrailingSlash = true)
        if (key.isBlank()) return ""
        return ensureDirectoryKey(key)
    }

    private fun ensureDirectoryKey(key: String): String {
        if (key.isBlank()) return ""
        return if (key.endsWith('/')) key else "$key/"
    }

    private fun buildDirectoryMarkerKeys(key: String, includeSelf: Boolean): List<String> {
        val normalizedKey = key.trim().trim('/')
        if (normalizedKey.isBlank()) return emptyList()

        val segments = normalizedKey.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return emptyList()

        val maxIndex = if (includeSelf) {
            segments.lastIndex
        } else {
            segments.lastIndex - 1
        }
        if (maxIndex < 0) return emptyList()

        val keys = ArrayList<String>(maxIndex + 1)
        val builder = StringBuilder()
        for (index in 0..maxIndex) {
            if (builder.isNotEmpty()) builder.append('/')
            builder.append(segments[index])
            keys += "$builder/"
        }
        return keys
    }

    private fun comparablePath(path: String): String {
        val normalized = normalizeRemotePath(path)
        return if (normalized == "/") normalized else normalized.trimEnd('/')
    }

    private fun buildCopySource(sourceKey: String): String {
        val rawKey = sourceKey.trim().takeIf { it.isNotBlank() } ?: return bucketName
        val keepTrailingSlash = rawKey.endsWith('/')
        val normalizedKey = rawKey.trim('/').takeIf { it.isNotBlank() }
        val encodedKey = normalizedKey
            ?.split('/')
            ?.joinToString("/") { segment -> awsPercentEncode(segment) }
            .orEmpty()
        val prefix = if (encodedKey.isBlank()) bucketName else "$bucketName/$encodedKey"
        return if (keepTrailingSlash && prefix != bucketName) "$prefix/" else prefix
    }

    private fun parseListObjectsPage(xml: String): ListObjectsPage {
        val commonPrefixes = COMMON_PREFIX_REGEX.findAll(xml)
            .mapNotNull { match ->
                match.groupValues.getOrNull(1)?.trim()?.let(::unescapeXml)
            }
            .filter { item -> item.isNotBlank() }
            .toList()

        val contents = CONTENTS_REGEX.findAll(xml).mapNotNull { match ->
            val block = match.groupValues.getOrNull(1) ?: return@mapNotNull null
            val key = extractTagValue(block, "Key")?.let(::unescapeXml)?.trim().orEmpty()
            if (key.isBlank()) return@mapNotNull null
            val size = extractTagValue(block, "Size")?.trim()?.toLongOrNull() ?: 0L
            val lastModified = extractTagValue(block, "LastModified")?.trim().orEmpty()
            S3ObjectItem(key = key, size = size, lastModified = lastModified)
        }.toList()

        val nextContinuationToken = extractTagValue(xml, "NextContinuationToken")
            ?.let(::unescapeXml)
            ?.trim()
            ?.ifBlank { null }
        return ListObjectsPage(
            commonPrefixes = commonPrefixes,
            contents = contents,
            nextContinuationToken = nextContinuationToken
        )
    }

    private fun parseListPartsPage(xml: String): ListPartsPage {
        val parts = PART_REGEX.findAll(xml).mapNotNull { match ->
            val block = match.groupValues.getOrNull(1) ?: return@mapNotNull null
            val partNumber = extractTagValue(block, "PartNumber")?.trim()?.toIntOrNull() ?: return@mapNotNull null
            val eTag = extractTagValue(block, "ETag")
                ?.let(::unescapeXml)
                ?.trim()
                ?.trim('"')
                .orEmpty()
            if (eTag.isBlank()) return@mapNotNull null
            val partSize = extractTagValue(block, "Size")?.trim()?.toLongOrNull() ?: 0L
            S3MultipartPart(
                partNumber = partNumber,
                eTag = eTag,
                size = partSize
            )
        }.toList()

        val isTruncated = extractTagValue(xml, "IsTruncated")
            ?.trim()
            ?.equals("true", ignoreCase = true)
            ?: false
        val nextPartNumberMarker = extractTagValue(xml, "NextPartNumberMarker")
            ?.trim()
            ?.toIntOrNull()
        return ListPartsPage(
            parts = parts,
            isTruncated = isTruncated,
            nextPartNumberMarker = nextPartNumberMarker
        )
    }

    private fun extractTagValue(xml: String, tag: String): String? {
        val escaped = Regex.escape(tag)
        val pattern = "(?is)<\\s*$escaped\\b[^>]*>(.*?)</\\s*$escaped\\b>"
        return pattern.toRegex()
            .find(xml)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    private fun validateCopyObjectResponse(responseBody: String): Exception? {
        if (S3_ERROR_RESPONSE_REGEX.containsMatchIn(responseBody)) {
            val code = extractTagValue(responseBody, "Code")
                ?.let(::unescapeXml)
                ?.trim()
                .orEmpty()
            val detail = extractTagValue(responseBody, "Message")
                ?.let(::unescapeXml)
                ?.trim()
                .orEmpty()
            val suffix = listOf(code, detail)
                .filter { value -> value.isNotBlank() }
                .joinToString(": ")
            return Exception(
                if (suffix.isBlank()) AppStrings.ui_s3_copy_failed_server_returned_an_error_in_the_successful_state
                else AppStrings.ui_s3_copy_failed_arg0.format(arg0 = (suffix))
            )
        }

        val result = extractTagValue(responseBody, "CopyObjectResult")
            ?: return Exception(AppStrings.ui_s3_copy_failed_response_missing_copyobjectresult)
        val eTag = extractTagValue(result, "ETag")?.trim().orEmpty()
        val lastModified = extractTagValue(result, "LastModified")?.trim().orEmpty()
        if (eTag.isBlank() || lastModified.isBlank()) {
            return Exception(AppStrings.network_s3_copy_failed_incomplete_result)
        }
        return null
    }

    private fun parseInstant(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrDefault(0L)
    }

    private fun unescapeXml(value: String): String {
        return value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun canonicalQueryString(query: Map<String, String>): String {
        return query.entries
            .sortedWith(compareBy<Map.Entry<String, String>> { it.key }.thenBy { it.value })
            .joinToString("&") { (name, value) ->
                "${awsPercentEncode(name)}=${awsPercentEncode(value)}"
            }
    }

    private fun awsPercentEncode(value: String): String {
        return value.encodeToByteArray().joinToString(separator = "") { byte ->
            val intValue = byte.toInt() and 0xff
            when (intValue.toChar()) {
                in 'A'..'Z', in 'a'..'z', in '0'..'9', '-', '_', '.', '~' -> intValue.toChar().toString()
                else -> "%${intValue.toString(16).uppercase().padStart(2, '0')}"
            }
        }
    }

    private fun normalizeHeaderValue(value: String): String {
        return value.trim().replace(Regex("\\s+"), " ")
    }

    private fun twoDigits(value: Int): String = value.toString().padStart(2, '0')

    private data class S3EndpointConfig(
        val protocol: URLProtocol,
        val host: String,
        val port: Int?,
        val basePath: String,
        val pathStyle: Boolean,
    ) {
        fun hostWithBucket(bucket: String): String {
            return if (pathStyle) host else "$bucket.$host"
        }

        fun canonicalHost(bucket: String): String {
            val requestHost = hostWithBucket(bucket)
            val portSuffix = port?.let { ":$it" }.orEmpty()
            return requestHost + portSuffix
        }

        fun pathForKey(
            bucket: String,
            key: String?,
            encoder: (String) -> String,
        ): String {
            val pathSegments = mutableListOf<String>()
            val cleanedBase = basePath.trim('/').takeIf { it.isNotBlank() }
            if (cleanedBase != null) {
                pathSegments += cleanedBase.split('/').filter { it.isNotBlank() }
            }
            if (pathStyle) {
                pathSegments += bucket
            }
            val rawKey = key?.trim()?.takeIf { it.isNotBlank() }
            val keepTrailingSlash = rawKey?.endsWith('/') == true
            val normalizedKey = rawKey?.trim('/')?.takeIf { it.isNotBlank() }
            if (normalizedKey != null) {
                pathSegments += normalizedKey.split('/').map { segment -> encoder(segment) }
            }
            val path = if (pathSegments.isEmpty()) {
                "/"
            } else {
                "/${pathSegments.joinToString("/")}"
            }
            return if (keepTrailingSlash && path != "/") "$path/" else path
        }

        fun requestUrl(
            hostWithBucket: String,
            path: String,
            query: Map<String, String>,
            encoder: (String) -> String,
        ): String {
            val portSuffix = port?.let { ":$it" }.orEmpty()
            val queryString = if (query.isEmpty()) {
                ""
            } else {
                query.entries.joinToString("&", prefix = "?") { (name, value) ->
                    "${encoder(name)}=${encoder(value)}"
                }
            }
            return "${protocol.name}://$hostWithBucket$portSuffix$path$queryString"
        }
    }

    private data class S3ObjectItem(
        val key: String,
        val size: Long,
        val lastModified: String,
    )

    private data class ListObjectsPage(
        val commonPrefixes: List<String>,
        val contents: List<S3ObjectItem>,
        val nextContinuationToken: String?,
    )

    private data class S3MultipartPart(
        val partNumber: Int,
        val eTag: String,
        val size: Long,
    )

    private data class ListPartsPage(
        val parts: List<S3MultipartPart>,
        val isTruncated: Boolean,
        val nextPartNumberMarker: Int?,
    )

    private data class S3MultipartResumeState(
        val uploadId: String,
        val partEtags: Map<Int, String>,
    )

    private data class S3ContentRange(
        val start: Long,
        val end: Long,
        val total: Long?,
    )

    private data class SignedRequestPlan(
        val method: HttpMethod,
        val requestUrl: String,
        val signingHeaders: Map<String, String>,
        val extraHeaders: Map<String, String>,
        val authorization: String,
        val bodyBuilder: (HttpRequestBuilder.() -> Unit)?,
    )

    private companion object {
        private val COMMON_PREFIX_REGEX = Regex(
            "(?is)<\\s*CommonPrefixes\\b[^>]*>\\s*<\\s*Prefix\\b[^>]*>(.*?)</\\s*Prefix\\b>\\s*</\\s*CommonPrefixes\\b>"
        )
        private val CONTENTS_REGEX = Regex(
            "(?is)<\\s*Contents\\b[^>]*>(.*?)</\\s*Contents\\b>"
        )
        private val PART_REGEX = Regex(
            "(?is)<\\s*Part\\b[^>]*>(.*?)</\\s*Part\\b>"
        )
        private val CONTENT_RANGE_REGEX = Regex(
            "^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$",
            setOf(RegexOption.IGNORE_CASE)
        )
        private val CONTENT_RANGE_NOT_SATISFIABLE_REGEX = Regex(
            "^bytes\\s+\\*/(\\d+)$",
            setOf(RegexOption.IGNORE_CASE)
        )
    }
}

private object S3SigV4Crypto {
    private val K = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
    )

    fun sha256Hex(data: ByteArray): String = toHex(sha256(data))

    fun hmacSha256Hex(key: ByteArray, data: ByteArray): String = toHex(hmacSha256(key, data))

    fun signingKey(secretKey: String, dateStamp: String, region: String, service: String): ByteArray {
        val kSecret = ("AWS4$secretKey").encodeToByteArray()
        val kDate = hmacSha256(kSecret, dateStamp.encodeToByteArray())
        val kRegion = hmacSha256(kDate, region.encodeToByteArray())
        val kService = hmacSha256(kRegion, service.encodeToByteArray())
        return hmacSha256(kService, "aws4_request".encodeToByteArray())
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val blockSize = 64
        val normalizedKey = if (key.size > blockSize) sha256(key) else key
        val keyBlock = ByteArray(blockSize)
        normalizedKey.copyInto(keyBlock)

        val oKeyPad = ByteArray(blockSize)
        val iKeyPad = ByteArray(blockSize)
        for (i in 0 until blockSize) {
            oKeyPad[i] = (keyBlock[i].toInt() xor 0x5c).toByte()
            iKeyPad[i] = (keyBlock[i].toInt() xor 0x36).toByte()
        }
        val inner = sha256(iKeyPad + data)
        return sha256(oKeyPad + inner)
    }

    private fun sha256(input: ByteArray): ByteArray {
        val h = intArrayOf(
            0x6a09e667,
            0xbb67ae85.toInt(),
            0x3c6ef372,
            0xa54ff53a.toInt(),
            0x510e527f,
            0x9b05688c.toInt(),
            0x1f83d9ab,
            0x5be0cd19
        )

        val padded = padSha256(input)
        val w = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                val j = offset + i * 4
                w[i] = ((padded[j].toInt() and 0xff) shl 24) or
                        ((padded[j + 1].toInt() and 0xff) shl 16) or
                        ((padded[j + 2].toInt() and 0xff) shl 8) or
                        (padded[j + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]

            for (i in 0 until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + s1 + ch + K[i] + w[i]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj

                hh = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh

            offset += 64
        }

        val out = ByteArray(32)
        for (i in h.indices) {
            val value = h[i]
            out[i * 4] = (value ushr 24).toByte()
            out[i * 4 + 1] = (value ushr 16).toByte()
            out[i * 4 + 2] = (value ushr 8).toByte()
            out[i * 4 + 3] = value.toByte()
        }
        return out
    }

    private fun padSha256(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8L
        val withOne = input.size + 1
        val padZeros = ((56 - (withOne % 64)) + 64) % 64
        val totalSize = withOne + padZeros + 8
        val out = ByteArray(totalSize)
        input.copyInto(out)
        out[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            out[totalSize - 1 - i] = ((bitLength ushr (8 * i)) and 0xff).toByte()
        }
        return out
    }

    private fun rotr(value: Int, bits: Int): Int = (value ushr bits) or (value shl (32 - bits))

    private fun toHex(bytes: ByteArray): String {
        return bytes.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
