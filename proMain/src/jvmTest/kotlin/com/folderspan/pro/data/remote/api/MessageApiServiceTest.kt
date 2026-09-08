package com.folderspan.pro.data.remote.api

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.core.network.defaultJson
import com.folderspan.pro.data.mapper.toAnnouncementPageResult
import com.folderspan.pro.data.mapper.toAppUpdatePageResult
import com.folderspan.pro.data.mapper.toAppUpdateOrNull
import com.folderspan.pro.data.mapper.toDomainOrNull
import com.folderspan.pro.data.remote.dto.MessageEnvelopeDto
import com.folderspan.pro.data.remote.dto.MessageItemDto
import com.folderspan.pro.data.remote.dto.MessageListDataDto
import com.folderspan.pro.data.repository.DefaultAnnouncementRepository
import com.folderspan.pro.data.repository.DefaultAppUpdateRepository
import com.folderspan.pro.domain.model.ANNOUNCEMENT_LIST_TYPE
import com.folderspan.pro.domain.model.AnnouncementQuery
import com.folderspan.pro.domain.model.NotificationActionKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageApiServiceTest {
    @Test
    fun listMessagesUsesGatewayCollectionPathWithoutAuthorization() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = MessageApiService(recordingClient(requests), testConfig())

        val result = service.listMessages(
            AnnouncementQuery(
                platform = "linux",
                page = 2,
                pageSize = 250,
            ),
        )

        assertIs<ApiResult.Success<*>>(result, result.toString())
        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/api/v1/messages", request.url.encodedPath)
        assertEquals(ANNOUNCEMENT_LIST_TYPE, request.url.parameters["type"])
        assertEquals("linux", request.url.parameters["platform"])
        assertEquals("2", request.url.parameters["page"])
        assertEquals("100", request.url.parameters["pageSize"])
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun listMessagesTreatsNotFoundAsEmptyPage() = runTest {
        val client = jsonClient {
            respond(
                content = "not found",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }

        val result = MessageApiService(client, testConfig()).listMessages(
            AnnouncementQuery(platform = "android", page = 3, pageSize = 25),
        )

        val envelope = assertIs<ApiResult.Success<*>>(result).data as MessageEnvelopeDto<*>
        val data = envelope.data as MessageListDataDto
        assertEquals(emptyList(), data.list)
        assertEquals(3, data.page)
        assertEquals(25, data.pageSize)

        val page = DefaultAnnouncementRepository(
            MessageApiService(client, testConfig()),
        ).list(AnnouncementQuery(platform = "android", page = 3, pageSize = 25))
        val emptyPage = assertIs<ApiResult.Success<*>>(page).data as com.folderspan.pro.domain.model.AnnouncementPage
        assertEquals(emptyList(), emptyPage.items)
        assertEquals(3, emptyPage.page)
    }

    @Test
    fun listMessagesPropagatesOtherFailures() = runTest {
        val client = jsonClient {
            respond(
                content = "boom",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }

        val result = MessageApiService(client, testConfig()).listMessages(
            AnnouncementQuery(platform = "ios"),
        )
        assertIs<ApiResult.Failure>(result)
    }

    @Test
    fun mapsSecondsAndMillisecondsAndPreservesContent() {
        val seconds = MessageItemDto(
            id = 1,
            title = " Title ",
            content = "  keep  ",
            publishedAt = 1_700_000_000L,
            link = "https://example.test/a",
        ).toDomainOrNull()
        val milliseconds = MessageItemDto(
            id = 2,
            content = "[Docs](https://example.test)",
            publishedAt = 1_700_000_000_123L,
            link = "",
        ).toDomainOrNull()

        requireNotNull(seconds)
        assertEquals("Title", seconds.title)
        assertEquals("  keep  ", seconds.content)
        assertEquals(1_700_000_000_000L, seconds.publishedAtEpochMillis)
        assertEquals(NotificationActionKind.Url, seconds.actions.single().kind)
        assertEquals("https://example.test/a", seconds.actions.single().url)

        requireNotNull(milliseconds)
        assertEquals("[Docs](https://example.test)", milliseconds.content)
        assertEquals(1_700_000_000_123L, milliseconds.publishedAtEpochMillis)
        assertEquals(emptyList(), milliseconds.actions)
        assertNull(MessageItemDto(id = 0).toDomainOrNull())
    }

    @Test
    fun mapsNonHttpLinkAsUnavailableUrlAction() {
        val announcement = MessageItemDto(
            id = 9,
            link = "javascript:alert(1)",
        ).toDomainOrNull()

        val action = requireNotNull(announcement).actions.single()
        assertEquals(NotificationActionKind.Url, action.kind)
        assertEquals("javascript:alert(1)", action.url)
        assertTrue(action.label.isNotBlank())
    }

    @Test
    fun latestAppUpdateUsesUpdatesPathWithoutAuthorization() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = MessageApiService(recordingLatestClient(requests), testConfig())

        val result = service.latestAppUpdate("linux")

        assertIs<ApiResult.Success<*>>(result, result.toString())
        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/api/v1/updates/latest", request.url.encodedPath)
        assertEquals("linux", request.url.parameters["platform"])
        assertEquals("release", request.url.parameters["channel"])
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun latestAppUpdateSendsRequestedChannel() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = MessageApiService(recordingLatestClient(requests), testConfig())

        val result = service.latestAppUpdate("windows", "beta")

        assertIs<ApiResult.Success<*>>(result, result.toString())
        assertEquals("beta", requests.single().url.parameters["channel"])
    }

    @Test
    fun latestAppUpdateNormalizesUnknownChannelToRelease() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = MessageApiService(recordingLatestClient(requests), testConfig())

        service.latestAppUpdate("linux", "nightly")

        assertEquals("release", requests.single().url.parameters["channel"])
    }

    @Test
    fun latestAppUpdateTreatsNotFoundAndNullDataAsEmpty() = runTest {
        val notFound = jsonClient {
            respond(
                content = "not found",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }
        val notFoundResult = MessageApiService(notFound, testConfig()).latestAppUpdate("android")
        val notFoundEnvelope = assertIs<ApiResult.Success<*>>(notFoundResult).data as MessageEnvelopeDto<*>
        assertNull(notFoundEnvelope.data)

        val nullData = jsonClient {
            respond(
                content = """{"code":0,"data":null,"msg":"OK"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val nullResult = MessageApiService(nullData, testConfig()).latestAppUpdate("ios")
        val nullEnvelope = assertIs<ApiResult.Success<*>>(nullResult).data as MessageEnvelopeDto<*>
        assertNull(nullEnvelope.data)
        val mapped = DefaultAppUpdateRepository(
            MessageApiService(nullData, testConfig()),
        ).latest("ios", "release")
        assertNull(assertIs<ApiResult.Success<*>>(mapped).data)
    }

    @Test
    fun listAppUpdatesUsesUpdatesCollectionPathWithoutAuthorization() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val service = MessageApiService(recordingClient(requests), testConfig())

        val result = service.listAppUpdates("linux", "beta", page = 2, pageSize = 250)

        assertIs<ApiResult.Success<*>>(result, result.toString())
        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/api/v1/updates", request.url.encodedPath)
        assertEquals("linux", request.url.parameters["platform"])
        assertEquals("beta", request.url.parameters["channel"])
        assertEquals("2", request.url.parameters["page"])
        assertEquals("100", request.url.parameters["pageSize"])
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun listAppUpdatesTreatsNotFoundAsEmptyPage() = runTest {
        val client = jsonClient {
            respond(
                content = "not found",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }

        val result = MessageApiService(client, testConfig()).listAppUpdates("android", page = 3, pageSize = 25)
        val envelope = assertIs<ApiResult.Success<*>>(result).data as MessageEnvelopeDto<*>
        val data = envelope.data as MessageListDataDto
        assertEquals(emptyList(), data.list)
        assertEquals(3, data.page)
        assertEquals(25, data.pageSize)

        val page = DefaultAppUpdateRepository(
            MessageApiService(client, testConfig()),
        ).list("android", "release", page = 3, pageSize = 25)
        val emptyPage = assertIs<ApiResult.Success<*>>(page).data as com.folderspan.pro.domain.model.AppUpdatePage
        assertEquals(emptyList(), emptyPage.items)
        assertEquals(3, emptyPage.page)
    }

    @Test
    fun listAppUpdatesPropagatesOtherFailures() = runTest {
        val client = jsonClient {
            respond(
                content = "boom",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }

        val result = MessageApiService(client, testConfig()).listAppUpdates("windows")
        assertIs<ApiResult.Failure>(result)
    }

    @Test
    fun listAppUpdatesNormalizesUnknownChannelToRelease() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        MessageApiService(recordingClient(requests), testConfig()).listAppUpdates("linux", "nightly")
        assertEquals("release", requests.single().url.parameters["channel"])
    }

    @Test
    fun pageMappingSkipsInvalidAppUpdates() {
        val success = MessageEnvelopeDto(
            data = MessageListDataDto(
                list = listOf(
                    MessageItemDto(id = 7, version = "1.0.0", publishedAt = 10),
                    MessageItemDto(id = 0, version = ""),
                ),
                total = 2,
                page = 1,
                pageSize = 20,
            ),
        ).toAppUpdatePageResult()

        val page = assertIs<ApiResult.Success<*>>(success).data as com.folderspan.pro.domain.model.AppUpdatePage
        assertEquals(listOf(7L), page.items.map { it.id })
        assertEquals(2, page.total)
    }

    @Test
    fun latestAppUpdatePropagatesOtherFailures() = runTest {
        val client = jsonClient {
            respond(
                content = "boom",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }

        val result = MessageApiService(client, testConfig()).latestAppUpdate("windows")
        assertIs<ApiResult.Failure>(result)
    }

    @Test
    fun mapsLatestItemPreservingContentAndTimestamps() {
        val seconds = MessageItemDto(
            id = 4,
            title = " Update ",
            content = "  keep  ",
            version = "1.2.0",
            publishedAt = 1_700_000_000L,
            link = "https://example.test/app",
            channel = "beta",
        ).toAppUpdateOrNull()
        val milliseconds = MessageItemDto(
            id = 5,
            content = "notes",
            version = "1.2.1",
            publishedAt = 1_700_000_000_123L,
            link = "",
        ).toAppUpdateOrNull()

        requireNotNull(seconds)
        assertEquals("Update", seconds.title)
        assertEquals("  keep  ", seconds.content)
        assertEquals("1.2.0", seconds.version)
        assertEquals("beta", seconds.channel)
        assertEquals(1_700_000_000_000L, seconds.publishedAtEpochMillis)
        assertEquals("https://example.test/app", seconds.link)

        requireNotNull(milliseconds)
        assertEquals(1_700_000_000_123L, milliseconds.publishedAtEpochMillis)
        assertEquals("release", milliseconds.channel)
        assertNull(MessageItemDto(id = 0, version = "").toAppUpdateOrNull())
    }

    @Test
    fun pageMappingSkipsInvalidItems() {
        val success = MessageEnvelopeDto(
            data = MessageListDataDto(
                list = listOf(
                    MessageItemDto(id = 7, publishedAt = 10),
                    MessageItemDto(id = 0),
                ),
                total = 2,
                page = 1,
                pageSize = 20,
            ),
        ).toAnnouncementPageResult()

        val page = assertIs<ApiResult.Success<*>>(success).data as com.folderspan.pro.domain.model.AnnouncementPage
        assertEquals(listOf(7L), page.items.map { it.id })
        assertEquals(2, page.total)
    }

    private fun recordingLatestClient(requests: MutableList<HttpRequestData>): HttpClient = jsonClient { request ->
        requests += request
        respond(
            content = """{"code":0,"data":{"id":1,"type":"default","title":"Update","content":"notes","version":"1.1.0","platform":"linux","link":"https://example.test","published_at":1700000000000}}""",
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun recordingClient(requests: MutableList<HttpRequestData>): HttpClient = jsonClient { request ->
        requests += request
        respond(
            content = """{"code":0,"data":{"list":[],"total":0,"page":2,"pageSize":100}}""",
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun jsonClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(defaultJson) }
            defaultRequest {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            }
        }

    private fun testConfig() = GatewayConfig(baseUrl = "https://example.test")
}
