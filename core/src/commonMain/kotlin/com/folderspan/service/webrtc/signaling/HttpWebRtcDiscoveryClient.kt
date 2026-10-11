package com.folderspan.service.webrtc.signaling

import com.folderspan.service.http.client.createNoProxyHttpClient
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class HttpWebRtcDiscoveryClient(
    private val client: HttpClient = createNoProxyHttpClient { expectSuccess = false },
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun discoverHost(baseUrl: String): Result<SignalingDevice?> {
        return try {
            val response = client.get("${baseUrl.trimEnd('/')}/api/webrtc/signaling/discover") {
                accept(ContentType.Application.Json)
                headers.append(HttpHeaders.UserAgent, "FolderSpan-WebRTC")
            }
            if (!response.status.isSuccess()) {
                return Result.failure(IllegalStateException("HTTP ${response.status.value} ${response.status.description}"))
            }
            val decoded = json.decodeFromString<HttpWebRtcDiscoverResponse>(response.bodyAsText())
            if (decoded.code != null) {
                return Result.failure(IllegalStateException(decoded.message ?: decoded.code))
            }
            Result.success(decoded.host)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
