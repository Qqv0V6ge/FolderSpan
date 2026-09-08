package com.folderspan.service.http.server.linkshare

import com.folderspan.test.runSuspendTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinkShareHttpRuntimeTest {
    @Test
    fun requestFactoryParsesPathQueryHeadersAndCookies() {
        val request = LinkShareHttpRequest.from(
            method = "get",
            rawUri = "/docs%20root/file.txt?search=hello+world&tag=a&tag=b&empty=",
            headers = linkShareHeadersOf(
                "Cookie" to "FolderSpanLinkShareClient=abc; theme=dark",
                "X-API-Request" to "true",
            ),
            remoteHost = "192.168.1.20",
            scheme = "https",
            host = "example.local",
            port = 1204,
        )

        assertEquals("GET", request.method)
        assertEquals("/docs%20root/file.txt", request.encodedPath)
        assertEquals("/docs root/file.txt", request.path)
        assertEquals("hello world", request.query("search"))
        assertEquals(listOf("a", "b"), request.queryValues("tag"))
        assertEquals("", request.query("empty"))
        assertEquals("true", request.header("x-api-request"))
        assertEquals("abc", request.cookies["FolderSpanLinkShareClient"])
        assertEquals("192.168.1.20", request.remoteHost)
        assertTrue(request.isApiRequest())
    }

    @Test
    fun cookieSerializationMatchesBrowserSessionContract() {
        val cookie = LinkShareHttpCookie(
            name = "FolderSpanLinkShareSession",
            value = "token_123",
            maxAgeSeconds = 43_200,
            secure = true,
        )

        assertEquals(
            "FolderSpanLinkShareSession=token_123; Path=/; Max-Age=43200; Secure; HttpOnly; SameSite=Lax",
            cookie.toHeaderValue(),
        )
    }

    @Test
    fun cookieParserDecodesBrowserEncodedConsentValues() {
        val cookies = parseLinkShareCookieHeader(
            "FolderSpanLinkShareHttpsConsent=v1%3A192.168.1.2%3A1204%3AABCDEF; theme=dark"
        )

        assertEquals(
            "v1:192.168.1.2:1204:ABCDEF",
            cookies["FolderSpanLinkShareHttpsConsent"],
        )
        assertEquals("dark", cookies["theme"])
    }

    @Test
    fun responseBuilderSupportsRedirectAndMultipleCookies() {
        val response = LinkShareHttpResponseBuilder().apply {
            cookie(LinkShareHttpCookie("client", "abc", maxAgeSeconds = 10))
            cookie(LinkShareHttpCookie("session", "def", maxAgeSeconds = 20, secure = true))
            redirect("/target", statusCode = 308)
        }.build()

        assertEquals(308, response.statusCode)
        assertEquals("Permanent Redirect", response.reasonPhrase)
        assertEquals("/target", response.header("Location"))
        assertEquals("0", response.header("Content-Length"))
        assertEquals(2, response.headerValues("Set-Cookie").size)
        assertIs<LinkShareHttpResponseBody.Empty>(response.body)
    }

    @Test
    fun corsAllowsOnlyAdvertisedSameOriginRequests() {
        val advertisedHosts = setOf("192.168.1.20", "127.0.0.1")
        val sameOrigin = LinkShareHttpRequest.from(
            method = "OPTIONS",
            rawUri = "/api/share/upload",
            headers = linkShareHeadersOf(
                "Origin" to "https://192.168.1.20:1204",
                "Access-Control-Request-Headers" to "X-API-Request, Content-Type",
            ),
            scheme = "https",
            host = "192.168.1.20",
            port = 1204,
        )
        val differentOrigin = sameOrigin.copy(
            headers = normalizeRequestHeaders(
                linkShareHeadersOf("Origin" to "https://10.0.0.8:1204"),
            ),
        )
        val rebindingHost = sameOrigin.copy(
            host = "share.example",
            headers = normalizeRequestHeaders(
                linkShareHeadersOf("Origin" to "https://share.example:1204"),
            ),
        )

        val preflight = LinkShareHttpHeaderPolicies.corsPreflightResponse(sameOrigin, advertisedHosts)
        val rejected = LinkShareHttpHeaderPolicies.corsPreflightResponse(differentOrigin, advertisedHosts)
        val rebinding = LinkShareHttpHeaderPolicies.corsPreflightResponse(rebindingHost, advertisedHosts)

        assertEquals(200, preflight?.statusCode)
        assertEquals("https://192.168.1.20:1204", preflight?.header("Access-Control-Allow-Origin"))
        assertEquals(
            "Authorization, Content-Type, Content-Range, Range, X-API-Request, X-Requested-With",
            preflight?.header("Access-Control-Allow-Headers"),
        )
        assertEquals("true", preflight?.header("Access-Control-Allow-Credentials"))
        assertEquals(403, rejected?.statusCode)
        assertEquals(403, rebinding?.statusCode)
    }

    @Test
    fun securityHeadersAllowStreamsaverMitmFrameOnly() {
        val normal = LinkShareHttpHeaderPolicies.securityHeaders("/static/share/share-index.js")
        val mitm = LinkShareHttpHeaderPolicies.securityHeaders("/static/streamsaver/mitm.html")

        assertEquals("DENY", normal.first { it.name == "X-Frame-Options" }.value)
        assertEquals("SAMEORIGIN", mitm.first { it.name == "X-Frame-Options" }.value)
        val normalCsp = normal.first { it.name == "Content-Security-Policy" }.value
        val mitmCsp = mitm.first { it.name == "Content-Security-Policy" }.value
        assertFalse(normalCsp.contains("script-src 'self' 'unsafe-inline'"))
        assertContains(mitmCsp, "script-src 'self' 'unsafe-inline'")
        assertContains(normalCsp, "frame-ancestors 'none'")
        assertContains(mitmCsp, "frame-ancestors 'self'")
    }

    @Test
    fun staticMetadataPreservesSpecialCacheAndContentTypeRules() {
        val sw = LinkShareHttpHeaderPolicies.staticResponseHeaders(
            staticPath = "streamsaver/sw.js",
            etag = "sw123",
            contentLength = 10,
        )
        val mitm = LinkShareHttpHeaderPolicies.staticResponseHeaders("streamsaver/mitm.html")
        val css = LinkShareHttpHeaderPolicies.staticResponseHeaders("styles/shared-styles.css")

        assertEquals("text/javascript", sw.first { it.name == "Content-Type" }.value)
        assertEquals("no-store", sw.first { it.name == "Cache-Control" }.value)
        assertEquals("/static/streamsaver/", sw.first { it.name == "Service-Worker-Allowed" }.value)
        assertEquals("10", sw.first { it.name == "Content-Length" }.value)
        assertEquals("text/html", mitm.first { it.name == "Content-Type" }.value)
        assertEquals("no-store", mitm.first { it.name == "Cache-Control" }.value)
        assertEquals("text/css", css.first { it.name == "Content-Type" }.value)
        assertEquals("public, max-age=86400, immutable", css.first { it.name == "Cache-Control" }.value)
    }

    @Test
    fun routerDispatchesExactStaticRootAndFallbackRoutes() = runSuspendTest {
        val router = LinkShareHttpRouter.build {
            exact("POST", "/auth") { exchange ->
                LinkShareHttpResponse.text(text = "auth:${exchange.request.path}")
            }
            prefix("GET", "/static") { exchange ->
                LinkShareHttpResponse.text(text = "static:${exchange.request.path}")
            }
            exact("GET", "/api/share/upload-check") {
                LinkShareHttpResponse.text(text = "upload-check")
            }
            exact("POST", "/api/share/upload") {
                LinkShareHttpResponse.text(text = "upload")
            }
            root("GET") {
                LinkShareHttpResponse.text(text = "root")
            }
            fallback("GET") { exchange ->
                LinkShareHttpResponse.text(text = "shared:${exchange.request.path}")
            }
        }

        assertEquals("auth:/auth", router.dispatch(request("POST", "/auth")).bodyText())
        assertEquals("static:/static/share/share-index.js", router.dispatch(request("GET", "/static/share/share-index.js")).bodyText())
        assertEquals("upload-check", router.dispatch(request("GET", "/api/share/upload-check")).bodyText())
        assertEquals("upload", router.dispatch(request("POST", "/api/share/upload")).bodyText())
        assertEquals("root", router.dispatch(request("GET", "/")).bodyText())
        assertEquals("shared:/docs/file.txt", router.dispatch(request("GET", "/docs/file.txt")).bodyText())
        assertEquals(404, router.dispatch(request("DELETE", "/docs/file.txt")).statusCode)
    }

    private fun request(method: String, rawUri: String): LinkShareHttpRequest {
        return LinkShareHttpRequest.from(method = method, rawUri = rawUri)
    }

    private fun LinkShareHttpResponse.bodyText(): String {
        val bytes = (body as LinkShareHttpResponseBody.Bytes).bytes
        return bytes.decodeToString()
    }
}
