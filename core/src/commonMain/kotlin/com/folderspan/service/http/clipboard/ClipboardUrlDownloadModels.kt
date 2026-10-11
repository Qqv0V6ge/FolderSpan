package com.folderspan.service.http.clipboard

import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import strings.AppStrings

const val CLIPBOARD_DOWNLOAD_DEFAULT_RETRIES = 2
const val CLIPBOARD_DOWNLOAD_MIN_RETRIES = 0
const val CLIPBOARD_DOWNLOAD_MAX_RETRIES = 5
const val CLIPBOARD_DOWNLOAD_DEFAULT_THREADS = 1
const val CLIPBOARD_DOWNLOAD_MIN_THREADS = 1
const val CLIPBOARD_DOWNLOAD_MAX_THREADS = 8
const val CLIPBOARD_DOWNLOAD_MAX_COOKIE_LENGTH = 4_096
const val CLIPBOARD_DOWNLOAD_MAX_USER_AGENT_LENGTH = 512

data class ClipboardUrlDownloadPlatformCapabilities(
    val platformName: String,
    val canSetCookie: Boolean,
    val canSetUserAgent: Boolean,
    val canControlAutomaticHeaders: Boolean,
    val browserCredentialsOmitted: Boolean,
) {
    val isBrowser: Boolean
        get() = browserCredentialsOmitted
}

data class ClipboardUrlDownloadDraft(
    val rawText: String,
    val url: String,
    val targetSummary: String,
    val retries: String = CLIPBOARD_DOWNLOAD_DEFAULT_RETRIES.toString(),
    val cookie: String = "",
    val userAgent: String,
    val automaticHeaders: Boolean = true,
    val threads: String = CLIPBOARD_DOWNLOAD_DEFAULT_THREADS.toString(),
    val capabilities: ClipboardUrlDownloadPlatformCapabilities,
)

data class ClipboardUrlDownloadValidationErrors(
    val retries: ClipboardUrlDownloadFieldError? = null,
    val cookie: ClipboardUrlDownloadFieldError? = null,
    val userAgent: ClipboardUrlDownloadFieldError? = null,
    val threads: ClipboardUrlDownloadFieldError? = null,
) {
    val isEmpty: Boolean
        get() = retries == null && cookie == null && userAgent == null && threads == null
}

enum class ClipboardUrlDownloadFieldError {
    NotInteger,
    RetryRange,
    ThreadRange,
    CookieTooLong,
    CookieControlCharacters,
    CookieInvalidFormat,
    UserAgentRequired,
    UserAgentTooLong,
    UserAgentControlCharacters,
}

class ClipboardUrlDownloadTaskConfig internal constructor(
    val url: String,
    val rawText: String,
    val retries: Int,
    cookie: String,
    userAgent: String,
    val automaticHeaders: Boolean,
    val threads: Int,
    val capabilities: ClipboardUrlDownloadPlatformCapabilities,
) {
    private var taskCookie: String = cookie
    private var taskUserAgent: String = userAgent

    val cookie: String
        get() = taskCookie

    val userAgent: String
        get() = taskUserAgent

    fun clearSensitive() {
        taskCookie = ""
        taskUserAgent = ""
    }
}

sealed interface ClipboardUrlDownloadConfigResult {
    data class Valid(val config: ClipboardUrlDownloadTaskConfig) : ClipboardUrlDownloadConfigResult
    data class Invalid(val errors: ClipboardUrlDownloadValidationErrors) : ClipboardUrlDownloadConfigResult
}

fun ClipboardUrlDownloadDraft.validate(): ClipboardUrlDownloadConfigResult {
    val retryCount = retries.trim().toIntOrNull()
    val threadCount = threads.trim().toIntOrNull()
    val effectiveCookie = if (capabilities.canSetCookie) cookie else ""
    val effectiveUserAgent = if (capabilities.canSetUserAgent) userAgent else ""
    val errors = ClipboardUrlDownloadValidationErrors(
        retries = when {
            retryCount == null -> ClipboardUrlDownloadFieldError.NotInteger
            retryCount !in CLIPBOARD_DOWNLOAD_MIN_RETRIES..CLIPBOARD_DOWNLOAD_MAX_RETRIES ->
                ClipboardUrlDownloadFieldError.RetryRange
            else -> null
        },
        cookie = validateClipboardCookie(effectiveCookie),
        userAgent = validateClipboardUserAgent(effectiveUserAgent, capabilities.canSetUserAgent),
        threads = when {
            threadCount == null -> ClipboardUrlDownloadFieldError.NotInteger
            threadCount !in CLIPBOARD_DOWNLOAD_MIN_THREADS..CLIPBOARD_DOWNLOAD_MAX_THREADS ->
                ClipboardUrlDownloadFieldError.ThreadRange
            else -> null
        },
    )
    if (!errors.isEmpty) return ClipboardUrlDownloadConfigResult.Invalid(errors)
    return ClipboardUrlDownloadConfigResult.Valid(
        ClipboardUrlDownloadTaskConfig(
            url = url,
            rawText = rawText,
            retries = requireNotNull(retryCount),
            cookie = effectiveCookie.trim(),
            userAgent = effectiveUserAgent.trim(),
            automaticHeaders = automaticHeaders && capabilities.canControlAutomaticHeaders,
            threads = requireNotNull(threadCount),
            capabilities = capabilities,
        )
    )
}

fun validateClipboardCookie(value: String): ClipboardUrlDownloadFieldError? {
    if (value.isEmpty()) return null
    if (value.length > CLIPBOARD_DOWNLOAD_MAX_COOKIE_LENGTH) {
        return ClipboardUrlDownloadFieldError.CookieTooLong
    }
    if (value.any(::isUnsafeHeaderCharacter)) {
        return ClipboardUrlDownloadFieldError.CookieControlCharacters
    }
    val parts = value.split(';')
    if (parts.any { part ->
            val candidate = part.trim()
            val separator = candidate.indexOf('=')
            separator <= 0 || !candidate.substring(0, separator).all(::isHeaderTokenCharacter)
        }
    ) {
        return ClipboardUrlDownloadFieldError.CookieInvalidFormat
    }
    return null
}

fun validateClipboardUserAgent(
    value: String,
    required: Boolean = true,
): ClipboardUrlDownloadFieldError? {
    if (value.isBlank()) return if (required) ClipboardUrlDownloadFieldError.UserAgentRequired else null
    if (value.length > CLIPBOARD_DOWNLOAD_MAX_USER_AGENT_LENGTH) {
        return ClipboardUrlDownloadFieldError.UserAgentTooLong
    }
    if (value.any(::isUnsafeHeaderCharacter)) {
        return ClipboardUrlDownloadFieldError.UserAgentControlCharacters
    }
    return null
}

internal fun isUnsafeHeaderCharacter(char: Char): Boolean = char.code < 0x20 || char.code == 0x7f

private fun isHeaderTokenCharacter(char: Char): Boolean =
    char.code in 0x21..0x7e && char !in "()<>@,;:\"/[]?={} \t"

enum class ClipboardUrlDownloadError {
    InvalidSettings,
    InvalidUrl,
    RedirectLimit,
    RedirectRejected,
    AuthenticationRequired,
    HttpFailure,
    HtmlContent,
    MissingFileIdentity,
    MissingFileSize,
    TransportInterrupted,
    ReadTimeout,
    TlsFailure,
    ResponseChanged,
    RangeRejected,
    BrowserPolicy,
    Cancelled,
    StagingFailure,
    Unknown,
}

data class ClipboardUrlDownloadProgress(
    val bytesReceived: Long,
    val totalBytes: Long?,
    val attempt: Int,
    val maxAttempts: Int,
    val activeSegments: Int = 1,
    val fellBackToSingleThread: Boolean = false,
)

sealed interface ClipboardUrlDownloadState {
    data object Idle : ClipboardUrlDownloadState
    data class Configuring(
        val draft: ClipboardUrlDownloadDraft,
        val errors: ClipboardUrlDownloadValidationErrors = ClipboardUrlDownloadValidationErrors(),
    ) : ClipboardUrlDownloadState

    data class Inspecting(val targetSummary: String) : ClipboardUrlDownloadState
    data class Downloading(val progress: ClipboardUrlDownloadProgress) : ClipboardUrlDownloadState
    data class RetryWaiting(val attempt: Int, val delayMillis: Long) : ClipboardUrlDownloadState
    data class Succeeded(val displayName: String, val size: Long) : ClipboardUrlDownloadState
    data class Fallback(
        val rawText: String,
        val error: ClipboardUrlDownloadError?,
        val safeReason: String? = null,
    ) : ClipboardUrlDownloadState

    data object Cancelled : ClipboardUrlDownloadState
}

enum class ClipboardUrlDraftOpenResult {
    Opened,
    AlreadyRunning,
}

sealed interface ClipboardUrlDraftConfirmResult {
    data class Confirmed(val config: ClipboardUrlDownloadTaskConfig) : ClipboardUrlDraftConfirmResult
    data class Invalid(val errors: ClipboardUrlDownloadValidationErrors) : ClipboardUrlDraftConfirmResult
    data object NotConfiguring : ClipboardUrlDraftConfirmResult
}

class ClipboardUrlDownloadStateMachine {
    private val mutableState = MutableStateFlow<ClipboardUrlDownloadState>(ClipboardUrlDownloadState.Idle)
    private var activeConfig: ClipboardUrlDownloadTaskConfig? = null

    val state: StateFlow<ClipboardUrlDownloadState> = mutableState.asStateFlow()

    fun openDraft(draft: ClipboardUrlDownloadDraft): ClipboardUrlDraftOpenResult {
        if (isBusy(mutableState.value)) return ClipboardUrlDraftOpenResult.AlreadyRunning
        clearSensitiveConfig()
        mutableState.value = ClipboardUrlDownloadState.Configuring(draft)
        return ClipboardUrlDraftOpenResult.Opened
    }

    fun confirm(draft: ClipboardUrlDownloadDraft): ClipboardUrlDraftConfirmResult {
        if (mutableState.value !is ClipboardUrlDownloadState.Configuring) {
            return ClipboardUrlDraftConfirmResult.NotConfiguring
        }
        return when (val result = draft.validate()) {
            is ClipboardUrlDownloadConfigResult.Invalid -> {
                mutableState.value = ClipboardUrlDownloadState.Configuring(draft, result.errors)
                ClipboardUrlDraftConfirmResult.Invalid(result.errors)
            }

            is ClipboardUrlDownloadConfigResult.Valid -> {
                clearSensitiveConfig()
                activeConfig = result.config
                mutableState.value = ClipboardUrlDownloadState.Inspecting(draft.targetSummary)
                ClipboardUrlDraftConfirmResult.Confirmed(result.config)
            }
        }
    }

    fun downloading(progress: ClipboardUrlDownloadProgress) {
        if (activeConfig != null) mutableState.value = ClipboardUrlDownloadState.Downloading(progress)
    }

    fun retryWaiting(attempt: Int, delayMillis: Long) {
        if (activeConfig != null) {
            mutableState.value = ClipboardUrlDownloadState.RetryWaiting(attempt, delayMillis.coerceAtLeast(0L))
        }
    }

    fun succeeded(displayName: String, size: Long) {
        clearSensitiveConfig()
        mutableState.value = ClipboardUrlDownloadState.Succeeded(displayName, size.coerceAtLeast(0L))
    }

    fun fallback(rawText: String, error: ClipboardUrlDownloadError?, safeReason: String? = null) {
        clearSensitiveConfig()
        mutableState.value = ClipboardUrlDownloadState.Fallback(rawText, error, safeReason)
    }

    fun cancel() {
        clearSensitiveConfig()
        mutableState.value = ClipboardUrlDownloadState.Cancelled
    }

    fun dismiss() {
        clearSensitiveConfig()
        mutableState.value = ClipboardUrlDownloadState.Idle
    }

    internal fun activeConfigForTest(): ClipboardUrlDownloadTaskConfig? = activeConfig

    private fun clearSensitiveConfig() {
        activeConfig?.clearSensitive()
        activeConfig = null
    }

    private fun isBusy(value: ClipboardUrlDownloadState): Boolean = when (value) {
        is ClipboardUrlDownloadState.Configuring,
        is ClipboardUrlDownloadState.Inspecting,
        is ClipboardUrlDownloadState.Downloading,
        is ClipboardUrlDownloadState.RetryWaiting -> true

        ClipboardUrlDownloadState.Idle,
        is ClipboardUrlDownloadState.Succeeded,
        is ClipboardUrlDownloadState.Fallback,
        ClipboardUrlDownloadState.Cancelled -> false
    }
}

fun clipboardUrlTargetSummary(rawUrl: String): String {
    val url = runCatching { Url(rawUrl) }.getOrNull() ?: return AppStrings.ui_http_https_download
    val port = if (url.port == url.protocol.defaultPort) "" else ":${url.port}"
    return "${url.protocol.name}://${url.host}$port"
}
