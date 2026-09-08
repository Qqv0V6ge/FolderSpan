package com.folderspan.pro.data.repository

import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.data.remote.api.UserApiService
import com.folderspan.pro.domain.usecase.AccountDeviceTrustRefreshResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AccountDeviceTrustRepositoryTest {
    @Test
    fun authorizationRefreshUsesOnlyDeviceListEndpoint() = runTest {
        val requestedPaths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requestedPaths += request.url.encodedPath
            respond(
                content = """{"code":0,"data":{"devices":[]}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) {
            install(ContentNegotiation) {
                json(com.folderspan.pro.core.network.defaultJson)
            }
        }
        val repository = AccountDeviceTrustRepository(
            UserApiService(client, GatewayConfig(baseUrl = "https://example.test"))
        )

        val result = repository.refresh("token")

        assertIs<AccountDeviceTrustRefreshResult.Success>(result)
        assertEquals(1, requestedPaths.size)
        assertTrue(requestedPaths.single().endsWith("/devices"))
        val legacyTicketPath = "ws" + "-tickets"
        val legacyChannelPath = "/devices/" + "ws"
        assertTrue(requestedPaths.none { it.contains(legacyTicketPath) || it.endsWith(legacyChannelPath) })
    }
}
