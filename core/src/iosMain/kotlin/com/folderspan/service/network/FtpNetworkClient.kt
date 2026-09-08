@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.folderspan.service.network

import strings.AppStrings

import com.folderspan.data.main.network.*
import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.utils.IosSecurityScopeStore
import com.folderspan.utils.LogKit
import com.folderspan.utils.NetworkHostUtils
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.curl.CURLE_OK
import platform.curl.CURLINFO_RESPONSE_CODE
import platform.curl.CURLOPT_CUSTOMREQUEST
import platform.curl.CURLOPT_FTPPORT
import platform.curl.CURLOPT_INFILESIZE_LARGE
import platform.curl.CURLOPT_NOBODY
import platform.curl.CURLOPT_PASSWORD
import platform.curl.CURLOPT_QUOTE
import platform.curl.CURLOPT_READDATA
import platform.curl.CURLOPT_READFUNCTION
import platform.curl.CURLOPT_UPLOAD
import platform.curl.CURLOPT_URL
import platform.curl.CURLOPT_USERNAME
import platform.curl.CURLOPT_USE_SSL
import platform.curl.CURLOPT_WRITEDATA
import platform.curl.CURLOPT_WRITEFUNCTION
import platform.curl.CURL_GLOBAL_DEFAULT
import platform.curl.curl_easy_cleanup
import platform.curl.curl_easy_getinfo
import platform.curl.curl_easy_init
import platform.curl.curl_easy_perform
import platform.curl.curl_easy_setopt
import platform.curl.curl_easy_strerror
import platform.curl.curl_global_cleanup
import platform.curl.curl_global_init
import platform.curl.curl_slist
import platform.curl.curl_slist_append
import platform.curl.curl_slist_free_all
import platform.posix.*
import kotlin.text.equals
import kotlin.time.Clock

internal class FtpNetworkClient(private val network: Network) : NetworkClient {
    override suspend fun list(
        path: String,
        requestId: String?,
        batchId: String?
    ): Result<List<NetworkFileEntry>> {
        val normalized = normalizePath(path)
        val mlsdResult = fetchListing(normalized, useMlsd = true)
        val parsed = mlsdResult.getOrNull()?.let { parseListing(it, path) }
        if (!parsed.isNullOrEmpty()) {
            LogKit.i(AppStrings.ui_ftp_list_arg0_count_arg1.format(arg0 = normalized, arg1 = (parsed.size).toString()))
            return Result.success(parsed)
        }
        return fetchListing(normalized, useMlsd = false).map { list ->
            val result = parseListing(list, path)
            LogKit.i(AppStrings.ui_ftp_list_arg0_count_arg1.format(arg0 = normalized, arg1 = (result.size).toString()))
            result
        }
    }

    override suspend fun download(
        remotePath: String,
        localPath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> {
        return IosSecurityScopeStore.withSecurityScopeIfNeeded(localPath) {
            val fd = open(localPath, O_WRONLY or O_CREAT or O_TRUNC or O_NOFOLLOW, 420)
            if (fd < 0) {
                return@withSecurityScopeIfNeeded Result.failure(Exception(AppStrings.ui_unable_to_write_to_local_file))
            }
            val file = fdopen(fd, "wb")
            if (file == null) {
                close(fd)
                return@withSecurityScopeIfNeeded Result.failure(Exception(AppStrings.ui_unable_to_write_to_local_file))
            }
            try {
                withCurl(AppStrings.ui_download, buildUrl(remotePath)) { curl ->
                    val context = CurlTransferContext(file, size, onProgress)
                    val ref = StableRef.create(context)
                    try {
                        curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, staticCFunction(::curlWriteFileCallback))
                        curl_easy_setopt(curl, CURLOPT_WRITEDATA, ref.asCPointer())
                        val res = curl_easy_perform(curl)
                        if (res != CURLE_OK) {
                            Result.failure(Exception(AppStrings.ui_ftp_download_failed_arg0.format(arg0 = (curlError(res)).toString())))
                        } else {
                            Result.success(true)
                        }
                    } finally {
                        ref.dispose()
                    }
                }
            } finally {
                fclose(file)
            }
        }
    }

    override suspend fun upload(
        localPath: String,
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> {
        return IosSecurityScopeStore.withSuspendSecurityScopeIfNeeded(localPath) {
            val fd = open(localPath, O_RDONLY or O_NOFOLLOW)
            if (fd < 0) {
                return@withSuspendSecurityScopeIfNeeded Result.failure(Exception(AppStrings.ui_unable_read_local_file))
            }
            val file = fdopen(fd, "rb")
            if (file == null) {
                close(fd)
                return@withSuspendSecurityScopeIfNeeded Result.failure(Exception(AppStrings.ui_unable_read_local_file))
            }
            try {
                withCurl(AppStrings.ui_upload, buildUrl(remotePath)) { curl ->
                    curl_easy_setopt(curl, CURLOPT_UPLOAD, 1L)
                    curl_easy_setopt(curl, CURLOPT_INFILESIZE_LARGE, size)
                    val context = CurlTransferContext(file, size, onProgress)
                    val ref = StableRef.create(context)
                    try {
                        curl_easy_setopt(curl, CURLOPT_READFUNCTION, staticCFunction(::curlReadFileCallback))
                        curl_easy_setopt(curl, CURLOPT_READDATA, ref.asCPointer())
                        val res = curl_easy_perform(curl)
                        if (res != CURLE_OK) {
                            Result.failure(Exception(AppStrings.ui_ftp_upload_failed_arg0.format(arg0 = (curlError(res)).toString())))
                        } else {
                            Result.success(true)
                        }
                    } finally {
                        ref.dispose()
                    }
                }
            } finally {
                fclose(file)
            }
        }
    }

    override suspend fun uploadFromSource(
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit,
        readChunk: suspend () -> ByteArray?,
    ): Result<Boolean> {
        return consumeUploadChunksBlocking(readChunk) { nextChunk ->
            withCurl(AppStrings.ui_upload, buildUrl(remotePath)) { curl ->
                curl_easy_setopt(curl, CURLOPT_UPLOAD, 1L)
                if (size >= 0L) {
                    curl_easy_setopt(curl, CURLOPT_INFILESIZE_LARGE, size)
                }
                val context = CurlMemoryReadContext(size, onProgress, nextChunk)
                val ref = StableRef.create(context)
                try {
                    curl_easy_setopt(curl, CURLOPT_READFUNCTION, staticCFunction(::curlReadMemoryCallback))
                    curl_easy_setopt(curl, CURLOPT_READDATA, ref.asCPointer())
                    val res = curl_easy_perform(curl)
                    when {
                        context.failed -> Result.failure(
                            context.failure ?: Exception(AppStrings.ui_ftp_upload_failed_arg0.format(arg0 = "read"))
                        )
                        res != CURLE_OK -> Result.failure(
                            Exception(AppStrings.ui_ftp_upload_failed_arg0.format(arg0 = (curlError(res)).toString()))
                        )
                        else -> Result.success(true)
                    }
                } finally {
                    ref.dispose()
                }
            }
        }
    }

    override suspend fun copyFile(sourcePath: String, targetPath: String): Result<Boolean> {
        val source = normalizePath(sourcePath)
        val target = normalizePath(targetPath)
        if (source.isBlank() || target.isBlank()) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_ftp_server_path_cannot_be_empty))
        }
        if (source.contains('\r') || source.contains('\n') || target.contains('\r') || target.contains('\n')) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_ftp_server_copy_path_cannot_contain_newline_characters))
        }
        return performQuote(
            listOf(
                "SITE CPFR $source",
                "SITE CPTO $target"
            ),
            unsupportedReplyCodes = setOf(202L, 500L, 502L, 504L),
        )
    }

    override suspend fun rename(path: String, newPath: String): Result<Boolean> {
        return performQuote(
            listOf(
                "RNFR ${normalizePath(path)}",
                "RNTO ${normalizePath(newPath)}"
            )
        )
    }

    override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> {
        val command = if (isDirectory) "RMD ${normalizePath(path)}" else "DELE ${normalizePath(path)}"
        return performQuote(listOf(command))
    }

    override suspend fun createFolder(path: String): Result<Boolean> {
        return performQuote(listOf("MKD ${normalizePath(path)}"))
    }

    override suspend fun createFile(path: String): Result<Boolean> {
        return withCurl(AppStrings.ui_create_file, buildUrl(path)) { curl ->
            curl_easy_setopt(curl, CURLOPT_UPLOAD, 1L)
            curl_easy_setopt(curl, CURLOPT_INFILESIZE_LARGE, 0L)
            curl_easy_setopt(curl, CURLOPT_READFUNCTION, staticCFunction(::curlEmptyReadCallback))
            val res = curl_easy_perform(curl)
            if (res != CURLE_OK) {
                Result.failure(Exception(AppStrings.ui_ftp_creates_file_failed_arg0.format(arg0 = (curlError(res)).toString())))
            } else {
                Result.success(true)
            }
        }
    }

    private suspend fun fetchListing(path: String, useMlsd: Boolean): Result<String> {
        val targetPath = path.trimEnd('/')
        val listingPath = if (targetPath.isBlank()) "" else "$targetPath/"
        val hasNonAsciiPath = listingPath.any { it.code > 0x7F }
        val preferredMode = network.extras.ftp.pathEncoding.toUrlPathMode()

        val primary = fetchListingOnce(
            path = listingPath,
            useMlsd = useMlsd,
            urlPathMode = preferredMode,
            warnOnFailure = !useMlsd && !hasNonAsciiPath
        )
        if (primary.isSuccess) {
            return primary
        }

        var lastResult = primary
        var lastErrorMessage = primary.exceptionOrNull()?.message.orEmpty()

        retryUrlPathModes(preferredMode, hasNonAsciiPath).forEach { mode ->
            if (!shouldRetryWithFallback(lastErrorMessage)) {
                return@forEach
            }
            LogKit.i(AppStrings.ui_ftp_list_retry_arg0_arg1.format(arg0 = mode.retryLabel(), arg1 = buildUrl(listingPath, urlPathMode = mode)))
            val retried = fetchListingOnce(
                path = listingPath,
                useMlsd = useMlsd,
                urlPathMode = mode,
                warnOnFailure = false
            )
            if (retried.isSuccess) {
                return retried
            }
            lastResult = retried
            lastErrorMessage = retried.exceptionOrNull()?.message.orEmpty()
        }

        val cwdPath = listingPath.trim('/')
        if (hasNonAsciiPath && cwdPath.isNotBlank() && shouldRetryWithFallback(lastErrorMessage)) {
            LogKit.i(AppStrings.ui_ftp_list_retry_cwd_arg0.format(arg0 = cwdPath))
            val cwdRetry = fetchListingViaCwd(
                path = cwdPath,
                useMlsd = useMlsd,
                urlPathMode = preferredMode,
                warnOnFailure = false
            )
            if (cwdRetry.isSuccess) {
                return cwdRetry
            }
            lastResult = cwdRetry
            lastErrorMessage = cwdRetry.exceptionOrNull()?.message.orEmpty()
        }

        if (!useMlsd && hasNonAsciiPath) {
            LogKit.w(AppStrings.ui_ftp_list_list_failed_arg0.format(arg0 = lastErrorMessage))
        }
        return lastResult
    }

    private fun retryUrlPathModes(preferredMode: UrlPathMode, hasNonAsciiPath: Boolean): List<UrlPathMode> {
        if (!hasNonAsciiPath) return emptyList()
        val allModes = listOf(
            UrlPathMode.UTF8_PERCENT,
            UrlPathMode.GB18030_PERCENT,
            UrlPathMode.SHIFT_JIS_PERCENT,
            UrlPathMode.CP949_PERCENT,
            UrlPathMode.WINDOWS_1251_PERCENT,
            UrlPathMode.RAW_UNICODE
        )
        return allModes.filter { it != preferredMode }
    }

    private fun UrlPathMode.shouldSendUtf8Option(): Boolean {
        return this == UrlPathMode.UTF8_PERCENT || this == UrlPathMode.RAW_UNICODE
    }

    private fun UrlPathMode.retryLabel(): String {
        return when (this) {
            UrlPathMode.UTF8_PERCENT -> "UTF-8 URL"
            UrlPathMode.GB18030_PERCENT -> "GB18030 URL"
            UrlPathMode.SHIFT_JIS_PERCENT -> "Shift_JIS URL"
            UrlPathMode.CP949_PERCENT -> "CP949 URL"
            UrlPathMode.WINDOWS_1251_PERCENT -> "Windows-1251 URL"
            UrlPathMode.RAW_UNICODE -> AppStrings.ui_original_path_url
        }
    }

    private fun FtpPathEncoding.toUrlPathMode(): UrlPathMode {
        return when (this) {
            FtpPathEncoding.Auto -> UrlPathMode.UTF8_PERCENT
            FtpPathEncoding.Utf8 -> UrlPathMode.UTF8_PERCENT
            FtpPathEncoding.Gb18030 -> UrlPathMode.GB18030_PERCENT
            FtpPathEncoding.ShiftJis -> UrlPathMode.SHIFT_JIS_PERCENT
            FtpPathEncoding.Cp949 -> UrlPathMode.CP949_PERCENT
            FtpPathEncoding.Windows1251 -> UrlPathMode.WINDOWS_1251_PERCENT
        }
    }

    private suspend fun fetchListingOnce(
        path: String,
        useMlsd: Boolean,
        urlPathMode: UrlPathMode,
        warnOnFailure: Boolean
    ): Result<String> {
        val buffer = CurlBuffer(listingDecodeEncodings(urlPathMode))
        val ref = StableRef.create(buffer)
        var quoteList: CPointer<curl_slist>? = null
        return try {
            withCurl(
                if (useMlsd) AppStrings.ui_list_mlsd else AppStrings.ui_list_list,
                buildUrl(path, urlPathMode = urlPathMode),
                warnOnFailure = warnOnFailure
            ) { curl ->
                if (urlPathMode.shouldSendUtf8Option()) {
                    quoteList = curl_slist_append(quoteList, "*OPTS UTF8 ON")
                }
                if (quoteList != null) {
                    curl_easy_setopt(curl, CURLOPT_QUOTE, quoteList)
                }
                curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, staticCFunction(::curlWriteCallback))
                curl_easy_setopt(curl, CURLOPT_WRITEDATA, ref.asCPointer())
                if (useMlsd) {
                    curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, "MLSD")
                } else {
                    curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, "LIST")
                }
                val res = curl_easy_perform(curl)
                if (res != CURLE_OK) {
                    Result.failure(Exception(AppStrings.ui_ftp_list_failed_arg0.format(arg0 = (curlError(res)).toString())))
                } else {
                    Result.success(buffer.asString())
                }
            }
        } finally {
            if (quoteList != null) {
                curl_slist_free_all(quoteList)
            }
            ref.dispose()
        }
    }

    private suspend fun fetchListingViaCwd(
        path: String,
        useMlsd: Boolean,
        urlPathMode: UrlPathMode,
        warnOnFailure: Boolean
    ): Result<String> {
        val buffer = CurlBuffer(listingDecodeEncodings(urlPathMode))
        val ref = StableRef.create(buffer)
        var quoteList: CPointer<curl_slist>? = null
        return try {
            withCurl(
                if (useMlsd) AppStrings.ui_list_mlsd_cwd else AppStrings.ui_list_list_cwd,
                buildUrl(""),
                warnOnFailure = warnOnFailure
            ) { curl ->
                if (urlPathMode.shouldSendUtf8Option()) {
                    quoteList = curl_slist_append(quoteList, "*OPTS UTF8 ON")
                }
                path.split('/')
                    .map { decodePercentEncodedSegment(it) }
                    .filter { it.isNotEmpty() }
                    .forEach { segment ->
                        quoteList = curl_slist_append(quoteList, "CWD $segment")
                    }
                if (quoteList != null) {
                    curl_easy_setopt(curl, CURLOPT_QUOTE, quoteList)
                }
                curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, staticCFunction(::curlWriteCallback))
                curl_easy_setopt(curl, CURLOPT_WRITEDATA, ref.asCPointer())
                if (useMlsd) {
                    curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, "MLSD")
                } else {
                    curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, "LIST")
                }
                val res = curl_easy_perform(curl)
                if (res != CURLE_OK) {
                    Result.failure(Exception(AppStrings.ui_ftp_list_failed_arg0.format(arg0 = (curlError(res)).toString())))
                } else {
                    Result.success(buffer.asString())
                }
            }
        } finally {
            if (quoteList != null) {
                curl_slist_free_all(quoteList)
            }
            ref.dispose()
        }
    }

    private fun shouldRetryWithFallback(message: String): Boolean {
        if (message.isBlank()) return true
        val lower = message.lowercase()
        val nonRetryable = listOf(
            "authentication failed",
            "login denied",
            "not logged in",
            "failed to connect",
            "couldn't connect",
            "operation timed out",
            "timeout was reached",
            "could not resolve host",
            "ssl connect error"
        )
        return nonRetryable.none { lower.contains(it) }
    }

    private enum class UrlPathMode {
        UTF8_PERCENT,
        GB18030_PERCENT,
        SHIFT_JIS_PERCENT,
        CP949_PERCENT,
        WINDOWS_1251_PERCENT,
        RAW_UNICODE,
    }

    private suspend fun performQuote(
        commands: List<String>,
        unsupportedReplyCodes: Set<Long> = emptySet(),
    ): Result<Boolean> {
        var slist: CPointer<curl_slist>? = null
        return try {
            commands.forEach { cmd ->
                slist = curl_slist_append(slist, cmd)
            }
            withCurl(AppStrings.ui_command, buildUrl("")) { curl ->
                curl_easy_setopt(curl, CURLOPT_NOBODY, 1L)
                curl_easy_setopt(curl, CURLOPT_QUOTE, slist)
                val res = curl_easy_perform(curl)
                val responseCode = memScoped {
                    val value = alloc<LongVar>()
                    val infoResult = curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, value.ptr)
                    if (infoResult == CURLE_OK) value else 0L
                }
                if (responseCode in unsupportedReplyCodes) {
                    Result.failure(
                        NetworkUnsupportedException(
                            AppStrings.ui_ftp_server_does_not_support_server_replication_arg0.format(arg0 = (responseCode).toString())
                        )
                    )
                } else if (res != CURLE_OK) {
                    Result.failure(Exception(AppStrings.ui_ftp_command_failed_arg0.format(arg0 = (curlError(res)).toString())))
                } else {
                    Result.success(true)
                }
            }
        } finally {
            if (slist != null) {
                curl_slist_free_all(slist)
            }
        }
    }

    private suspend fun <T> withCurl(
        label: String,
        url: String,
        warnOnFailure: Boolean = true,
        block: (COpaquePointer) -> Result<T>
    ): Result<T> = withContext(Dispatchers.Default) {
        LogKit.i("FTP $label: $url")
        val globalInit = curl_global_init(CURL_GLOBAL_DEFAULT.toLong())
        if (globalInit != CURLE_OK) {
            LogKit.w(AppStrings.ui_ftp_initialization_failed_arg0.format(arg0 = label))
            return@withContext Result.failure(Exception(AppStrings.ui_initialization_of_ftp_client_failed))
        }
        val curl = curl_easy_init() ?: run {
            curl_global_cleanup()
            LogKit.w(AppStrings.ui_ftp_initialization_failed_arg0.format(arg0 = label))
            return@withContext Result.failure(Exception(AppStrings.ui_initialization_of_ftp_client_failed))
        }
        try {
            configureCommon(curl, url)
            val result = runCatching { block(curl) }.getOrElse { error ->
                LogKit.w(AppStrings.ui_ftp_arg0_exception_arg1.format(arg0 = label, arg1 = (error.message).toString()), error)
                Result.failure(error)
            }
            if (result.isSuccess) {
                LogKit.i(AppStrings.ui_ftp_arg0_success_arg1.format(arg0 = label, arg1 = network.host))
            } else {
                val message = AppStrings.ui_ftp_arg0_failed_arg1.format(arg0 = label, arg1 = (result.exceptionOrNull()?.message).toString())
                if (warnOnFailure) {
                    LogKit.w(message)
                }
            }
            result
        } finally {
            curl_easy_cleanup(curl)
            curl_global_cleanup()
        }
    }

    private fun configureCommon(curl: COpaquePointer, url: String) {
        val ftpExtras = network.extras.ftp
        curl_easy_setopt(curl, CURLOPT_URL, url)
        val username = network.username.ifBlank { "anonymous" }
        curl_easy_setopt(curl, CURLOPT_USERNAME, username)
        curl_easy_setopt(curl, CURLOPT_PASSWORD, network.password)
        val sslMode = if (ftpExtras.ftpsEnabled) 3L else 0L
        curl_easy_setopt(curl, CURLOPT_USE_SSL, sslMode)
        if (!ftpExtras.passiveMode) {
            curl_easy_setopt(curl, CURLOPT_FTPPORT, "-")
        }
    }

    private fun buildUrl(path: String, urlPathMode: UrlPathMode = network.extras.ftp.pathEncoding.toUrlPathMode()): String {
        val scheme = if (network.extras.ftp.ftpsEnabled) "ftps" else "ftp"
        val resolved = NetworkHostUtils.resolveHostPort(network.host, 21)
        val port = resolved.port ?: 21
        val hostPort = NetworkHostUtils.combineHostPort(resolved.host, port)
        val normalized = normalizePath(path)
        if (normalized.isBlank()) {
            return "$scheme://$hostPort/"
        }
        val encoded = encodePathForUrl(normalized, urlPathMode)
        return "$scheme://$hostPort/$encoded"
    }

    private fun encodePathForUrl(path: String, urlPathMode: UrlPathMode): String {
        val builder = StringBuilder(path.length)
        path.forEach { ch ->
            if (ch == '/' || isUnreservedPathChar(ch)) {
                builder.append(ch)
                return@forEach
            }
            if (urlPathMode == UrlPathMode.RAW_UNICODE && ch.code > 0x7F) {
                builder.append(ch)
                return@forEach
            }

            val bytes = when {
                ch.code <= 0x7F -> ch.toString().encodeToByteArray()
                else -> {
                    val nsEncoding = urlPathMode.toNSStringEncoding()
                    if (nsEncoding != null) {
                        encodeStringToByteArray(ch.toString(), nsEncoding) ?: ch.toString().encodeToByteArray()
                    } else {
                        ch.toString().encodeToByteArray()
                    }
                }
            }
            appendPercentEncoded(builder, bytes)
        }
        return builder.toString()
    }

    private fun UrlPathMode.toNSStringEncoding(): ULong? {
        return when (this) {
            UrlPathMode.UTF8_PERCENT -> null
            UrlPathMode.GB18030_PERCENT -> gb18030Encoding()
            UrlPathMode.SHIFT_JIS_PERCENT -> shiftJisEncoding()
            UrlPathMode.CP949_PERCENT -> cp949Encoding()
            UrlPathMode.WINDOWS_1251_PERCENT -> windows1251Encoding()
            UrlPathMode.RAW_UNICODE -> null
        }
    }

    private fun listingDecodeEncodings(urlPathMode: UrlPathMode): List<ULong> {
        val ordered = LinkedHashSet<ULong>()
        val preferred = when (urlPathMode) {
            UrlPathMode.UTF8_PERCENT,
            UrlPathMode.RAW_UNICODE -> NSUTF8StringEncoding

            UrlPathMode.GB18030_PERCENT -> gb18030Encoding()
            UrlPathMode.SHIFT_JIS_PERCENT -> shiftJisEncoding()
            UrlPathMode.CP949_PERCENT -> cp949Encoding()
            UrlPathMode.WINDOWS_1251_PERCENT -> windows1251Encoding()
        }
        ordered.add(preferred)
        ordered.add(NSUTF8StringEncoding)
        ordered.add(gb18030Encoding())
        ordered.add(shiftJisEncoding())
        ordered.add(cp949Encoding())
        ordered.add(windows1251Encoding())
        return ordered.toList()
    }

    private fun decodePercentEncodedSegment(segment: String): String {
        if (!segment.contains('%')) return segment
        return runCatching { decodePercentEncodedUtf8(segment) }
            .getOrDefault(segment)
    }

    private fun decodePercentEncodedUtf8(value: String): String {
        if (value.isEmpty()) return ""
        val bytes = mutableListOf<Byte>()
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current == '%' && index + 2 < value.length) {
                val high = hexValue(value[index + 1])
                val low = hexValue(value[index + 2])
                if (high >= 0 && low >= 0) {
                    bytes.add(((high shl 4) or low).toByte())
                    index += 3
                    continue
                }
            }
            current.toString().encodeToByteArray().forEach { bytes.add(it) }
            index++
        }
        return ByteArray(bytes.size) { idx -> bytes[idx] }.decodeToString()
    }

    private fun hexValue(ch: Char): Int {
        return when (ch) {
            in '0'..'9' -> ch.code - '0'.code
            in 'a'..'f' -> 10 + (ch.code - 'a'.code)
            in 'A'..'F' -> 10 + (ch.code - 'A'.code)
            else -> -1
        }
    }

    private fun appendPercentEncoded(builder: StringBuilder, bytes: ByteArray) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            val hex = value.toString(16).uppercase().padStart(2, '0')
            builder.append('%').append(hex)
        }
    }

    private fun encodeStringToByteArray(value: String, encoding: ULong): ByteArray? {
        if (value.isEmpty()) return ByteArray(0)
        return memScoped {
            val nsValue = NSString.create(cString = value.cstr.getPointer(this), encoding = NSUTF8StringEncoding) ?: return@memScoped null
            val data = nsValue.dataUsingEncoding(encoding) ?: return@memScoped null
            val length = data.length().toInt()
            if (length <= 0) return@memScoped ByteArray(0)
            val pointer = data.bytes() ?: return@memScoped null
            pointer.reinterpret<ByteVar>().readBytes(length)
        }
    }

    private fun gb18030Encoding(): ULong {
        return CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingGB_18030_2000.toUInt())
    }

    private fun shiftJisEncoding(): ULong {
        return CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingShiftJIS.toUInt())
    }

    private fun cp949Encoding(): ULong {
        return CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingDOSKorean.toUInt())
    }

    private fun windows1251Encoding(): ULong {
        return CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingWindowsCyrillic.toUInt())
    }

    private fun isUnreservedPathChar(ch: Char): Boolean {
        return ((ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9')) ||
            ch == '-' || ch == '_' || ch == '.' || ch == '~' || ch == '%'
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return ""
        val normalized = path.replace("\\", "/").trimStart('/')
        requireSafeNetworkWritePath(normalized)
        return normalized
    }

    private fun joinPath(base: String, name: String): String {
        require(!isUnsafeNetworkPathSegment(name)) { AppStrings.ui_remote_path_invalid }
        val separator = network.pathSeparator
        val normalized = if (base.endsWith(separator)) base else base + separator
        return normalized + name
    }

    private fun parseListing(raw: String, basePath: String): List<NetworkFileEntry> {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val hasMlsd = lines.any { it.contains("type=", ignoreCase = true) }
        return if (hasMlsd) {
            parseMlsd(lines, basePath)
        } else {
            parseList(lines, basePath)
        }
    }

    private fun parseMlsd(lines: List<String>, basePath: String): List<NetworkFileEntry> {
        val entries = mutableListOf<NetworkFileEntry>()
        for (line in lines) {
            val parts = line.split(" ", limit = 2)
            if (parts.size < 2) continue
            val name = parts[1].trim()
            if (name == "." || name == "..") continue
            val facts = parts[0].split(";")
                .mapNotNull {
                    val idx = it.indexOf('=')
                    if (idx <= 0) null else it.substring(0, idx).lowercase() to it.substring(idx + 1)
                }.toMap()
            val type = facts["type"]?.lowercase().orEmpty()
            val isDir = type == "dir" || type == "cdir" || type == "pdir"
            val size = if (isDir) -1L else (facts["size"]?.toLongOrNull() ?: 0L)
            val modified = facts["modify"]?.let { parseMlsdTime(it) } ?: 0L
            entries.add(
                NetworkFileEntry(
                    name = name,
                    path = joinPath(basePath, name),
                    isDirectory = isDir,
                    size = size,
                    createdDate = modified,
                    updatedDate = modified,
                    isHidden = name.startsWith(".")
                )
            )
        }
        return entries
    }

    private fun parseList(lines: List<String>, basePath: String): List<NetworkFileEntry> {
        val entries = mutableListOf<NetworkFileEntry>()
        for (line in lines) {
            if (line.startsWith("total", ignoreCase = true)) continue

            val unixParts = line.split(Regex("\\s+"), limit = 9)
            if (unixParts.size >= 9 && unixParts[0].isNotEmpty()) {
                val kind = unixParts[0].first()
                if (kind in setOf('d', '-', 'l', 'b', 'c', 's', 'p')) {
                    val rawName = unixParts[8].trim()
                    val name = rawName.substringBefore(" -> ").trim()
                    if (name.isNotEmpty() && !isUnsafeNetworkPathSegment(name)) {
                        val isDir = kind == 'd'
                        val size = if (isDir) -1L else (unixParts.getOrNull(4)?.toLongOrNull() ?: 0L)
                        val modified = parseUnixListTime(
                            monthToken = unixParts.getOrNull(5).orEmpty(),
                            dayToken = unixParts.getOrNull(6).orEmpty(),
                            yearOrTimeToken = unixParts.getOrNull(7).orEmpty()
                        )
                        entries.add(
                            NetworkFileEntry(
                                name = name,
                                path = joinPath(basePath, name),
                                isDirectory = isDir,
                                size = size,
                                createdDate = modified,
                                updatedDate = modified,
                                isHidden = name.startsWith(".")
                            )
                        )
                        continue
                    }
                }
            }

            val dosParts = line.split(Regex("\\s+"), limit = 4)
            if (dosParts.size >= 4) {
                val marker = dosParts[2]
                val name = dosParts[3].trim()
                if (name.isNotEmpty() && name != "." && name != "..") {
                    val isDir = marker.equals("<DIR>", ignoreCase = true)
                    val size = if (isDir) -1L else marker.replace(",", "").toLongOrNull()
                    if (isDir || size != null) {
                        val modified = parseDosListTime(
                            dateToken = dosParts[0],
                            timeToken = dosParts[1]
                        )
                        entries.add(
                            NetworkFileEntry(
                                name = name,
                                path = joinPath(basePath, name),
                                isDirectory = isDir,
                                size = size ?: 0L,
                                createdDate = modified,
                                updatedDate = modified,
                                isHidden = name.startsWith(".")
                            )
                        )
                        continue
                    }
                }
            }

            val fallbackName = line.substringAfterLast(' ').trim()
            if (fallbackName.isEmpty() || fallbackName == "." || fallbackName == "..") continue
            val fallbackIsDir = line.firstOrNull() == 'd'
            entries.add(
                NetworkFileEntry(
                    name = fallbackName,
                    path = joinPath(basePath, fallbackName),
                    isDirectory = fallbackIsDir,
                    size = if (fallbackIsDir) -1L else 0L,
                    createdDate = 0L,
                    updatedDate = 0L,
                    isHidden = fallbackName.startsWith(".")
                )
            )
        }
        return entries
    }

    private fun parseUnixListTime(monthToken: String, dayToken: String, yearOrTimeToken: String): Long {
        val month = monthFromToken(monthToken) ?: return 0L
        val day = dayToken.toIntOrNull() ?: return 0L
        val timeZone = TimeZone.currentSystemDefault()
        return runCatching {
            if (yearOrTimeToken.contains(':')) {
                val timeParts = yearOrTimeToken.split(':', limit = 2)
                val hour = timeParts.getOrNull(0)?.toIntOrNull() ?: return@runCatching 0L
                val minute = timeParts.getOrNull(1)?.toIntOrNull() ?: return@runCatching 0L
                val nowInstant = Clock.System.now()
                val nowLocal = nowInstant.toLocalDateTime(timeZone)
                var year = nowLocal.year
                var localDateTime = LocalDateTime(year, month, day, hour, minute, 0)
                var instant = localDateTime.toInstant(timeZone)
                if (instant.toEpochMilliseconds() - nowInstant.toEpochMilliseconds() > ONE_DAY_MILLIS) {
                    year -= 1
                    localDateTime = LocalDateTime(year, month, day, hour, minute, 0)
                    instant = localDateTime.toInstant(timeZone)
                }
                instant.toEpochMilliseconds()
            } else {
                val year = yearOrTimeToken.toIntOrNull() ?: return@runCatching 0L
                LocalDateTime(year, month, day, 0, 0, 0)
                    .toInstant(timeZone)
                    .toEpochMilliseconds()
            }
        }.getOrDefault(0L)
    }

    private fun parseDosListTime(dateToken: String, timeToken: String): Long {
        val dateParts = dateToken.replace('/', '-').split('-', limit = 3)
        if (dateParts.size != 3) return 0L
        val month = dateParts[0].toIntOrNull() ?: return 0L
        val day = dateParts[1].toIntOrNull() ?: return 0L
        var year = dateParts[2].toIntOrNull() ?: return 0L
        if (year < 100) {
            year += if (year >= 70) 1900 else 2000
        }

        val upper = timeToken.trim().uppercase()
        val isAm = upper.endsWith("AM")
        val isPm = upper.endsWith("PM")
        val raw = upper.removeSuffix("AM").removeSuffix("PM")
        val parts = raw.split(':', limit = 2)
        var hour = parts.getOrNull(0)?.toIntOrNull() ?: return 0L
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return 0L
        if (isPm && hour in 1..11) hour += 12
        if (isAm && hour == 12) hour = 0

        return runCatching {
            LocalDateTime(year, month, day, hour, minute, 0)
                .toInstant(TimeZone.currentSystemDefault())
                .toEpochMilliseconds()
        }.getOrDefault(0L)
    }

    private fun monthFromToken(token: String): Int? {
        return when (token.trim().take(3).lowercase()) {
            "jan" -> 1
            "feb" -> 2
            "mar" -> 3
            "apr" -> 4
            "may" -> 5
            "jun" -> 6
            "jul" -> 7
            "aug" -> 8
            "sep" -> 9
            "oct" -> 10
            "nov" -> 11
            "dec" -> 12
            else -> null
        }
    }

    private companion object {
        const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L
    }

    private fun parseMlsdTime(value: String): Long {
        if (value.length < 14) return 0L
        return runCatching {
            val year = value.substring(0, 4).toInt()
            val month = value.substring(4, 6).toInt()
            val day = value.substring(6, 8).toInt()
            val hour = value.substring(8, 10).toInt()
            val minute = value.substring(10, 12).toInt()
            val second = value.substring(12, 14).toInt()
            LocalDateTime(year, month, day, hour, minute, second)
                .toInstant(TimeZone.UTC)
                .toEpochMilliseconds()
        }.getOrDefault(0L)
    }

    private fun curlError(code: UInt): String {
        return curl_easy_strerror(code)?.toKString() ?: "Unknown error"
    }
}

private class CurlBuffer(
    private val preferredEncodings: List<ULong> = listOf(NSUTF8StringEncoding)
) {
    private var data = ByteArray(0)

    fun append(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        val merged = ByteArray(data.size + chunk.size)
        data.copyInto(merged, 0, 0, data.size)
        chunk.copyInto(merged, data.size, 0, chunk.size)
        data = merged
    }

    fun asString(): String {
        if (data.isEmpty()) return ""
        val candidates = preferredEncodings
            .mapNotNull { encoding ->
                data.decodeWithEncoding(encoding)?.let { decoded -> encoding to decoded }
            }

        if (candidates.isEmpty()) {
            return data.decodeToString()
        }

        candidates.firstOrNull { (_, decoded) -> !decoded.contains(REPLACEMENT_CHAR) }
            ?.let { (_, decoded) -> return decoded }

        return candidates.minByOrNull { (_, decoded) -> replacementCount(decoded) }
            ?.second
            ?: data.decodeToString()
    }

    private fun ByteArray.decodeWithEncoding(encoding: ULong): String? {
        if (isEmpty()) return ""
        return usePinned { pinned ->
            val nsData = NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
            NSString.create(data = nsData, encoding = encoding)?.toString()
        }
    }

    private fun replacementCount(value: String): Int = value.count { it == REPLACEMENT_CHAR }

    private companion object {
        const val REPLACEMENT_CHAR: Char = '\uFFFD'
    }
}

private class CurlTransferContext(
    val file: CPointer<FILE>,
    val totalBytes: Long,
    val onProgress: (Long, Long) -> Unit
) {
    var doneBytes: Long = 0L
}

private class CurlMemoryReadContext(
    val totalBytes: Long,
    val onProgress: (Long, Long) -> Unit,
    val nextChunk: () -> ByteArray?,
) {
    var doneBytes: Long = 0L
    var buffer: ByteArray = EmptyBytes
    var position: Int = 0
    var eof: Boolean = false
    var failed: Boolean = false
    var failure: Throwable? = null

    private companion object {
        val EmptyBytes = ByteArray(0)
    }
}

private fun curlWriteFileCallback(
    buffer: CPointer<ByteVar>?,
    size: size_t,
    nmemb: size_t,
    userData: COpaquePointer?
): size_t {
    if (buffer == null || userData == null) return 0UL
    val context = userData.asStableRef<CurlTransferContext>().get()
    val written = fwrite(buffer, size, nmemb, context.file)
    if (written > 0UL) {
        context.doneBytes += (written * size).toLong()
        val totalBytes = if (context.totalBytes > 0L) context.totalBytes else context.doneBytes
        context.onProgress(context.doneBytes, totalBytes)
    }
    return written
}

private fun curlReadFileCallback(
    buffer: CPointer<ByteVar>?,
    size: size_t,
    nmemb: size_t,
    userData: COpaquePointer?
): size_t {
    if (buffer == null || userData == null) return 0UL
    val context = userData.asStableRef<CurlTransferContext>().get()
    val read = fread(buffer, size, nmemb, context.file)
    if (read > 0UL) {
        context.doneBytes += (read * size).toLong()
        val totalBytes = if (context.totalBytes > 0L) context.totalBytes else context.doneBytes
        context.onProgress(context.doneBytes, totalBytes)
    }
    return read
}

private fun curlWriteCallback(
    buffer: CPointer<ByteVar>?,
    size: size_t,
    nmemb: size_t,
    userData: COpaquePointer?
): size_t {
    if (buffer == null || userData == null) return 0UL
    val total = size * nmemb
    val bytes = buffer.readBytes(total.toInt())
    userData.asStableRef<CurlBuffer>().get().append(bytes)
    return total
}

private fun curlEmptyReadCallback(
    buffer: CPointer<ByteVar>?,
    size: size_t,
    nmemb: size_t,
    userData: COpaquePointer?
): size_t = 0UL

private val CURL_MEMORY_READ_ABORT: size_t = 0x10000000u

private fun curlReadMemoryCallback(
    buffer: CPointer<ByteVar>?,
    size: size_t,
    nmemb: size_t,
    userData: COpaquePointer?
): size_t {
    if (buffer == null || userData == null) return CURL_MEMORY_READ_ABORT
    val context = userData.asStableRef<CurlMemoryReadContext>().get()
    val capacity = (size * nmemb).toInt()
    if (capacity <= 0) return 0UL
    if (context.failed) return CURL_MEMORY_READ_ABORT
    var copied = 0
    while (copied < capacity) {
        if (context.position >= context.buffer.size) {
            if (context.eof) break
            val next = try {
                context.nextChunk()
            } catch (error: Throwable) {
                context.failed = true
                context.failure = error
                return CURL_MEMORY_READ_ABORT
            }
            if (next == null) {
                context.eof = true
                context.buffer = ByteArray(0)
                context.position = 0
                break
            }
            context.buffer = next
            context.position = 0
            if (context.buffer.isEmpty()) continue
        }
        val available = context.buffer.size - context.position
        if (available <= 0) continue
        val copy = minOf(capacity - copied, available)
        context.buffer.usePinned { pinned ->
            memcpy(
                buffer + copied.toLong(),
                pinned.addressOf(context.position),
                copy.toULong(),
            )
        }
        context.position += copy
        copied += copy
        context.doneBytes += copy.toLong()
        val totalBytes = if (context.totalBytes >= 0L) context.totalBytes else context.doneBytes
        context.onProgress(context.doneBytes, totalBytes)
    }
    return copied.toULong()
}
