package com.folderspan.pro.data.remote.api

import strings.AppStrings

import com.folderspan.pro.test.ChineseLocalizationTest
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.core.network.RequestSigningConfig
import com.folderspan.pro.core.network.RequestSigner
import com.folderspan.pro.core.network.ReplayableRequestContent
import com.folderspan.pro.core.network.RouteBuilder
import com.folderspan.pro.core.network.defaultJson
import com.folderspan.pro.core.network.installCommonConfig
import com.folderspan.pro.data.remote.dto.FeedbackSubmitRequest
import com.folderspan.pro.data.remote.dto.FeedbackUpdateRequest
import com.folderspan.pro.data.repository.DefaultFeedbackRepository
import com.folderspan.pro.domain.model.FeedbackListQuery
import com.folderspan.pro.domain.model.FeedbackUpload
import com.folderspan.pro.domain.model.FeedbackTransferProgress
import com.folderspan.pro.domain.model.MAX_FEEDBACK_ATTACHMENT_BYTES
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.header
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class FeedbackApiServiceTest : ChineseLocalizationTest() {
    @Test
    fun routeBuilderBuildsEveryFeedbackRouteFromConfig() {
        val routes = RouteBuilder(
            GatewayConfig(
                baseUrl = "https://example.test/root/",
                feedbackPrefix = "/custom/feedbacks/",
            ),
        )

        assertEquals("https://example.test/root/custom/feedbacks", routes.feedback())
        assertEquals("https://example.test/root/custom/feedbacks/categories", routes.feedback("/categories"))
        assertEquals("https://example.test/root/custom/feedbacks/ticket/read", routes.feedback("/ticket/read"))
    }

    @Test
    fun jsonOperationsUseExpectedMethodsRoutesQueriesBodiesAndAuth() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = FeedbackApiService(recordingJsonClient(requests), testConfig())

        service.categories()
        service.submit(
            FeedbackSubmitRequest("content", "1.2.3", "linux", type = "feedback"),
        )
        service.submit(
            FeedbackSubmitRequest("owned", "1.2.3", "linux", type = "suggestion"),
            token = "owned-token",
        )
        service.list(
            FeedbackListQuery(
                type = "suggestion",
                platform = "linux",
                startTimeEpochSeconds = 10,
                endTimeEpochSeconds = 20,
                page = 2,
                pageSize = 250,
            ),
            token = "list-token",
        )
        service.update(
            FeedbackUpdateRequest("ticket", "updated", "1.2.3", "linux"),
            token = "update-token",
        )
        service.delete(listOf("one", "two"), token = "delete-token")
        service.detail("ticket", token = "detail-token")
        service.markRead("ticket", token = "read-token")
        service.supplement("ticket", "more", token = "supplement-token")
        service.withdraw("ticket", "done", token = "withdraw-token")

        assertRequest(requests[0], HttpMethod.Get, "/api/v1/feedbacks/categories", null)
        assertRequest(requests[1], HttpMethod.Post, "/api/v1/feedbacks", null)
        assertNull(requests[1].headers[HttpHeaders.Authorization])
        assertContains(requests[1].body.text(), "\"content\":\"content\"")
        assertEquals("Bearer owned-token", requests[2].headers[HttpHeaders.Authorization])

        val list = requests[3]
        assertRequest(list, HttpMethod.Get, "/api/v1/feedbacks", "list-token")
        assertEquals("suggestion", list.url.parameters["type"])
        assertEquals("linux", list.url.parameters["platform"])
        assertEquals("10", list.url.parameters["startTime"])
        assertEquals("20", list.url.parameters["endTime"])
        assertEquals("2", list.url.parameters["page"])
        assertEquals("100", list.url.parameters["pageSize"])

        assertRequest(requests[4], HttpMethod.Put, "/api/v1/feedbacks", "update-token")
        assertContains(requests[4].body.text(), "\"uuid\":\"ticket\"")
        assertRequest(requests[5], HttpMethod.Delete, "/api/v1/feedbacks", "delete-token")
        assertContains(requests[5].body.text(), "\"uuids\":[\"one\",\"two\"]")
        assertRequest(requests[6], HttpMethod.Get, "/api/v1/feedbacks/ticket", "detail-token")
        assertRequest(requests[7], HttpMethod.Post, "/api/v1/feedbacks/ticket/read", "read-token")
        assertRequest(requests[8], HttpMethod.Post, "/api/v1/feedbacks/ticket/supplements", "supplement-token")
        assertContains(requests[8].body.text(), "\"content\":\"more\"")
        assertRequest(requests[9], HttpMethod.Post, "/api/v1/feedbacks/ticket/withdraw", "withdraw-token")
        assertContains(requests[9].body.text(), "\"reason\":\"done\"")
    }

    @Test
    fun uploadUsesReplayableSignedMultipartAndDownloadParsesSafeFilename() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(
            MockEngine { request ->
                requests += request
                if (request.url.encodedPath.endsWith("/download")) {
                    respond(
                        content = byteArrayOf(1, 2, 3),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("text/plain"),
                            HttpHeaders.ContentDisposition to listOf("attachment; filename*=UTF-8''..%2Fnotes.log"),
                            HttpHeaders.ContentLength to listOf("3"),
                        ),
                    )
                } else {
                    respond(
                        content = """{"code":0,"data":{"uuid":"attachment","originalName":"notes.log","contentType":"text/plain","size":3,"createdAt":"now"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            },
        ) {
            installCommonConfig(
                requestSigningConfig = RequestSigningConfig(
                    timestampProvider = { "1000" },
                    nonceProvider = { "nonce" },
                ),
                useProxy = false,
            )
        }
        val service = FeedbackApiService(client, testConfig())
        val uploadProgress = mutableListOf<FeedbackTransferProgress>()
        val downloadProgress = mutableListOf<FeedbackTransferProgress>()

        val upload = service.upload(
            uuid = "ticket",
            upload = FeedbackUpload("../notes\".log", "text/plain", byteArrayOf(1, 2, 3)),
            token = "upload-token",
            onProgress = { uploadProgress += it },
        )
        val download = service.download("ticket", "download", "download-token") {
            downloadProgress += it
        }

        assertIs<ApiResult.Success<*>>(upload)
        val uploadRequest = requests[0]
        assertRequest(uploadRequest, HttpMethod.Post, "/api/v1/feedbacks/ticket/attachments", "upload-token")
        val uploadBody = assertIs<ReplayableRequestContent>(uploadRequest.body)
        val uploadBytes = uploadBody.replayableBodyBytes()
        assertTrue(uploadRequest.body.contentType?.match(ContentType.MultiPart.FormData) == true)
        assertContains(uploadBytes.decodeToString(), "name=\"file\"; filename=\"notes\\\".log\"")
        assertContains(uploadBytes.decodeToString(), "Content-Type: text/plain")
        assertEquals(
            RequestSigner.sign(
                method = "POST",
                path = "/api/v1/feedbacks/ticket/attachments",
                query = emptyList(),
                body = uploadBytes,
                timestamp = "1000",
                nonce = "nonce",
                secret = "upload-token",
            ).signature,
            uploadRequest.headers["X-Signature"],
        )

        val payload = assertIs<ApiResult.Success<*>>(download).data
        assertEquals("notes.log", (payload as com.folderspan.pro.domain.model.FeedbackDownload).fileName)
        assertEquals("text/plain", payload.contentType)
        assertTrue(payload.bytes.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(FeedbackTransferProgress(0, 3), uploadProgress.first())
        assertEquals(FeedbackTransferProgress(3, 3), uploadProgress.last())
        assertEquals(FeedbackTransferProgress(0, 3), downloadProgress.first())
        assertEquals(FeedbackTransferProgress(3, 3), downloadProgress.last())
        assertRequest(
            requests[1],
            HttpMethod.Get,
            "/api/v1/feedbacks/ticket/attachments/download",
            "download-token",
        )
    }

    @Test
    fun uploadWritesCompleteMultipartThroughRealJvmEngine() = runTest {
        val receivedBody = AtomicReference<ByteArray>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/feedbacks/ticket/attachments") { exchange ->
                receivedBody.set(exchange.requestBody.readAllBytes())
                val response = """{"code":0,"data":true}""".encodeToByteArray()
                exchange.responseHeaders.add(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                exchange.sendResponseHeaders(HttpStatusCode.OK.value, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        val client = HttpClient {
            install(ContentNegotiation) { json(defaultJson) }
        }
        val fileBytes = ByteArray(70_000) { (it % 127).toByte() }

        try {
            val result = FeedbackApiService(
                client = client,
                config = GatewayConfig(baseUrl = "http://127.0.0.1:${server.address.port}"),
            ).upload(
                uuid = "ticket",
                upload = FeedbackUpload("trace.log", "text/plain", fileBytes),
                token = "upload-token",
            )

            assertIs<ApiResult.Success<*>>(result, result.toString())
            val multipartBody = receivedBody.get()
            val headerEnd = multipartBody.decodeToString().indexOf("\r\n\r\n")
            assertTrue(headerEnd >= 0, "Missing multipart file headers")
            val fileStart = headerEnd + 4
            assertTrue(
                multipartBody.copyOfRange(fileStart, fileStart + fileBytes.size).contentEquals(fileBytes),
                "Multipart file bytes were not sent completely",
            )
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun deleteAttachmentUsesExpectedRouteAndAuth() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = FeedbackApiService(recordingJsonClient(requests), testConfig())

        val result = service.deleteAttachment(
            uuid = "ticket",
            attachmentUuid = "attachment",
            token = "delete-attachment-token",
        )

        assertIs<ApiResult.Success<*>>(result)
        assertRequest(
            requests.single(),
            HttpMethod.Delete,
            "/api/v1/feedbacks/ticket/attachments/attachment",
            "delete-attachment-token",
        )
    }

    @Test
    fun downloadFailurePreservesHttpStatusForSessionHandling() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"code":40100,"msg":"expired"}""",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) { json(defaultJson) }
            defaultRequest { header(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
        }

        val result = FeedbackApiService(client, testConfig()).download("ticket", "file", "expired")

        assertEquals(HttpStatusCode.Unauthorized.value, assertIs<ApiResult.Failure>(result).statusCode)
    }

    @Test
    fun downloadResolvesMetadataUrlBeforeReturningFileBytes() = runTest {
        val gatewayRequests = mutableListOf<HttpRequestData>()
        val attachmentRequests = mutableListOf<HttpRequestData>()
        val fileBytes = byteArrayOf(1, 2, 3, 4)
        val gatewayClient = HttpClient(
            MockEngine { request ->
                gatewayRequests += request
                respond(
                    content = AppStrings.ui_test_feedback_api_file_upload_response_json,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            installCommonConfig(
                requestSigningConfig = RequestSigningConfig(
                    timestampProvider = { "1000" },
                    nonceProvider = { "nonce" },
                ),
                useProxy = false,
            )
        }
        val attachmentClient = HttpClient(
            MockEngine { request ->
                attachmentRequests += request
                respond(
                    content = fileBytes,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("image/png"),
                        HttpHeaders.ContentLength to listOf(fileBytes.size.toString()),
                    ),
                )
            },
        )
        val progress = mutableListOf<FeedbackTransferProgress>()

        val result = FeedbackApiService(
            client = gatewayClient,
            config = testConfig(),
            attachmentDownloadClient = attachmentClient,
        ).download(
            "ticket",
            "file",
            "token",
        ) { progress += it }

        val download = assertIs<com.folderspan.pro.domain.model.FeedbackDownload>(
            assertIs<ApiResult.Success<*>>(result).data,
        )
        assertEquals(AppStrings.ui_test_feedback_api_service_creenshot_png, download.fileName)
        assertEquals("image/png", download.contentType)
        assertTrue(download.bytes.contentEquals(fileBytes))
        assertRequest(
            gatewayRequests.single(),
            HttpMethod.Get,
            "/api/v1/feedbacks/ticket/attachments/file",
            "token",
        )
        val attachmentRequest = attachmentRequests.single()
        assertEquals("objects.example.test", attachmentRequest.url.host)
        assertNull(attachmentRequest.headers[HttpHeaders.Authorization])
        assertNull(attachmentRequest.headers[HttpHeaders.Host])
        assertNull(attachmentRequest.headers["X-Signature"])
        assertNull(attachmentRequest.headers["X-Device-Key"])
        assertEquals(FeedbackTransferProgress(0, 4), progress.first())
        assertEquals(FeedbackTransferProgress(4, 4), progress.last())
    }

    @Test
    fun downloadRejectsFileWhenMetadataSizeDoesNotMatch() = runTest {
        val gatewayClient = metadataDownloadClient(size = 4)
        val attachmentClient = HttpClient(
            MockEngine { respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK) },
        )

        val result = FeedbackApiService(
            client = gatewayClient,
            config = testConfig(),
            attachmentDownloadClient = attachmentClient,
        ).download("ticket", "file", "token")

        assertIs<ApiResult.Failure>(result)
    }

    @Test
    fun downloadPreservesObjectStorageHttpStatus() = runTest {
        val gatewayClient = metadataDownloadClient(size = 4)
        val attachmentClient = HttpClient(
            MockEngine { respond("denied", HttpStatusCode.Forbidden) },
        )

        val result = FeedbackApiService(
            client = gatewayClient,
            config = testConfig(),
            attachmentDownloadClient = attachmentClient,
        ).download("ticket", "file", "token")

        assertEquals(HttpStatusCode.Forbidden.value, assertIs<ApiResult.Failure>(result).statusCode)
    }

    @Test
    fun downloadPreservesUnknownTotalAndReportsMonotonicTerminalBytes() = runTest {
        val bytes = ByteArray(70_000) { (it % 127).toByte() }
        val progress = mutableListOf<FeedbackTransferProgress>()
        val client = HttpClient(
            MockEngine {
                respond(
                    content = bytes,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
                )
            },
        )

        val result = FeedbackApiService(client, testConfig()).download(
            "ticket",
            "file",
            "token",
        ) { progress += it }

        assertTrue(assertIs<ApiResult.Success<*>>(result).data is com.folderspan.pro.domain.model.FeedbackDownload)
        assertTrue(progress.all { it.totalBytes == null })
        assertEquals(bytes.size.toLong(), progress.last().transferredBytes)
        assertEquals(progress.sortedBy { it.transferredBytes }, progress)
    }

    @Test
    fun downloadRejectsDeclaredContentLengthAboveAttachmentLimitBeforeCollection() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = byteArrayOf(1),
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentLength,
                        (10L * 1024L * 1024L + 1L).toString(),
                    ),
                )
            },
        )

        val result = FeedbackApiService(client, testConfig()).download("ticket", "file", "token")

        assertIs<ApiResult.Failure>(result)
    }

    @Test
    fun uploadAndDownloadProgressCallbacksRemainCancellable() = runTest {
        var requests = 0
        val client = HttpClient(
            MockEngine {
                requests++
                respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
            },
        )
        val service = FeedbackApiService(client, testConfig())

        assertFailsWith<CancellationException> {
            service.upload(
                "ticket",
                FeedbackUpload("trace.log", "text/plain", byteArrayOf(1)),
                "token",
            ) { throw CancellationException("cancel upload") }
        }
        assertEquals(0, requests)

        assertFailsWith<CancellationException> {
            service.download("ticket", "file", "token") { progress ->
                if (progress.transferredBytes > 0) throw CancellationException("cancel download")
            }
        }
        assertEquals(1, requests)
    }

    @Test
    fun contentDispositionParserPrefersEncodedAndRemovesPathsAndControls() {
        assertEquals(
            AppStrings.ui_test_index_template_report_1_pdf,
            parseFeedbackContentDispositionFileName(
                "attachment; filename=ignored.txt; filename*=UTF-8''folder%2F%E6%8A%A5%E5%91%8A%201.pdf",
            ),
        )
        assertEquals("safe.log", sanitizeFeedbackFileName("../bad\u0000/safe.log"))
        assertNull(sanitizeFeedbackFileName(".."))
    }

    @Test
    fun repositoryRejectsChangedUnsupportedExtensionBeforeRequestConstruction() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = DefaultFeedbackRepository(
            FeedbackApiService(recordingJsonClient(requests), testConfig()),
        )

        val result = repository.upload(
            uuid = "ticket",
            upload = FeedbackUpload("trace.zip", "application/zip", byteArrayOf(1)),
            token = "token",
        )

        assertIs<ApiResult.Failure>(result)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun repositoryRejectsActualBytesAboveLimitBeforeRequestConstruction() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = DefaultFeedbackRepository(
            FeedbackApiService(recordingJsonClient(requests), testConfig()),
        )

        val result = repository.upload(
            uuid = "ticket",
            upload = FeedbackUpload(
                "trace.log",
                "text/plain",
                ByteArray((MAX_FEEDBACK_ATTACHMENT_BYTES + 1).toInt()),
            ),
            token = "token",
        )

        assertIs<ApiResult.Failure>(result)
        assertTrue(requests.isEmpty())
    }

    private fun testConfig() = GatewayConfig(baseUrl = "https://example.test")

    private fun metadataDownloadClient(size: Long): HttpClient = HttpClient(
        MockEngine {
            respond(
                content = """{"code":0,"msg":"OK","data":{"originalName":"file.png","contentType":"image/png","size":$size,"url":"https://objects.example.test/report/file.png"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        },
    )

    private fun recordingJsonClient(requests: MutableList<HttpRequestData>): HttpClient =
        HttpClient(
            MockEngine { request ->
                requests += request
                respond(
                    content = """{"code":0,"data":true}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) { json(defaultJson) }
            defaultRequest { header(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
        }

    private fun assertRequest(
        request: HttpRequestData,
        method: HttpMethod,
        path: String,
        token: String?,
    ) {
        assertEquals(method, request.method, "Unexpected request: ${request.method.value} ${request.url}")
        assertEquals(path, request.url.encodedPath)
        if (token != null) assertEquals("Bearer $token", request.headers[HttpHeaders.Authorization])
    }
}

private fun OutgoingContent.text(): String = when (this) {
    is TextContent -> text
    is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
    else -> toString()
}
