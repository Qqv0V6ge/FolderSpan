package com.folderspan.pro.data.remote.api

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.core.network.ReplayableRequestContent
import com.folderspan.pro.core.network.cache.ApiCachePolicy
import com.folderspan.pro.core.network.cache.ApiResponseCacheStore
import com.folderspan.pro.core.network.cache.FileApiResponseCacheStore
import com.folderspan.pro.data.remote.dto.*
import com.folderspan.pro.domain.model.ProfileAvatarUpload
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.util.generateNonceBlocking
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully

/**
 * user-api wrapper for requests defined under http/user-api.
 */
class UserApiService(
    client: HttpClient,
    config: GatewayConfig = GatewayConfig(),
    cacheStore: ApiResponseCacheStore = FileApiResponseCacheStore(),
) : BaseApiService(client, config, cacheStore) {

    suspend fun ping(): ApiResult<Unit> = safeCall {
        val response = client.get(routes.user("/ping"))
        check(response.status.value in 200..299) {
            "Ping failed (${response.status.value})"
        }
    }

    suspend fun register(
        request: RegisterRequest,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.user("/register")
        val body = cacheBody(RegisterRequest.serializer(), request)
        return jsonCall(
            cachePolicy = cachePolicy?.withoutFallback(),
            cacheKey = cachePolicy?.let { cacheKey(method = "POST", url = url, body = body) },
        ) {
            client.post(url) {
                setBody(request)
            }
        }
    }

    suspend fun login(
        request: LoginRequest,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.user("/login")
        val body = cacheBody(LoginRequest.serializer(), request)
        return jsonCall(
            cachePolicy = cachePolicy?.withoutFallback(),
            cacheKey = cachePolicy?.let { cacheKey(method = "POST", url = url, body = body) },
        ) {
            client.post(url) {
                setBody(request)
            }
        }
    }

    suspend fun refreshToken(
        refreshToken: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.user("/token/refresh")
        val request = RefreshTokenRequest(refreshToken)
        val body = cacheBody(RefreshTokenRequest.serializer(), request)
        return jsonCall(
            cachePolicy = cachePolicy?.withoutFallback(),
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "POST",
                    url = url,
                    body = body,
                    userScope = refreshToken,
                )
            },
        ) {
            client.post(url) {
                setBody(request)
            }
        }
    }

    suspend fun me(
        token: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.user("/profile")
        return jsonCall(
            cachePolicy = cachePolicy,
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "GET",
                    url = url,
                    userScope = token,
                )
            },
        ) {
            client.get(url) {
                auth(token)
            }
        }
    }

    suspend fun cachedMe(
        token: String,
        cachePolicy: ApiCachePolicy,
    ): JsonResult? {
        val url = routes.user("/profile")
        val payload = cachedJsonPayload(
            cachePolicy = cachePolicy,
            cacheKey = cacheKey(
                method = "GET",
                url = url,
                userScope = token,
            ),
        ) ?: return null
        return ApiResult.Success(payload)
    }

    suspend fun profile(
        uuid: String,
        token: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.user("/profile/$uuid")
        return jsonCall(
            cachePolicy = cachePolicy,
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "GET",
                    url = url,
                    userScope = token,
                )
            },
        ) {
            client.get(url) {
                auth(token)
            }
        }
    }

    suspend fun listDevices(
        token: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.user("/devices")
        return jsonCall(
            cachePolicy = cachePolicy,
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "GET",
                    url = url,
                    userScope = token,
                )
            },
        ) {
            client.get(url) {
                auth(token)
            }
        }
    }

    suspend fun deleteDevice(
        id: Long,
        token: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.user("/devices/$id")
        return jsonCall(
            cachePolicy = cachePolicy?.withoutFallback(),
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "DELETE",
                    url = url,
                    userScope = token,
                )
            },
        ) {
            client.delete(url) {
                auth(token)
            }
        }
    }

    suspend fun updateProfile(
        request: UpdateProfileRequest,
        avatar: ProfileAvatarUpload? = null,
        token: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.user("/profile")
        val multipartContent = avatar?.let { request.toReplayableProfileMultipartContent(it) }
        val body = multipartContent?.replayableBodyBytes()
            ?: cacheBody(UpdateProfileRequest.serializer(), request)
        return jsonCall(
            cachePolicy = cachePolicy?.withoutFallback(),
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "PUT",
                    url = url,
                    body = body,
                    userScope = token,
                )
            },
        ) {
            client.put(url) {
                auth(token)
                if (multipartContent == null) {
                    setBody(request)
                } else {
                    setBody(multipartContent)
                }
            }
        }
    }

    suspend fun uploadAvatar(upload: ProfileAvatarUpload, token: String): JsonResult = jsonCall {
        client.put(routes.user("/profile/avatar")) {
            auth(token)
            setBody(upload.toReplayableAvatarMultipartContent())
        }
    }

    suspend fun removeAvatar(token: String): JsonResult = jsonCall {
        client.delete(routes.user("/profile/avatar")) {
            auth(token)
        }
    }

    suspend fun withdrawProfileReview(token: String): JsonResult = jsonCall {
        client.delete(routes.user("/profile/review")) {
            auth(token)
        }
    }

    suspend fun forgotPassword(
        email: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.user("/password/forgot")
        val request = ForgotPasswordRequest(email)
        val body = cacheBody(ForgotPasswordRequest.serializer(), request)
        return jsonCall(
            cachePolicy = cachePolicy?.withoutFallback(),
            cacheKey = cachePolicy?.let { cacheKey(method = "POST", url = url, body = body) },
        ) {
            client.post(url) {
                setBody(request)
            }
        }
    }

    suspend fun resetPassword(
        request: ResetPasswordRequest,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.user("/password/reset")
        val body = cacheBody(ResetPasswordRequest.serializer(), request)
        return jsonCall(
            cachePolicy = cachePolicy?.withoutFallback(),
            cacheKey = cachePolicy?.let { cacheKey(method = "POST", url = url, body = body) },
        ) {
            client.post(url) {
                setBody(request)
            }
        }
    }

    suspend fun changePassword(
        request: ChangePasswordRequest,
        token: String,
        cachePolicy: ApiCachePolicy? = null,
    ): JsonResult {
        val url = routes.user("/password")
        val body = cacheBody(ChangePasswordRequest.serializer(), request)
        return jsonCall(
            cachePolicy = cachePolicy?.withoutFallback(),
            cacheKey = cachePolicy?.let {
                cacheKey(
                    method = "PUT",
                    url = url,
                    body = body,
                    userScope = token,
                )
            },
        ) {
            client.put(url) {
                auth(token)
                setBody(request)
            }
        }
    }

    private fun ApiCachePolicy.withoutFallback(): ApiCachePolicy =
        if (allowFallback) copy(allowFallback = false) else this
}

private fun ProfileAvatarUpload.toReplayableAvatarMultipartContent(): ReplayableProfileMultipartContent =
    replayableProfileMultipartContent(avatar = this)

private fun UpdateProfileRequest.toReplayableProfileMultipartContent(
    avatar: ProfileAvatarUpload,
): ReplayableProfileMultipartContent = replayableProfileMultipartContent(
    textFields = listOfNotNull(
        name?.let { "name" to it },
        signature?.let { "signature" to it },
        locale?.let { "locale" to it },
    ),
    avatar = avatar,
)

private fun replayableProfileMultipartContent(
    textFields: List<Pair<String, String>> = emptyList(),
    avatar: ProfileAvatarUpload,
): ReplayableProfileMultipartContent {
    val boundaryNonce = generateNonceBlocking()
        .filter { character -> character.isLetterOrDigit() }
        .take(40)
    require(boundaryNonce.isNotEmpty()) { "Unable to generate multipart boundary" }
    val boundary = "FolderSpanBoundary$boundaryNonce"
    val parsedContentType = runCatching { ContentType.parse(avatar.contentType) }
        .getOrDefault(ContentType.Application.OctetStream)
    val fileName = when (avatar.contentType.lowercase()) {
        "image/png" -> "avatar.png"
        "image/webp" -> "avatar.webp"
        else -> "avatar.jpg"
    }
    val parts = buildList {
        textFields.forEach { (fieldName, value) ->
            add(
                buildString {
                    append("--")
                    append(boundary)
                    append("\r\n")
                    append("Content-Disposition: form-data; name=\"")
                    append(fieldName)
                    append("\"\r\n")
                    append("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
                    append(value)
                    append("\r\n")
                }.encodeToByteArray(),
            )
        }
        add(
            buildString {
                append("--")
                append(boundary)
                append("\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                append(fileName)
                append("\"\r\n")
                append("Content-Type: ")
                append(parsedContentType)
                append("\r\n\r\n")
            }.encodeToByteArray(),
        )
        add(avatar.bytes)
        add("\r\n--$boundary--\r\n".encodeToByteArray())
    }
    val payload = ByteArray(parts.sumOf { it.size })
    var destinationOffset = 0
    parts.forEach { part ->
        part.copyInto(payload, destinationOffset = destinationOffset)
        destinationOffset += part.size
    }
    return ReplayableProfileMultipartContent(
        payload = payload,
        contentType = ContentType.MultiPart.FormData.withParameter("boundary", boundary),
    )
}

private class ReplayableProfileMultipartContent(
    private val payload: ByteArray,
    override val contentType: ContentType,
) : OutgoingContent.WriteChannelContent(), ReplayableRequestContent {
    override val contentLength: Long = payload.size.toLong()

    override fun replayableBodyBytes(): ByteArray = payload

    override suspend fun writeTo(channel: ByteWriteChannel) {
        channel.writeFully(payload)
    }
}
