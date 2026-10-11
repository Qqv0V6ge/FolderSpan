package com.folderspan.service.http.server.linkshare

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.http.clipboard.ClipboardUrlDownloadPlatformCapabilities
import com.folderspan.service.http.clipboard.ClipboardUrlShareInspectionResult
import com.folderspan.service.http.clipboard.ClipboardUrlShareSourceRegistry
import com.folderspan.service.http.clipboard.DefaultClipboardUrlShareInspector
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.ShareTokenFingerprint
import com.folderspan.ui.state.main.NotificationState
import com.folderspan.test.ChineseLocalizationTest
import strings.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinkShareRouteDispatcherJvmTest : ChineseLocalizationTest() {
    @Test
    fun inspectedUrlFileStreamsThroughTheShareRouteWithoutLocalFileAccess() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            val upstreamRanges = mutableListOf<String?>()
            val inspector = DefaultClipboardUrlShareInspector(
                clientProvider = {
                    HttpClient(MockEngine { request ->
                        val range = request.headers[HttpHeaders.Range]
                        upstreamRanges += range
                        when (range) {
                            "bytes=0-0" -> respond(
                                content = ByteReadChannel("A".encodeToByteArray()),
                                status = HttpStatusCode.PartialContent,
                                headers = headersOf(
                                    HttpHeaders.ContentLength to listOf("1"),
                                    HttpHeaders.ContentRange to listOf("bytes 0-0/8"),
                                    HttpHeaders.ContentType to listOf("application/octet-stream"),
                                    HttpHeaders.ContentDisposition to listOf("attachment; filename=data.bin"),
                                ),
                            )

                            "bytes=2-4" -> respond(
                                content = ByteReadChannel("CDE".encodeToByteArray()),
                                status = HttpStatusCode.PartialContent,
                                headers = headersOf(
                                    HttpHeaders.ContentLength to listOf("3"),
                                    HttpHeaders.ContentRange to listOf("bytes 2-4/8"),
                                    HttpHeaders.ContentType to listOf("application/octet-stream"),
                                ),
                            )

                            else -> error("unexpected upstream range: $range")
                        }
                    }) {
                        expectSuccess = false
                        followRedirects = false
                    }
                },
                capabilities = ClipboardUrlDownloadPlatformCapabilities(
                    platformName = "Desktop",
                    canSetCookie = true,
                    canSetUserAgent = true,
                    canControlAutomaticHeaders = true,
                    browserCredentialsOmitted = false,
                ),
            )
            val inspected = inspector.inspect("https://example.test/data.bin")
                as ClipboardUrlShareInspectionResult.Success
            try {
                val state = FileShareState()
                val session = state.issueLinkShareSession(
                    clientId = CLIENT_ID,
                    fingerprint = ShareTokenFingerprint(clientIp = CLIENT_IP, userAgent = USER_AGENT),
                    allowHidden = false,
                    files = listOf(inspected.file),
                )

                val response = dispatcher(state).dispatch(
                    authorizedRequest("/${inspected.file.name}", session.token, range = "bytes=2-4")
                )
                val body = response.body as LinkShareHttpResponseBody.Stream
                val writer = RecordingResponseWriter()
                body.writer(writer)

                assertEquals(206, response.statusCode)
                assertEquals("bytes 2-4/8", response.header("Content-Range"))
                assertContentEquals("CDE".encodeToByteArray(), writer.bytes())
                assertEquals(listOf<String?>("bytes=0-0", "bytes=2-4"), upstreamRanges)
            } finally {
                inspected.release()
                ClipboardUrlShareSourceRegistry.releaseAll()
            }
        }
    }

    @Test
    fun previewFileUsesBrowserMediaTypeAndInlineDisposition() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedLocalFile("guide.pdf", "%PDF-test".encodeToByteArray()) { root, state, sessionToken ->
                val response = dispatcher(state).dispatch(
                    sharedFileRequest(root, "guide.pdf", sessionToken, preview = true)
                )

                assertEquals(200, response.statusCode, response.bodyText())
                assertEquals("application/pdf", response.header("Content-Type"))
                assertTrue(response.header("Content-Disposition").orEmpty().startsWith("inline;"))
                assertEquals("bytes", response.header("Accept-Ranges"))
                assertEquals("%PDF-test", response.bodyText())
            }
        }
    }

    @Test
    fun textPreviewDeclaresUtf8CharsetAndKeepsOriginalBytes() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            val expectedBytes = AppStrings.ui_test_link_share_route_dispatcher_jvm_hi_folderspan.encodeToByteArray()
            withSharedLocalFile("notes.txt", expectedBytes) { root, state, sessionToken ->
                val response = dispatcher(state).dispatch(
                    sharedFileRequest(root, "notes.txt", sessionToken, preview = true)
                )

                assertEquals(200, response.statusCode, response.bodyText())
                assertEquals("text/plain; charset=UTF-8", response.header("Content-Type"))
                assertTrue(response.header("Content-Disposition").orEmpty().startsWith("inline;"))
                val body = response.body as LinkShareHttpResponseBody.Bytes
                assertContentEquals(expectedBytes, body.bytes)
            }
        }
    }

    @Test
    fun previewRangeUsesSameInlineResponseWithPartialBytes() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedLocalFile("clip.mp4", "0123456789".encodeToByteArray()) { root, state, sessionToken ->
                val response = dispatcher(state).dispatch(
                    sharedFileRequest(root, "clip.mp4", sessionToken, preview = true, range = "bytes=2-5")
                )

                assertEquals(206, response.statusCode, response.bodyText())
                assertEquals("video/mp4", response.header("Content-Type"))
                assertEquals("bytes 2-5/10", response.header("Content-Range"))
                assertEquals("4", response.header("Content-Length"))
                assertTrue(response.header("Content-Disposition").orEmpty().startsWith("inline;"))
                assertEquals("2345", response.bodyText())
            }
        }
    }

    @Test
    fun directAndApiFileRequestsRemainAttachments() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedLocalFile("notes.txt", "hello".encodeToByteArray()) { root, state, sessionToken ->
                val directResponse = dispatcher(state).dispatch(
                    sharedFileRequest(root, "notes.txt", sessionToken)
                )
                val apiPreviewResponse = dispatcher(state).dispatch(
                    sharedFileRequest(root, "notes.txt", sessionToken, preview = true, api = true)
                )

                assertEquals(200, directResponse.statusCode, directResponse.bodyText())
                assertTrue(directResponse.header("Content-Disposition").orEmpty().startsWith("attachment;"))
                assertEquals(200, apiPreviewResponse.statusCode, apiPreviewResponse.bodyText())
                assertTrue(apiPreviewResponse.header("Content-Disposition").orEmpty().startsWith("attachment;"))
            }
        }
    }

    @Test
    fun unknownPreviewTypeFallsBackToAttachmentAndEmptyFileKeepsZeroLength() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedLocalFile("payload.folderspan-unknown", byteArrayOf()) { root, state, sessionToken ->
                val response = dispatcher(state).dispatch(
                    sharedFileRequest(root, "payload.folderspan-unknown", sessionToken, preview = true)
                )

                assertEquals(200, response.statusCode, response.bodyText())
                assertEquals("application/octet-stream", response.header("Content-Type"))
                assertEquals("0", response.header("Content-Length"))
                assertTrue(response.header("Content-Disposition").orEmpty().startsWith("attachment;"))
            }
        }
    }

    @Test
    fun singleFileShareRootUsesPreviewForBrowser() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSingleSharedLocalFile("photo.png", byteArrayOf(1, 2, 3), "image/png") { state, sessionToken ->
                val browserResponse = dispatcher(state).dispatch(authorizedRequest("/", sessionToken))

                assertEquals(200, browserResponse.statusCode, browserResponse.bodyText())
                assertEquals("image/png", browserResponse.header("Content-Type"))
                assertTrue(browserResponse.header("Content-Disposition").orEmpty().startsWith("inline;"))
            }
        }
    }

    @Test
    fun unsupportedSingleFileShareRootFallsBackToAttachment() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSingleSharedLocalFile(
                "gradle.properties",
                "org.gradle.jvmargs=-Xmx2g".encodeToByteArray(),
                ".properties",
            ) { state, sessionToken ->
                val browserResponse = dispatcher(state).dispatch(authorizedRequest("/", sessionToken))

                assertEquals(200, browserResponse.statusCode, browserResponse.bodyText())
                assertTrue(
                    browserResponse.header("Content-Disposition").orEmpty().startsWith("attachment;"),
                )
            }
        }
    }

    @Test
    fun breadcrumbsUseBrowserLanguageWithoutTranslatingSharedDirectoryName() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedLocalFile("notes.txt", "hello".encodeToByteArray()) { root, state, sessionToken ->
                val dispatcher = dispatcher(state)
                val englishResponse = dispatcher.dispatch(
                    authorizedRequest(
                        rawUri = "/${root.name}",
                        sessionToken = sessionToken,
                        acceptLanguage = "en-US",
                    )
                )
                val chineseResponse = dispatcher.dispatch(
                    authorizedRequest(
                        rawUri = "/${root.name}",
                        sessionToken = sessionToken,
                        acceptLanguage = "zh-CN",
                    )
                )

                assertEquals(200, englishResponse.statusCode, englishResponse.bodyText())
                assertContains(englishResponse.bodyText(), "Home</a>")
                assertFalse(AppStrings.ui_test_link_share_route_dispatcher_jvm_home in englishResponse.bodyText())
                assertContains(englishResponse.bodyText(), "${root.name}</span>")

                assertEquals(200, chineseResponse.statusCode, chineseResponse.bodyText())
                assertContains(chineseResponse.bodyText(), AppStrings.ui_test_link_share_route_dispatcher_jvm_home)
                assertFalse("Home</a>" in chineseResponse.bodyText())
                assertContains(chineseResponse.bodyText(), "${root.name}</span>")
            }
        }
    }

    @Test
    fun pageConfigUsesAdvertisedHostInsteadOfUntrustedRequestHost() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedLocalFile("notes.txt", "hello".encodeToByteArray()) { root, state, sessionToken ->
                val response = dispatcher(
                    state = state,
                    advertisedHosts = setOf("192.168.1.20", "127.0.0.1"),
                ).dispatch(
                    authorizedRequest(
                        rawUri = "/${root.name}",
                        sessionToken = sessionToken,
                    ).copy(host = "share.example", port = 1204)
                )

                assertEquals(403, response.statusCode, response.bodyText())
                assertRejectedHostSecurityHeaders(response)
                assertFalse("share.example" in response.bodyText())
            }
        }
    }

    @Test
    fun blankHostIsRejectedBeforeDispatch() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedLocalFile("notes.txt", "hello".encodeToByteArray()) { root, state, sessionToken ->
                val response = dispatcher(state).dispatch(
                    authorizedRequest(
                        rawUri = "/${root.name}",
                        sessionToken = sessionToken,
                    ).copy(host = "")
                )

                assertEquals(403, response.statusCode, response.bodyText())
                assertRejectedHostSecurityHeaders(response)
            }
        }
    }

    @Test
    fun downloadScriptsEscapeUserAgentAndPathMetacharacters() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            val request = LinkShareHttpRequest.from(
                method = "GET",
                rawUri = "/share/project%22%24%60",
                headers = linkShareHeadersOf("User-Agent" to "Mozilla/5.0\"\$`"),
                remoteHost = CLIENT_IP,
            )
            val script = dispatcher(FileShareState()).readShellFileForTest(
                request = request,
                templatePath = "files/share-file/shell/script.sh",
                sessionToken = "tokenhidden000000",
            )

            val assignment = script.lineSequence().first { line -> line.startsWith("USER_AGENT=") }
            assertTrue(assignment.startsWith("USER_AGENT=\""))
            assertEquals(1, assignment.lines().size)
            assertFalse(assignment.contains("\"\$`"))
            assertContains(assignment, "Mozilla/5.0\\\"\\\$\\`")

            val rootPath = script.lineSequence().first { line -> line.startsWith("root_path=") }
            assertEquals("root_path=\"/share/project%22%24%60\"", rootPath)
            assertFalse(script.contains("root_path=\"/share/project\"\$`\""))

            val targetDir = script.lineSequence().first { line -> line.startsWith("TARGET_DIR=") }
            assertEquals(1, targetDir.lines().size)
            assertTrue(targetDir.startsWith("TARGET_DIR=\"./"))
            assertFalse(targetDir.contains("\"\$`"))
        }
    }

    @Test
    fun downloadScriptsRejectControlCharactersInSubstitutedValues() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            val request = LinkShareHttpRequest.from(
                method = "GET",
                rawUri = "/share/dir",
                headers = linkShareHeadersOf("User-Agent" to "ok\nInjected"),
                remoteHost = CLIENT_IP,
            )
            val script = dispatcher(FileShareState()).readShellFileForTest(
                request = request,
                templatePath = "files/share-file/shell/script.ps1",
                sessionToken = "tokenhidden000000",
            )

            assertFalse(script.contains("\nInjected"))
            val userAgentLine = script.lineSequence().first { line -> line.contains("USER_AGENT") }
            assertTrue(userAgentLine.contains("USER_AGENT"))
            assertFalse('\n' in userAgentLine)
            assertContains(userAgentLine, "ok_Injected")
        }
    }

    @Test
    fun cmdDownloadScriptTargetDirContainsOnlySafeCharacters() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            val request = LinkShareHttpRequest.from(
                method = "GET",
                rawUri = "/share/dir-name",
                headers = linkShareHeadersOf("User-Agent" to "FolderSpan"),
                remoteHost = CLIENT_IP,
            )
            val script = dispatcher(FileShareState()).readShellFileForTest(
                request = request,
                templatePath = "files/share-file/shell/script.bat",
                sessionToken = "tokenhidden000000",
            )

            val targetDir = script.lineSequence().first { line ->
                line.contains("TARGET_DIR", ignoreCase = true)
            }
            val value = targetDir.substringAfter('=').trim().trim('"')
            assertTrue(value.all { char ->
                char.isLetterOrDigit() || char == '.' || char == '_' || char == '-' || char == '\\'
            })
        }
    }

    @Test
    fun directoryHtmlUsesNoStoreCacheControl() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedLocalFile("notes.txt", "hello".encodeToByteArray()) { root, state, sessionToken ->
                val response = dispatcher(state).dispatch(
                    authorizedRequest(rawUri = "/${root.name}", sessionToken = sessionToken)
                )
                assertEquals(200, response.statusCode, response.bodyText())
                assertEquals("no-store", response.header("Cache-Control"))
            }
        }
    }

    @Test
    fun previewParameterCannotBypassMissingAuthorization() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedLocalFile("public.txt", "public".encodeToByteArray()) { root, state, _ ->
                val unauthorized = dispatcher(state).dispatch(
                    LinkShareHttpRequest.from(
                        method = "GET",
                        rawUri = "/${root.name}/public.txt?preview=1",
                        headers = linkShareHeadersOf("User-Agent" to USER_AGENT),
                        remoteHost = CLIENT_IP,
                    )
                )
                assertEquals(302, unauthorized.statusCode, unauthorized.bodyText())
                assertEquals("/", unauthorized.header("Location"))
            }
        }
    }

    @Test
    fun manuallyApprovedRequestReceivesSessionAndStopsRequestingPermission() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedDirectory { root, state ->
                state.files.add(root)
                state.updateLinkShareDefaults(allowHidden = false, files = listOf(root))
                val dispatcher = dispatcher(state)

                val pendingResponse = dispatcher.dispatch(rootRequest())
                assertEquals(200, pendingResponse.statusCode, pendingResponse.bodyText())
                assertTrue(state.pendingLinkShareDevices.any { device -> device.id == CLIENT_ID })

                state.approveLinkShareDevice(CLIENT_ID)

                val approvedResponse = dispatcher.dispatch(rootRequest())
                val sessionCookie = assertNotNull(
                    approvedResponse.headerValues("Set-Cookie")
                        .firstOrNull { cookie -> cookie.startsWith("FolderSpanLinkShareSession=") },
                    approvedResponse.headerValues("Set-Cookie").joinToString(),
                )
                assertTrue(state.pendingLinkShareDevices.isEmpty())
                assertTrue(state.authorizedLinkShareDevices.keys.any { device -> device.id == CLIENT_ID })

                val sessionToken = sessionCookie.substringAfter('=').substringBefore(';')
                val subsequentResponse = dispatcher.dispatch(authorizedRequest("/", sessionToken))
                assertEquals(302, subsequentResponse.statusCode, subsequentResponse.bodyText())
                assertTrue(state.pendingLinkShareDevices.isEmpty())
            }
        }
    }

    @Test
    fun authorizedClientCookieDoesNotReissueSessionForChangedFingerprint() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedLocalFile("notes.txt", "shared-secret".encodeToByteArray()) { root, state, originalToken ->
                state.authorizeLinkShareDevice(
                    device = Device(CLIENT_ID, "Browser", "/", mutableMapOf(), DeviceType.JS, ""),
                    allowHidden = false,
                    files = listOf(root),
                    revokeExistingSessions = false,
                )
                val attacker = dispatcher(state).dispatch(
                    LinkShareHttpRequest.from(
                        method = "GET",
                        rawUri = "/${root.name}/notes.txt",
                        headers = linkShareHeadersOf(
                            "User-Agent" to ATTACKER_USER_AGENT,
                            "Cookie" to "FolderSpanLinkShareClient=$CLIENT_ID",
                            "X-API-Request" to "true",
                        ),
                        remoteHost = ATTACKER_IP,
                    )
                )
                val setCookies = attacker.headerValues("Set-Cookie")

                assertEquals(403, attacker.statusCode, attacker.bodyText())
                assertEquals(AppStrings.ui_unauthorized_access, attacker.bodyText())
                assertFalse(attacker.bodyText().contains("shared-secret"))
                assertFalse(
                    setCookies.any { cookie -> cookie.startsWith("FolderSpanLinkShareSession=") },
                    setCookies.joinToString(),
                )
                assertEquals(setOf(originalToken), state.linkShareSessions.keys.toSet())

                val unknownClient = dispatcher(state).dispatch(
                    LinkShareHttpRequest.from(
                        method = "GET",
                        rawUri = "/${root.name}/notes.txt",
                        headers = linkShareHeadersOf(
                            "User-Agent" to ATTACKER_USER_AGENT,
                            "Cookie" to "FolderSpanLinkShareClient=$UNKNOWN_CLIENT_ID",
                            "X-API-Request" to "true",
                        ),
                        remoteHost = ATTACKER_IP,
                    )
                )
                assertEquals(403, unknownClient.statusCode, unknownClient.bodyText())
                assertFalse(unknownClient.bodyText().contains("shared-secret"))
            }
        }
    }

    @Test
    fun browserHiddenFileAccessRedirectsToRoot() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withHiddenSharedFile { root, state, sessionToken ->
                val dispatcher = dispatcher(state)
                val response = dispatcher.dispatch(linkShareRequest(root, sessionToken, api = false, preview = true))

                assertEquals(302, response.statusCode, response.bodyText())
                assertEquals("/", response.header("Location"))
            }
        }
    }

    @Test
    fun apiHiddenFileAccessRemainsForbidden() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withHiddenSharedFile { root, state, sessionToken ->
                val dispatcher = dispatcher(state)
                val response = dispatcher.dispatch(linkShareRequest(root, sessionToken, api = true, preview = true))

                assertEquals(403, response.statusCode, response.bodyText())
                assertEquals(AppStrings.ui_no_right_to_access_hidden_files, response.bodyText())
            }
        }
    }

    @Test
    fun directHttpsCounterpartDoesNotRedirectBackToHttp() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            val state = FileShareState()
            val dispatcher = dispatcher(state).apply { configurePorts(httpPort = 1204, httpsPort = 1204) }
            val response = dispatcher.dispatch(
                LinkShareHttpRequest.from(
                    method = "GET",
                    rawUri = "/docs?download=1",
                    headers = linkShareHeadersOf("User-Agent" to USER_AGENT),
                    remoteHost = CLIENT_IP,
                    scheme = "https",
                    host = "127.0.0.1",
                    port = 1204,
                )
            )

            assertEquals(302, response.statusCode, response.bodyText())
            assertEquals("/", response.header("Location"))
        }
    }

    @Test
    fun autoApprovedRequestIsRecordedAsAuthorizedDeviceAndCanBeRejected() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedDirectory { root, state ->
                state.files.add(root)
                state.updateLinkShareDefaults(allowHidden = false, allowUpload = true, files = listOf(root))
                state.updateAutoApprove(true)
                val dispatcher = dispatcher(state)

                val response = dispatcher.dispatch(rootRequest())

                assertEquals(302, response.statusCode, response.bodyText())
                assertTrue(state.pendingLinkShareDevices.isEmpty())
                assertTrue(state.authorizedLinkShareDevices.keys.any { device -> device.id == CLIENT_ID })
                assertEquals(true, state.getAuthorizedLinkShareDevice(CLIENT_ID)?.allowUpload)

                val authorizedDevice = state.authorizedLinkShareDevices.keys.first { device -> device.id == CLIENT_ID }
                assertTrue(state.rejectAuthorizedLinkShareDevice(authorizedDevice))
                assertTrue(state.rejectedLinkShareDevices.any { device -> device.id == CLIENT_ID })

                val rejectedResponse = dispatcher.dispatch(rootRequest(api = true))

                assertEquals(403, rejectedResponse.statusCode, rejectedResponse.bodyText())
            }
        }
    }

    @Test
    fun passwordAuthorizedRequestIsRecordedAsAuthorizedDevice() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedDirectory { root, state ->
                state.files.add(root)
                state.updateLinkShareDefaults(allowHidden = false, files = listOf(root))
                state.updateConnectPassword(LINK_PASSWORD)
                val dispatcher = dispatcher(state)

                val response = dispatcher.dispatch(passwordAuthRequest(LINK_PASSWORD))

                assertEquals(302, response.statusCode, response.bodyText())
                assertEquals("/", response.header("Location"))
                assertTrue(state.pendingLinkShareDevices.isEmpty())
                assertTrue(state.authorizedLinkShareDevices.keys.any { device -> device.id == CLIENT_ID })
            }
        }
    }

    @Test
    fun passwordAuthRejectsCrossOriginAndMissingOrigin() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedDirectory { root, state ->
                state.files.add(root)
                state.updateLinkShareDefaults(allowHidden = false, files = listOf(root))
                state.updateConnectPassword(LINK_PASSWORD)
                val dispatcher = dispatcher(state)

                val crossOrigin = dispatcher.dispatch(
                    passwordAuthRequest(LINK_PASSWORD, origin = "https://evil.example")
                )
                assertEquals(403, crossOrigin.statusCode, crossOrigin.bodyText())
                assertEquals("text/html; charset=UTF-8", crossOrigin.header("Content-Type"))
                assertContains(crossOrigin.bodyText(), AppStrings.error_auth_request_origin_invalid)
                assertContains(crossOrigin.bodyText(), "href=\"/\"")
                assertContains(crossOrigin.bodyText(), AppStrings.ui_return)
                assertTrue(state.authorizedLinkShareDevices.isEmpty())

                val missingOrigin = dispatcher.dispatch(
                    passwordAuthRequest(LINK_PASSWORD, origin = null)
                )
                assertEquals(403, missingOrigin.statusCode, missingOrigin.bodyText())
                assertEquals("text/html; charset=UTF-8", missingOrigin.header("Content-Type"))
                assertContains(missingOrigin.bodyText(), AppStrings.error_auth_request_origin_invalid)
                assertTrue(state.authorizedLinkShareDevices.isEmpty())

                val apiCrossOrigin = dispatcher.dispatch(
                    passwordAuthRequest(LINK_PASSWORD, origin = "https://evil.example", api = true)
                )
                assertEquals(403, apiCrossOrigin.statusCode, apiCrossOrigin.bodyText())
                assertEquals(AppStrings.error_auth_request_origin_invalid, apiCrossOrigin.bodyText())
                assertTrue(state.authorizedLinkShareDevices.isEmpty())

                val sameOrigin = dispatcher.dispatch(
                    passwordAuthRequest(LINK_PASSWORD, origin = "http://127.0.0.1")
                )
                assertEquals(302, sameOrigin.statusCode, sameOrigin.bodyText())
                assertEquals("/", sameOrigin.header("Location"))
                assertTrue(state.authorizedLinkShareDevices.keys.any { device -> device.id == CLIENT_ID })
            }
        }
    }

    @Test
    fun passwordAuthIgnoresQueryPasswordAndRequiresPostBody() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedDirectory { root, state ->
                state.files.add(root)
                state.updateLinkShareDefaults(allowHidden = false, files = listOf(root))
                state.updateConnectPassword(LINK_PASSWORD)
                val dispatcher = dispatcher(state)

                val queryOnly = dispatcher.dispatch(
                    passwordAuthRequest(password = "wrong", rawUri = "/auth?pwd=$LINK_PASSWORD")
                )
                assertEquals(401, queryOnly.statusCode, queryOnly.bodyText())
                assertTrue(queryOnly.bodyText().contains(AppStrings.ui_test_link_share_route_dispatcher_jvm_enter_the_correct_password))
                assertTrue(state.authorizedLinkShareDevices.isEmpty())

                val unauthenticatedGet = dispatcher.dispatch(
                    LinkShareHttpRequest.from(
                        method = "GET",
                        rawUri = "/?pwd=$LINK_PASSWORD",
                        headers = linkShareHeadersOf(
                            "User-Agent" to USER_AGENT,
                            "Cookie" to "FolderSpanLinkShareClient=$CLIENT_ID",
                        ),
                        remoteHost = CLIENT_IP,
                    )
                )
                assertEquals(401, unauthenticatedGet.statusCode, unauthenticatedGet.bodyText())
                assertTrue(unauthenticatedGet.bodyText().contains(AppStrings.ui_test_link_share_route_dispatcher_jvm_enter_the_correct_password))
            }
        }
    }

    @Test
    fun passwordAuthLocksOutAfterRepeatedFailures() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedDirectory { root, state ->
                state.files.add(root)
                state.updateLinkShareDefaults(allowHidden = false, files = listOf(root))
                state.updateConnectPassword(LINK_PASSWORD)
                val limiter = LinkSharePasswordAttemptLimiter(
                    maxFailures = 3,
                    lockoutDurationMillis = 60_000L,
                    nowMillis = { 1_000_000L },
                )
                val dispatcher = dispatcher(state, passwordAttemptLimiter = limiter)

                repeat(2) { attempt ->
                    val failed = dispatcher.dispatch(passwordAuthRequest("wrong-$attempt"))
                    assertEquals(401, failed.statusCode, failed.bodyText())
                }
                val locked = dispatcher.dispatch(passwordAuthRequest("wrong-final"))
                assertEquals(429, locked.statusCode, locked.bodyText())
                assertEquals("60", locked.header("Retry-After"))
                assertTrue(locked.bodyText().contains(AppStrings.ui_test_link_share_route_dispatcher_jvm_try_too_many_times))

                val correctAfterLock = dispatcher.dispatch(passwordAuthRequest(LINK_PASSWORD))
                assertEquals(429, correctAfterLock.statusCode, correctAfterLock.bodyText())
                assertTrue(state.authorizedLinkShareDevices.isEmpty())
            }
        }
    }

    @Test
    fun concurrentWrongPasswordsCannotExceedFailureBudget() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedDirectory { root, state ->
                state.files.add(root)
                state.updateLinkShareDefaults(allowHidden = false, files = listOf(root))
                state.updateConnectPassword(LINK_PASSWORD)
                val limiter = LinkSharePasswordAttemptLimiter(
                    maxFailures = 3,
                    lockoutDurationMillis = 60_000L,
                    nowMillis = { 1_000_000L },
                )
                val dispatcher = dispatcher(state, passwordAttemptLimiter = limiter)
                val responses = List(20) {
                    async(Dispatchers.Default) {
                        dispatcher.dispatch(passwordAuthRequest(password = "wrong", remoteHost = "10.0.0.8"))
                    }
                }.awaitAll()
                val unauthorized = responses.count { item -> item.statusCode == 401 }
                val throttled = responses.count { item -> item.statusCode == 429 }
                assertTrue(unauthorized <= 3, "unauthorized=$unauthorized throttled=$throttled")
                assertTrue(throttled >= 17, "unauthorized=$unauthorized throttled=$throttled")
                val after = dispatcher.dispatch(passwordAuthRequest(password = "wrong", remoteHost = "10.0.0.8"))
                assertEquals(429, after.statusCode)
                assertTrue(state.authorizedLinkShareDevices.isEmpty())
            }
        }
    }

    @Test
    fun staticResourcesRejectPathTraversalWithoutAuthentication() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            val dispatcher = dispatcher(FileShareState())
            val traversalUris = listOf(
                "/static/../shell/script.sh",
                "/static/../../../../../../../etc/passwd",
                "/static/..%2f..%2f..%2f..%2f..%2f..%2f..%2f..%2f..%2f..%2f..%2fetc/passwd",
                "/static/%2e%2e/%2e%2e/%2e%2e/%2e%2e/%2e%2e/%2e%2e/%2e%2e/%2e%2e/%2e%2e/%2e%2e/%2e%2e/etc/passwd",
            )
            traversalUris.forEach { rawUri ->
                val response = dispatcher.dispatch(
                    LinkShareHttpRequest.from(
                        method = "GET",
                        rawUri = rawUri,
                        headers = linkShareHeadersOf("User-Agent" to USER_AGENT),
                        remoteHost = CLIENT_IP,
                    )
                )
                assertEquals(404, response.statusCode, "$rawUri -> ${response.bodyText()}")
                assertFalse(response.bodyText().startsWith("root:"), rawUri)
                assertFalse(response.bodyText().contains("#!/"), rawUri)
            }

            val css = dispatcher.dispatch(
                LinkShareHttpRequest.from(
                    method = "GET",
                    rawUri = "/static/styles/shared-styles.css",
                    headers = linkShareHeadersOf("User-Agent" to USER_AGENT),
                    remoteHost = CLIENT_IP,
                )
            )
            assertEquals(200, css.statusCode, css.bodyText())
            assertEquals("text/css", css.header("Content-Type"))
            assertTrue(css.bodyText().isNotBlank())
        }
    }

    @Test
    fun uploadCheckRequiresDeviceUploadPermissionAndAllowsOverride() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedDirectory { root, state ->
                val session = state.issueLinkShareSession(
                    clientId = CLIENT_ID,
                    fingerprint = ShareTokenFingerprint(clientIp = CLIENT_IP, userAgent = USER_AGENT),
                    allowHidden = false,
                    allowUpload = false,
                    files = listOf(root),
                )
                val dispatcher = dispatcher(state)

                val deniedResponse = dispatcher.dispatch(uploadCheckRequest(root, session.token))

                assertEquals(403, deniedResponse.statusCode, deniedResponse.bodyText())
                assertEquals(AppStrings.ui_upload_not_allowed, deniedResponse.bodyText())

                assertTrue(state.approveLinkShareUploadDevice(CLIENT_ID))

                val allowedResponse = dispatcher.dispatch(uploadCheckRequest(root, session.token))

                assertEquals(200, allowedResponse.statusCode, allowedResponse.bodyText())
            }
        }
    }

    @Test
    fun uploadPermissionRequestDeduplicatesAndRespectsRejection() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withSharedDirectory { root, state ->
                val session = state.issueLinkShareSession(
                    clientId = CLIENT_ID,
                    fingerprint = ShareTokenFingerprint(clientIp = CLIENT_IP, userAgent = USER_AGENT),
                    allowHidden = false,
                    allowUpload = false,
                    files = listOf(root),
                )
                val dispatcher = dispatcher(state)

                val firstResponse = dispatcher.dispatch(uploadPermissionRequest(session.token))
                val secondResponse = dispatcher.dispatch(uploadPermissionRequest(session.token))

                assertEquals(202, firstResponse.statusCode, firstResponse.bodyText())
                assertEquals(202, secondResponse.statusCode, secondResponse.bodyText())
                assertEquals(1, state.pendingLinkShareUploadDevices.distinctBy { device -> device.id }.size)

                assertTrue(state.rejectLinkShareUploadDevice(CLIENT_ID))

                val rejectedResponse = dispatcher.dispatch(uploadPermissionRequest(session.token))

                assertEquals(403, rejectedResponse.statusCode, rejectedResponse.bodyText())
                assertEquals(AppStrings.ui_upload_permission_request_has_been_denied, rejectedResponse.bodyText())
                assertTrue(state.pendingLinkShareUploadDevices.none { device -> device.id == CLIENT_ID })
            }
        }
    }

    @Test
    fun chunkedUploadWritesFileRanges() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withUploadSharedDirectory { root, state, sessionToken ->
                val dispatcher = dispatcher(state)
                val firstChunk = byteArrayOf(1, 2, 3)
                val secondChunk = byteArrayOf(4, 5)
                val totalSize = firstChunk.size + secondChunk.size

                val firstResponse = dispatcher.dispatch(
                    uploadChunkRequest(
                        root = root,
                        sessionToken = sessionToken,
                        fileName = UPLOAD_FILE_NAME,
                        totalSize = totalSize,
                        start = 0,
                        chunk = firstChunk,
                    )
                )
                val secondResponse = dispatcher.dispatch(
                    uploadChunkRequest(
                        root = root,
                        sessionToken = sessionToken,
                        fileName = UPLOAD_FILE_NAME,
                        totalSize = totalSize,
                        start = firstChunk.size.toLong(),
                        chunk = secondChunk,
                    )
                )

                assertEquals(200, firstResponse.statusCode, firstResponse.bodyText())
                assertEquals(200, secondResponse.statusCode, secondResponse.bodyText())
                assertContentEquals(
                    firstChunk + secondChunk,
                    File(root.path, UPLOAD_FILE_NAME).readBytes()
                )
            }
        }
    }

    @Test
    fun uploadCancelOnlyRemovesTrackedPartialUpload() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withUploadSharedDirectory { root, state, sessionToken ->
                val dispatcher = dispatcher(state)
                val existingFile = File(root.path, "existing.bin").apply { writeBytes(byteArrayOf(9)) }

                val unrelatedCancelResponse = dispatcher.dispatch(
                    uploadCancelRequest(
                        root = root,
                        sessionToken = sessionToken,
                        fileName = existingFile.name,
                        totalSize = 10,
                    )
                )

                assertEquals(200, unrelatedCancelResponse.statusCode, unrelatedCancelResponse.bodyText())
                assertTrue(existingFile.exists(), AppStrings.ui_test_link_share_route_dispatcher_jvm_unregistered_cancellation_requests)

                val partialFileName = "partial.bin"
                val firstChunk = byteArrayOf(1, 2, 3)
                val uploadResponse = dispatcher.dispatch(
                    uploadChunkRequest(
                        root = root,
                        sessionToken = sessionToken,
                        fileName = partialFileName,
                        totalSize = 5,
                        start = 0,
                        chunk = firstChunk,
                    )
                )
                val cancelResponse = dispatcher.dispatch(
                    uploadCancelRequest(
                        root = root,
                        sessionToken = sessionToken,
                        fileName = partialFileName,
                        totalSize = 5,
                    )
                )

                assertEquals(200, uploadResponse.statusCode, uploadResponse.bodyText())
                assertEquals(200, cancelResponse.statusCode, cancelResponse.bodyText())
                assertFalse(File(root.path, partialFileName).exists(), AppStrings.ui_test_link_share_route_dispatcher_jvm_already_completed_shard_uploads_should)
            }
        }
    }

    @Test
    fun browseRejectsSymbolicLinkEscapeFromSharedRoot() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withUploadSharedDirectory { root, state, sessionToken ->
                val outside = Files.createTempDirectory("link-share-outside")
                val sentinel = Files.writeString(outside.resolve("secret.txt"), "secret")
                val link = File(root.path).toPath().resolve("escape")
                try {
                    runCatching { Files.createSymbolicLink(link, outside) }.getOrNull() ?: return@withUploadSharedDirectory
                    val response = dispatcher(state).dispatch(
                        LinkShareHttpRequest.from(
                            method = "GET",
                            rawUri = "/${root.name}/escape/${sentinel.fileName}?preview=1",
                            headers = linkShareHeadersOf(
                                "User-Agent" to USER_AGENT,
                                "X-FolderSpan-Link-Session" to sessionToken,
                                "Cookie" to "FolderSpanLinkShareClient=$CLIENT_ID; FolderSpanLinkShareSession=$sessionToken",
                                "X-API-Request" to "true",
                            ),
                            remoteHost = CLIENT_IP,
                        )
                    )

                    assertEquals(403, response.statusCode, response.bodyText())
                    assertEquals("secret", Files.readString(sentinel))
                } finally {
                    Files.deleteIfExists(link)
                    outside.toFile().deleteRecursively()
                }
            }
        }
    }

    @Test
    fun uploadRejectsSymbolicLinkParentOutsideSharedRoot() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            withUploadSharedDirectory { root, state, sessionToken ->
                val outside = Files.createTempDirectory("link-share-upload-outside")
                val link = File(root.path).toPath().resolve("escape")
                try {
                    runCatching { Files.createSymbolicLink(link, outside) }.getOrNull() ?: return@withUploadSharedDirectory
                    val body = byteArrayOf(1, 2, 3)
                    val response = dispatcher(state).dispatch(
                        LinkShareHttpRequest.from(
                            method = "POST",
                            rawUri = "/api/share/upload?target=/${root.name}/escape&relativePath=hacked.bin&type=file&size=${body.size}",
                            headers = linkShareHeadersOf(
                                "User-Agent" to USER_AGENT,
                                "X-FolderSpan-Link-Session" to sessionToken,
                                "Cookie" to "FolderSpanLinkShareClient=$CLIENT_ID; FolderSpanLinkShareSession=$sessionToken",
                                "X-API-Request" to "true",
                                "Content-Length" to body.size.toString(),
                            ),
                            remoteHost = CLIENT_IP,
                            body = LinkShareHttpRequestBody.Bytes(body),
                        )
                    )

                    assertEquals(403, response.statusCode, response.bodyText())
                    assertFalse(outside.resolve("hacked.bin").toFile().exists())
                } finally {
                    Files.deleteIfExists(link)
                    outside.toFile().deleteRecursively()
                }
            }
        }
    }

    @Test
    fun contentUriShareRootRejectsUnregisteredRelativeSegmentsAndFailedLookupDownload() = runBlocking(Dispatchers.IO) {
        withTestKoin {
            val root = FileSimpleInfo(
                name = "shared-doc",
                isDirectory = false,
                isHidden = false,
                path = "content://com.example.docs/document/42",
                mineType = "application/pdf",
                size = 1L,
                createdDate = 0L,
                updatedDate = 0L,
            )
            val state = FileShareState()
            val session = state.issueLinkShareSession(
                clientId = CLIENT_ID,
                fingerprint = ShareTokenFingerprint(clientIp = CLIENT_IP, userAgent = USER_AGENT),
                allowHidden = false,
                files = listOf(root),
            )
            val dispatcher = dispatcher(state)

            assertTrue(dispatcher.isSharedPathSafeForTest(root, Local(), emptyList()))
            assertFalse(dispatcher.isSharedPathSafeForTest(root, Local(), listOf("other")))

            val extraSegmentResponse = dispatcher.dispatch(
                authorizedRequest("/${root.name}/other", session.token, api = true)
            )
            assertEquals(403, extraSegmentResponse.statusCode, extraSegmentResponse.bodyText())

            val exactResponse = dispatcher.dispatch(
                authorizedRequest("/${root.name}", session.token, api = true)
            )
            assertEquals(404, exactResponse.statusCode, exactResponse.bodyText())
            assertFalse(exactResponse.bodyText().contains("content://"))
        }
    }

    private suspend fun withTestKoin(block: suspend () -> Unit) {
        stopKoin()
        startKoin {
            modules(
                module {
                    single { NotificationState() }
                }
            )
        }
        try {
            block()
        } finally {
            stopKoin()
        }
    }

    private suspend fun withSharedDirectory(
        block: suspend (root: FileSimpleInfo, state: FileShareState) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val tempRoot = Files.createTempDirectory("link-share-root").toFile()
        try {
            val root = FileSimpleInfo(
                name = tempRoot.name,
                isDirectory = true,
                isHidden = false,
                path = tempRoot.absolutePath,
                mineType = "",
                size = 0L,
                createdDate = 0L,
                updatedDate = 0L,
            )
            block(root, FileShareState())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private suspend fun withSharedLocalFile(
        fileName: String,
        bytes: ByteArray,
        block: suspend (root: FileSimpleInfo, state: FileShareState, sessionToken: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val tempRoot = Files.createTempDirectory("link-share-preview-root").toFile()
        try {
            tempRoot.resolve(fileName).writeBytes(bytes)
            val root = FileSimpleInfo(
                name = tempRoot.name,
                isDirectory = true,
                isHidden = false,
                path = tempRoot.absolutePath,
                mineType = "",
                size = 0L,
                createdDate = 0L,
                updatedDate = 0L,
            )
            val state = FileShareState()
            val session = state.issueLinkShareSession(
                clientId = CLIENT_ID,
                fingerprint = ShareTokenFingerprint(clientIp = CLIENT_IP, userAgent = USER_AGENT),
                allowHidden = false,
                files = listOf(root),
            )
            block(root, state, session.token)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private suspend fun withSingleSharedLocalFile(
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
        block: suspend (state: FileShareState, sessionToken: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val tempRoot = Files.createTempDirectory("link-share-single-preview-root").toFile()
        try {
            val target = tempRoot.resolve(fileName).apply { writeBytes(bytes) }
            val file = FileSimpleInfo(
                name = fileName,
                isDirectory = false,
                isHidden = false,
                path = target.absolutePath,
                mineType = mimeType,
                size = bytes.size.toLong(),
                createdDate = 0L,
                updatedDate = 0L,
            )
            val state = FileShareState()
            val session = state.issueLinkShareSession(
                clientId = CLIENT_ID,
                fingerprint = ShareTokenFingerprint(clientIp = CLIENT_IP, userAgent = USER_AGENT),
                allowHidden = false,
                files = listOf(file),
            )
            block(state, session.token)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private suspend fun withHiddenSharedFile(
        block: suspend (root: FileSimpleInfo, state: FileShareState, sessionToken: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val tempRoot = Files.createTempDirectory("link-share-hidden-root").toFile()
        try {
            tempRoot.resolve(HIDDEN_FILE_NAME).writeText("secret")
            val root = FileSimpleInfo(
                name = tempRoot.name,
                isDirectory = true,
                isHidden = false,
                path = tempRoot.absolutePath,
                mineType = "",
                size = 0L,
                createdDate = 0L,
                updatedDate = 0L,
            )
            val state = FileShareState()
            val session = state.issueLinkShareSession(
                clientId = CLIENT_ID,
                fingerprint = ShareTokenFingerprint(clientIp = CLIENT_IP, userAgent = USER_AGENT),
                allowHidden = false,
                files = listOf(root),
            )
            block(root, state, session.token)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private suspend fun withUploadSharedDirectory(
        block: suspend (root: FileSimpleInfo, state: FileShareState, sessionToken: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val tempRoot = Files.createTempDirectory("link-share-upload-root").toFile()
        try {
            val root = FileSimpleInfo(
                name = tempRoot.name,
                isDirectory = true,
                isHidden = false,
                path = tempRoot.absolutePath,
                mineType = "",
                size = 0L,
                createdDate = 0L,
                updatedDate = 0L,
            )
            val state = FileShareState()
            state.updateAllowUpload(true)
            val session = state.issueLinkShareSession(
                clientId = CLIENT_ID,
                fingerprint = ShareTokenFingerprint(clientIp = CLIENT_IP, userAgent = USER_AGENT),
                allowHidden = false,
                files = listOf(root),
            )
            block(root, state, session.token)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun dispatcher(
        state: FileShareState,
        passwordAttemptLimiter: LinkSharePasswordAttemptLimiter = LinkSharePasswordAttemptLimiter(),
        passwordVerifier: LinkSharePasswordVerifier = LinkSharePasswordVerifier(iterations = 1),
        advertisedHosts: Set<String> = setOf("127.0.0.1", "localhost"),
    ): LinkShareRouteDispatcher {
        return LinkShareRouteDispatcher(
            fileShareState = state,
            platformFileResponder = object : LinkSharePlatformFileResponder {
                override suspend fun downloadLocalFile(
                    filePath: String,
                    file: FileSimpleInfo?,
                    request: LinkShareHttpRequest,
                    delivery: LinkShareFileDelivery,
                ): LinkShareHttpResponse {
                    val target = File(filePath)
                    if (!target.exists()) return LinkShareHttpResponse.bytes(statusCode = 404)
                    val bytes = target.readBytes()
                    val presentation = resolveLinkShareFilePresentation(
                        fileName = target.name,
                        requestedDelivery = delivery,
                        fileMimeTypeHint = file?.mineType,
                        platformMimeTypeHint = Files.probeContentType(target.toPath()),
                    )
                    val range = parseTestRangeHeader(request.header("Range"), bytes.size.toLong())
                    val responseBytes = range?.let { item ->
                        bytes.copyOfRange(item.first.toInt(), item.last.toInt() + 1)
                    } ?: bytes
                    val headers = presentation.headers.toMutableList()
                    if (range != null) {
                        headers += LinkShareHttpHeader(
                            "Content-Range",
                            "bytes ${range.first}-${range.last}/${bytes.size}",
                        )
                    }
                    return LinkShareHttpResponse.bytes(
                        statusCode = if (range == null) 200 else 206,
                        bytes = responseBytes,
                        contentType = presentation.contentType,
                        headers = headers,
                    )
                }
            },
            passwordAttemptLimiter = passwordAttemptLimiter,
            passwordVerifier = passwordVerifier,
            advertisedHostProvider = { advertisedHosts },
        )
    }

    private fun sharedFileRequest(
        root: FileSimpleInfo,
        fileName: String,
        sessionToken: String,
        preview: Boolean = false,
        api: Boolean = false,
        range: String? = null,
    ): LinkShareHttpRequest {
        val query = if (preview) "?preview=1" else ""
        return authorizedRequest(
            rawUri = "/${root.name}/$fileName$query",
            sessionToken = sessionToken,
            api = api,
            range = range,
        )
    }

    private fun authorizedRequest(
        rawUri: String,
        sessionToken: String,
        api: Boolean = false,
        range: String? = null,
        acceptLanguage: String? = null,
    ): LinkShareHttpRequest {
        val headers = buildList {
            add("User-Agent" to USER_AGENT)
            add("X-FolderSpan-Link-Session" to sessionToken)
            add("Cookie" to "FolderSpanLinkShareClient=$CLIENT_ID; FolderSpanLinkShareSession=$sessionToken")
            if (api) add("X-API-Request" to "true")
            if (range != null) add("Range" to range)
            if (acceptLanguage != null) add("Accept-Language" to acceptLanguage)
        }
        return LinkShareHttpRequest.from(
            method = "GET",
            rawUri = rawUri,
            headers = linkShareHeadersOf(*headers.toTypedArray()),
            remoteHost = CLIENT_IP,
        )
    }

    private fun linkShareRequest(
        root: FileSimpleInfo,
        sessionToken: String,
        api: Boolean,
        preview: Boolean = false,
    ): LinkShareHttpRequest {
        val headers = buildList {
            add("User-Agent" to USER_AGENT)
            add("X-FolderSpan-Link-Session" to sessionToken)
            add("Cookie" to "FolderSpanLinkShareClient=$CLIENT_ID; FolderSpanLinkShareSession=$sessionToken")
            if (api) add("X-API-Request" to "true")
        }
        return LinkShareHttpRequest.from(
            method = "GET",
            rawUri = "/${root.name}/$HIDDEN_FILE_NAME${if (preview) "?preview=1" else ""}",
            headers = linkShareHeadersOf(*headers.toTypedArray()),
            remoteHost = CLIENT_IP,
        )
    }

    private fun rootRequest(
        clientId: String = CLIENT_ID,
        api: Boolean = false,
    ): LinkShareHttpRequest {
        val headers = buildList {
            add("User-Agent" to USER_AGENT)
            add("Cookie" to "FolderSpanLinkShareClient=$clientId")
            if (api) add("X-API-Request" to "true")
        }
        return LinkShareHttpRequest.from(
            method = "GET",
            rawUri = "/",
            headers = linkShareHeadersOf(*headers.toTypedArray()),
            remoteHost = CLIENT_IP,
        )
    }

    private fun passwordAuthRequest(
        password: String,
        clientId: String = CLIENT_ID,
        rawUri: String = "/auth",
        remoteHost: String = CLIENT_IP,
        origin: String? = "http://127.0.0.1",
        host: String = "127.0.0.1",
        port: Int = 80,
        scheme: String = "http",
        api: Boolean = false,
    ): LinkShareHttpRequest {
        val body = "pwd=$password&redirect=%2F".encodeToByteArray()
        val headers = buildList {
            add("User-Agent" to USER_AGENT)
            add("Cookie" to "FolderSpanLinkShareClient=$clientId")
            add("Content-Length" to body.size.toString())
            if (origin != null) add("Origin" to origin)
            if (api) add("X-API-Request" to "true")
        }
        return LinkShareHttpRequest.from(
            method = "POST",
            rawUri = rawUri,
            headers = linkShareHeadersOf(*headers.toTypedArray()),
            remoteHost = remoteHost,
            scheme = scheme,
            host = host,
            port = port,
            body = LinkShareHttpRequestBody.Bytes(body),
        )
    }

    private fun uploadChunkRequest(
        root: FileSimpleInfo,
        sessionToken: String,
        fileName: String,
        totalSize: Int,
        start: Long,
        chunk: ByteArray,
    ): LinkShareHttpRequest {
        val endInclusive = start + chunk.size - 1
        return LinkShareHttpRequest.from(
            method = "POST",
            rawUri = "/api/share/upload?target=/${root.name}&relativePath=$fileName&type=file&size=$totalSize",
            headers = linkShareHeadersOf(
                "User-Agent" to USER_AGENT,
                "X-FolderSpan-Link-Session" to sessionToken,
                "Cookie" to "FolderSpanLinkShareClient=$CLIENT_ID; FolderSpanLinkShareSession=$sessionToken",
                "X-API-Request" to "true",
                "Content-Length" to chunk.size.toString(),
                "Content-Range" to "bytes $start-$endInclusive/$totalSize",
            ),
            remoteHost = CLIENT_IP,
            body = LinkShareHttpRequestBody.Bytes(chunk),
        )
    }

    private fun uploadCheckRequest(
        root: FileSimpleInfo,
        sessionToken: String,
        fileName: String = UPLOAD_FILE_NAME,
    ): LinkShareHttpRequest {
        return LinkShareHttpRequest.from(
            method = "GET",
            rawUri = "/api/share/upload-check?target=/${root.name}&relativePath=$fileName&type=file&size=0",
            headers = linkShareHeadersOf(
                "User-Agent" to USER_AGENT,
                "X-FolderSpan-Link-Session" to sessionToken,
                "Cookie" to "FolderSpanLinkShareClient=$CLIENT_ID; FolderSpanLinkShareSession=$sessionToken",
                "X-API-Request" to "true",
            ),
            remoteHost = CLIENT_IP,
        )
    }

    private fun uploadPermissionRequest(sessionToken: String): LinkShareHttpRequest {
        return LinkShareHttpRequest.from(
            method = "POST",
            rawUri = "/api/share/upload-permission/request",
            headers = linkShareHeadersOf(
                "User-Agent" to USER_AGENT,
                "X-FolderSpan-Link-Session" to sessionToken,
                "Cookie" to "FolderSpanLinkShareClient=$CLIENT_ID; FolderSpanLinkShareSession=$sessionToken",
                "X-API-Request" to "true",
            ),
            remoteHost = CLIENT_IP,
        )
    }

    private fun uploadCancelRequest(
        root: FileSimpleInfo,
        sessionToken: String,
        fileName: String,
        totalSize: Int,
    ): LinkShareHttpRequest {
        return LinkShareHttpRequest.from(
            method = "POST",
            rawUri = "/api/share/upload-cancel?target=/${root.name}&relativePath=$fileName&type=file&size=$totalSize",
            headers = linkShareHeadersOf(
                "User-Agent" to USER_AGENT,
                "X-FolderSpan-Link-Session" to sessionToken,
                "Cookie" to "FolderSpanLinkShareClient=$CLIENT_ID; FolderSpanLinkShareSession=$sessionToken",
                "X-API-Request" to "true",
            ),
            remoteHost = CLIENT_IP,
        )
    }

    private fun LinkShareHttpResponse.bodyText(): String {
        return when (val responseBody = body) {
            is LinkShareHttpResponseBody.Bytes -> responseBody.bytes.decodeToString()
            LinkShareHttpResponseBody.Empty -> ""
            is LinkShareHttpResponseBody.Stream -> "<stream>"
        }
    }

    private class RecordingResponseWriter : LinkShareHttpResponseBodyWriter {
        private val chunks = mutableListOf<ByteArray>()

        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
            chunks += bytes.copyOfRange(offset, offset + length)
        }

        override suspend fun flush() = Unit

        fun bytes(): ByteArray = chunks.fold(byteArrayOf()) { result, chunk -> result + chunk }
    }

    private fun assertRejectedHostSecurityHeaders(response: LinkShareHttpResponse) {
        assertEquals("nosniff", response.header("X-Content-Type-Options"))
        assertEquals("DENY", response.header("X-Frame-Options"))
        assertContains(response.header("Content-Security-Policy").orEmpty(), "connect-src 'self'")
    }

    private fun parseTestRangeHeader(header: String?, fileSize: Long): LongRange? {
        if (header == null || fileSize <= 0L || !header.startsWith("bytes=")) return null
        val rangeParts = header.removePrefix("bytes=").substringBefore(',').split('-', limit = 2)
        if (rangeParts.size != 2) return null
        val start = rangeParts[0].toLongOrNull() ?: return null
        val endInclusive = rangeParts[1].toLongOrNull() ?: (fileSize - 1)
        if (start < 0L || start >= fileSize || endInclusive < start) return null
        return start..minOf(endInclusive, fileSize - 1)
    }
}

private const val CLIENT_ID = "clienthidden0000"
private const val UNKNOWN_CLIENT_ID = "attackerclient000"
private const val CLIENT_IP = "127.0.0.1"
private const val ATTACKER_IP = "10.0.0.99"
private const val USER_AGENT = "Mozilla/5.0"
private const val ATTACKER_USER_AGENT = "Mozilla/5.0 (attacker)"
private const val LINK_PASSWORD = "secret"
private const val HIDDEN_FILE_NAME = ".secret.txt"
private const val UPLOAD_FILE_NAME = "chunked.bin"
