package com.folderspan.routes

import strings.AppStrings

import com.folderspan.createSettings
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.exception.EmptyDataException
import com.folderspan.exception.ParameterErrorException
import com.folderspan.service.bookmark.DeviceBookmarkService
import com.folderspan.service.account.AccountDeviceNonceReplayCache
import com.folderspan.service.account.AccountDeviceTrustRegistry
import com.folderspan.service.file.DeviceFileCopyService
import com.folderspan.service.file.DeviceFileService
import com.folderspan.service.http.FILE_SHARE_ACCESS_KEY_HEADER
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_MAX_LENGTH
import com.folderspan.service.http.constantTimeFileShareAccessKeyEquals
import com.folderspan.service.http.readFileShareAccessKeyConfig
import com.folderspan.service.http.server.AdvertisedHostProvider
import com.folderspan.service.http.server.DefaultAdvertisedHostProvider
import com.folderspan.service.path.DevicePathService
import com.folderspan.service.webrtc.signaling.HttpWebRtcSignalingHub
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.main.DeviceState
import com.folderspan.ui.state.main.MainState
import com.folderspan.utils.LogKit
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class RawHttpApiDispatcher(
    internal val settings: Settings = createSettings(),
    internal val advertisedHostProvider: AdvertisedHostProvider = DefaultAdvertisedHostProvider,
) : KoinComponent {
    internal val database by inject<FolderSpanDatabase>()
    internal val deviceState by inject<DeviceState>()
    internal val deviceCertificateState by inject<DeviceCertificateState>()
    internal val mainState by inject<MainState>()
    internal val fileState by inject<FileState>()
    internal val fileShareState by inject<FileShareState>()
    internal val fileBookmarkState by inject<com.folderspan.ui.state.file.FileBookmarkState>()
    internal val accountDeviceTrustRegistry by inject<AccountDeviceTrustRegistry>()
    internal val accountDeviceNonceReplayCache by inject<AccountDeviceNonceReplayCache>()
    internal val rawHttpWebRtcSignalingHub = HttpWebRtcSignalingHub()

    internal val fileService by lazy {
        DeviceFileService(
            deviceCertificateState,
            maxByteRangeLength = DEVICE_DIRECT_MAX_LENGTH,
        )
    }
    internal val fileCopyService by lazy { DeviceFileCopyService(deviceCertificateState) }
    internal val pathService by lazy { DevicePathService(deviceCertificateState) }
    internal val bookmarkService by lazy {
        DeviceBookmarkService(deviceCertificateState) {
            fileBookmarkState.load()
        }
    }

    suspend fun dispatch(request: RawHttpRequest): RawHttpResponse {
        if (request.method.equals("OPTIONS", ignoreCase = true)) {
            if (isRejectedPrivateWebRequest(request)) {
                return RawHttpResponse.bytes(403, headers = deviceApiCorsHeaders(this))
            }
            return RawHttpResponse.bytes(204, headers = deviceApiCorsHeaders(this, request))
        }
        if (isRejectedPrivateWebRequest(request)) {
            return RawHttpResponse.bytes(403, headers = deviceApiCorsHeaders(this))
        }
        val accessKeyConfig = settings.readFileShareAccessKeyConfig()
        if (accessKeyConfig.enabled &&
            (!accessKeyConfig.hasValidValue() ||
                !constantTimeFileShareAccessKeyEquals(
                    expected = accessKeyConfig.value,
                    actual = request.header(FILE_SHARE_ACCESS_KEY_HEADER),
                ))
        ) {
            return RawHttpResponse.bytes(403, headers = deviceApiCorsHeaders(this, request))
        }
        if (!request.method.equals("POST", ignoreCase = true)) {
            return protobufFailure(405, IllegalArgumentException("Method Not Allowed"))
                .withDeviceApiCors(this, request)
        }
        if (request.isEncryptedHttpPayload()) {
            return protobufFailure(
                400,
                ParameterErrorException(AppStrings.ui_device_api_http_encrypted_payload_unsupported),
            )
                .withDeviceApiCors(this, request)
        }
        return try {
            val response = when {
                request.path.startsWith("/api/share/") -> handleShare(request)
                else -> protobufFailure(404, EmptyDataException())
            }
            response.withDeviceApiCors(this, request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            LogKit.e("${AppStrings.ui_raw_http_api_handler_failed}: ${error.message}", error)
            protobufFailure(500, error).withDeviceApiCors(this, request)
        }
    }
}

internal fun RawHttpApiDispatcher.hasRequiredFileShareAccessKey(request: RawHttpRequest): Boolean {
    val config = settings.readFileShareAccessKeyConfig()
    return config.enabled &&
        config.hasValidValue() &&
        constantTimeFileShareAccessKeyEquals(
            expected = config.value,
            actual = request.header(FILE_SHARE_ACCESS_KEY_HEADER),
        )
}
