package com.folderspan.service.http.server.linkshare

import com.folderspan.utils.FileAccessPermission
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.localization.ResolvedAppLanguage
import com.folderspan.localization.resolveBrowserLanguage
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareProtocol
import com.folderspan.extensions.parsePath
import com.folderspan.extensions.withSharedProtocol
import com.folderspan.service.file.DeviceFileClient
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.MAX_LENGTH
import com.folderspan.service.http.clipboard.ClipboardUrlShareSource
import com.folderspan.service.http.clipboard.ClipboardUrlShareSourceRegistry
import com.folderspan.service.http.server.AdvertisedHostProvider
import com.folderspan.service.http.server.DefaultAdvertisedHostProvider
import com.folderspan.service.http.server.LinkSharePageConfig
import com.folderspan.service.http.server.buildLinkShareBaseUrl
import com.folderspan.service.http.server.buildLinkShareHttpsConsentKey
import com.folderspan.service.http.server.isAdvertisedOrLoopbackHost
import com.folderspan.service.http.server.resolvePublicHttpHost
import com.folderspan.service.mcp.http.normalizeHostHeader
import com.folderspan.service.http.server.templates.HtmlTemplates
import com.folderspan.service.http.server.templates.PageAssets
import com.folderspan.service.http.server.templates.ThemeConfigScriptBuilder
import com.folderspan.service.http.server.templates.SvgIcon
import com.folderspan.service.http.server.templates.route.BreadcrumbItem
import com.folderspan.service.http.server.templates.svgIconDefinition
import com.folderspan.service.http.tls.currentDeviceTlsFingerprint
import com.folderspan.service.path.defaultDirectoryListLimiter
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.file.LinkShareAuthorization
import com.folderspan.ui.state.file.LinkShareSession
import com.folderspan.ui.state.file.LinkShareUploadPermissionRequestResult
import com.folderspan.ui.state.file.ShareTokenFingerprint
import com.folderspan.ui.state.main.MainState
import com.folderspan.ui.theme.getDefaultColorScheme
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.NaturalOrderComparator
import com.folderspan.utils.PathUtils
import com.folderspan.utils.parseBrowser
import com.folderspan.utils.parseOperatingSystem
import com.folderspan.shared.generated.resources.Res
import strings.AppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

interface LinkSharePlatformFileResponder {
    suspend fun downloadLocalFile(
        filePath: String,
        file: FileSimpleInfo?,
        request: LinkShareHttpRequest,
        delivery: LinkShareFileDelivery,
    ): LinkShareHttpResponse
}

private const val LINK_SHARE_LOCAL_LIST_MAX_CONCURRENT_REQUESTS = 24
private val linkShareLocalListLimiter = defaultDirectoryListLimiter(
    maxConcurrency = LINK_SHARE_LOCAL_LIST_MAX_CONCURRENT_REQUESTS,
)

private suspend fun listLocalSharedFiles(path: String): Result<List<FileSimpleInfo>> {
    return linkShareLocalListLimiter.withPermit {
        withContext(Dispatchers.Default) {
            PathUtils.getFileAndFolder(FileAccessPermission.Allowed, path)
        }
    }
}

class LinkShareRouteDispatcher(
    private val fileShareState: FileShareState,
    private val platformFileResponder: LinkSharePlatformFileResponder,
    private val passwordAttemptLimiter: LinkSharePasswordAttemptLimiter = LinkSharePasswordAttemptLimiter(),
    private val passwordVerifier: LinkSharePasswordVerifier = LinkSharePasswordVerifier(),
    private val advertisedHostProvider: AdvertisedHostProvider = DefaultAdvertisedHostProvider,
) : KoinComponent {
    private val mainState: MainState by inject()
    private val themeCssMutex = Mutex()
    private var cachedThemeCss: CachedThemeConfigCss? = null
    private val uploadCleanupMutex = Mutex()
    private val cancellableUploadPaths = mutableSetOf<String>()
    private var configuredHttpPort: Int? = null
    private var configuredHttpsPort: Int? = null

    private val router = LinkShareHttpRouter.build {
        exact("GET", "/favicon.ico") { exchange -> handleFavicon(exchange) }
        prefix("GET", "/static") { exchange -> handleStatic(exchange) }
        exact("POST", LINK_SHARE_AUTH_PATH) { exchange -> handlePasswordAuth(exchange) }
        exact("GET", "/api/share/upload-check") { exchange -> handleShareUploadCheck(exchange) }
        exact("POST", "/api/share/upload-permission/request") { exchange -> handleShareUploadPermissionRequest(exchange) }
        exact("POST", "/api/share/upload") { exchange -> handleShareUpload(exchange) }
        exact("POST", "/api/share/upload-cancel") { exchange -> handleShareUploadCancel(exchange) }
        root("GET") { exchange -> handleRoot(exchange) }
        fallback("GET") { exchange -> handleSharedPath(exchange) }
    }

    fun configurePorts(httpPort: Int, httpsPort: Int?) {
        configuredHttpPort = httpPort.takeIf { item -> item in 1..65535 }
        configuredHttpsPort = httpsPort?.takeIf { item -> item in 1..65535 }
    }

    suspend fun dispatch(request: LinkShareHttpRequest): LinkShareHttpResponse {
        val corsPreflight = LinkShareHttpHeaderPolicies.corsPreflightResponse(
            request,
            advertisedHostProvider.get(),
        )
        if (corsPreflight != null) {
            return corsPreflight.withCommonHeaders(request)
        }
        rejectedLinkShareHostResponse(request)?.let { response ->
            return response.withCommonHeaders(request)
        }

        return try {
            val pageConfig = request.buildLinkSharePageConfig()
            val exchange = LinkShareHttpExchange(request)
            if (requiresLinkShareDeviceGuard(request)) {
                val guardResponse = prepareLinkShareAccess(exchange, pageConfig)
                if (guardResponse != null) {
                    return guardResponse.withExchangeCookies(exchange).withCommonHeaders(request)
                }
            }
            router.dispatch(exchange).withExchangeCookies(exchange).withCommonHeaders(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            LogKit.e(AppStrings.ui_link_sharing_request_processing_exception_arg0.format(arg0 = (error.message).toString()), error)
            LinkShareHttpResponse.text(500, AppStrings.ui_service_handles_exceptions).withCommonHeaders(request)
        }
    }

    private fun requiresLinkShareDeviceGuard(request: LinkShareHttpRequest): Boolean {
        return request.path != "/favicon.ico" && !request.path.startsWith("/static/")
    }

    private fun rejectedLinkShareHostResponse(request: LinkShareHttpRequest): LinkShareHttpResponse? {
        val host = request.host.trim()
        if (host.isEmpty()) return LinkShareHttpResponse.bytes(statusCode = 403)
        val normalized = normalizeHostHeader(host) ?: return LinkShareHttpResponse.bytes(statusCode = 403)
        return if (isAdvertisedOrLoopbackHost(normalized, advertisedHostProvider.get())) {
            null
        } else {
            LinkShareHttpResponse.bytes(statusCode = 403)
        }
    }

    private suspend fun prepareLinkShareAccess(
        exchange: LinkShareHttpExchange,
        pageConfig: LinkSharePageConfig,
    ): LinkShareHttpResponse? {
        val request = exchange.request
        val rawDevice = request.getClientDeviceInfo()
        if (rawDevice == null) {
            LogKit.w(AppStrings.ui_link_to_share_device_information_invalid_missing_user_agent)
            return LinkShareHttpResponse.bytes(statusCode = 403)
        }

        val clientId = request.resolveLinkShareClientId()
        val device = rawDevice.copy(id = clientId)
        exchange.put(LINK_SHARE_DEVICE_KEY, device)
        exchange.put(LINK_SHARE_PENDING_COOKIE_KEY, linkShareCookie(
            request = request,
            name = LINK_SHARE_CLIENT_COOKIE,
            value = clientId,
            maxAge = LINK_SHARE_CLIENT_COOKIE_MAX_AGE_SECONDS
        ))

        if (fileShareState.isRejectedLinkShareDevice(device)) {
            LogKit.w(AppStrings.ui_request_to_share_device_was_denied_arg0.format(arg0 = (device).toString()))
            return if (request.isApiRequest()) {
                LinkShareHttpResponse.text(403, AppStrings.ui_access_request_denied)
            } else {
                val pageAssets = getPageAssets()
                htmlResponse(403) {
                    HtmlTemplates.requestRejectedPage(assets = pageAssets)(this)
                }
            }
        }

        if (request.path == LINK_SHARE_AUTH_PATH) {
            return null
        }

        val fingerprint = request.buildLinkShareTokenFingerprint()
        val redeemedTicket = fileShareState.consumeLinkShareTicket(request.query(LINK_SHARE_TICKET_QUERY))
        if (redeemedTicket != null) {
            val session = fileShareState.issueLinkShareSession(
                clientId = device.id,
                fingerprint = fingerprint,
                allowHidden = redeemedTicket.allowHidden,
                allowUpload = redeemedTicket.allowUpload,
                files = redeemedTicket.files
            )
            fileShareState.authorizeLinkShareDevice(
                device = device,
                access = redeemedTicket.access,
                revokeExistingSessions = false,
                fingerprint = fingerprint,
            )
            exchange.put(LINK_SHARE_PENDING_SESSION_COOKIE_KEY, linkShareSessionCookie(request, session.token))
            fileShareState.removePendingLinkShareDevice(device.id)
            LogKit.i(AppStrings.ui_link_sharing_ticket_has_been_exchanged_clientid_arg0.format(arg0 = (device.id)))
            return LinkShareHttpResponse.redirect(sanitizeLinkShareRedirect(request.path))
        }

        val session = request.resolveLinkShareSession(device.id, fingerprint)
        if (session != null) {
            fileShareState.authorizeLinkShareDevice(
                device = device,
                access = session.access,
                revokeExistingSessions = false,
                clearUploadRequestState = false,
                fingerprint = fingerprint,
            )
            exchange.put(LINK_SHARE_AUTHORIZATION_KEY, session.authorization())
            exchange.put(LINK_SHARE_SESSION_TOKEN_KEY, session.token)
            fileShareState.removePendingLinkShareDevice(device.id)
            return null
        }

        val approvedAccess = fileShareState.resolveAuthorizedLinkShareDevice(device.id, fingerprint)
        if (approvedAccess != null) {
            val approvedSession = fileShareState.issueLinkShareSession(
                clientId = device.id,
                fingerprint = fingerprint,
                allowHidden = approvedAccess.allowHidden,
                allowUpload = approvedAccess.allowUpload,
                files = approvedAccess.files,
            )
            exchange.put(
                LINK_SHARE_PENDING_SESSION_COOKIE_KEY,
                linkShareSessionCookie(request, approvedSession.token),
            )
            exchange.put(LINK_SHARE_AUTHORIZATION_KEY, approvedSession.authorization())
            exchange.put(LINK_SHARE_SESSION_TOKEN_KEY, approvedSession.token)
            fileShareState.removePendingLinkShareDevice(device.id)
            LogKit.i(AppStrings.ui_link_sharing_has_been_approved_and_issued_session_clientid_arg0.format(arg0 = (device.id)))
            return null
        }

        if (fileShareState.autoApprove.value) {
            val defaults = fileShareState.resolveLinkShareDefaults()
            val autoSession = fileShareState.issueLinkShareSession(
                clientId = device.id,
                fingerprint = fingerprint,
                allowHidden = defaults.allowHidden,
                allowUpload = defaults.allowUpload,
                files = defaults.files
            )
            fileShareState.authorizeLinkShareDevice(
                device = device,
                allowHidden = defaults.allowHidden,
                allowUpload = defaults.allowUpload,
                files = defaults.files,
                revokeExistingSessions = false,
                fingerprint = fingerprint,
            )
            exchange.put(LINK_SHARE_PENDING_SESSION_COOKIE_KEY, linkShareSessionCookie(request, autoSession.token))
            exchange.put(LINK_SHARE_AUTHORIZATION_KEY, autoSession.authorization())
            exchange.put(LINK_SHARE_SESSION_TOKEN_KEY, autoSession.token)
            LogKit.i(AppStrings.ui_automatic_approval_of_link_sharing_and_session_signing_clientid_arg0.format(arg0 = (device.id)))
            return null
        }

        if (fileShareState.connectPassword.value.isNotEmpty()) {
            return if (request.isApiRequest()) {
                LogKit.w(AppStrings.ui_link_share_unauthenticated_api)
                LinkShareHttpResponse.text(401, AppStrings.ui_enter_the_correct_password)
            } else {
                LogKit.w(AppStrings.ui_link_sharing_without_authentication_html)
                respondPasswordPage(AppStrings.ui_enter_the_correct_password, request.path)
            }
        }

        if (fileShareState.pendingLinkShareDevices.none { item -> item.id == device.id }) {
            fileShareState.addPendingLinkShareDevice(
                device = device,
                sourceHost = request.remoteHost,
                fingerprint = fingerprint,
            )
            LogKit.d(AppStrings.ui_add_the_list_of_authorized_items_arg0.format(arg0 = (device).toString()))
        }
        return null
    }

    private suspend fun handlePasswordAuth(exchange: LinkShareHttpExchange): LinkShareHttpResponse {
        val request = exchange.request
        val origin = request.header("Origin")
        if (
            origin.isNullOrBlank() ||
            !LinkShareHttpHeaderPolicies.isSameOrigin(
                origin,
                request.host,
                request.port,
                request.scheme,
                advertisedHostProvider.get(),
            )
        ) {
            return LinkShareHttpResponse.text(403, AppStrings.error_auth_request_origin_invalid)
        }
        val clientRequest = exchange.getOrNull(LINK_SHARE_DEVICE_KEY)
            ?: return LinkShareHttpResponse.text(403, AppStrings.ui_device_information_invalid)
        val form = parseLinkShareQueryParameters(request.readBodyText())
        val redirectTo = sanitizeLinkShareRedirect(form["redirect"]?.firstOrNull())
        val password = form["pwd"]?.firstOrNull().orEmpty()
        val expectedPassword = fileShareState.connectPassword.value
        val clientKey = request.remoteHost.orEmpty()
        if (expectedPassword.isEmpty()) {
            LogKit.w(AppStrings.ui_link_to_share_password_error)
            return respondPasswordPage(AppStrings.ui_enter_the_correct_password, redirectTo)
        }
        passwordAttemptLimiter.tryAcquireAttempt(clientKey)?.let { lockout ->
            LogKit.w(AppStrings.ui_link_to_share_password_attempt_locked)
            return respondPasswordThrottled(request, redirectTo, lockout)
        }
        val passwordMatches = passwordAttemptLimiter.withAttemptSlot(clientKey) {
            passwordVerifier.matches(expectedPassword, password)
        }
        if (passwordMatches) {
            passwordAttemptLimiter.registerSuccess(clientKey)
        } else {
            val lockout = passwordAttemptLimiter.lockoutOrNull(clientKey)
            if (lockout != null) {
                LogKit.w(AppStrings.ui_link_to_share_password_attempt_locked)
                return respondPasswordThrottled(request, redirectTo, lockout)
            }
            LogKit.w(AppStrings.ui_link_to_share_password_error)
            return respondPasswordPage(AppStrings.ui_enter_the_correct_password, redirectTo)
        }

        val defaults = fileShareState.resolveLinkShareDefaults()
        val session = fileShareState.issueLinkShareSession(
            clientId = clientRequest.id,
            fingerprint = request.buildLinkShareTokenFingerprint(),
            allowHidden = defaults.allowHidden,
            allowUpload = defaults.allowUpload,
            files = defaults.files
        )
        fileShareState.authorizeLinkShareDevice(
            device = clientRequest,
            allowHidden = defaults.allowHidden,
            allowUpload = defaults.allowUpload,
            files = defaults.files,
            revokeExistingSessions = false,
            fingerprint = request.buildLinkShareTokenFingerprint(),
        )
        exchange.put(LINK_SHARE_PENDING_SESSION_COOKIE_KEY, linkShareSessionCookie(request, session.token))
        fileShareState.removePendingLinkShareDevice(clientRequest.id)
        LogKit.d(AppStrings.ui_link_to_share_password_validation_and_session_issuance_clientid_arg0.format(arg0 = (clientRequest.id)))
        return LinkShareHttpResponse.redirect(redirectTo)
    }

    private suspend fun handleRoot(exchange: LinkShareHttpExchange): LinkShareHttpResponse {
        val request = exchange.request
        val clientRequest = exchange.getOrNull(LINK_SHARE_DEVICE_KEY) ?: request.getClientDeviceInfo()
            ?: return LinkShareHttpResponse.bytes(statusCode = 403)
        LogKit.i(AppStrings.ui_get_from_device_id_arg0_name_arg1_type_arg2.format(arg0 = (clientRequest.id), arg1 = (clientRequest.name), arg2 = (clientRequest.type).toString()))
        fileShareState.deviceRequestLogFor(clientRequest).addPath("/")

        val sharedFileInfo = exchange.getOrNull(LINK_SHARE_AUTHORIZATION_KEY)
        val files = sharedFileInfo?.files?.let { fileShareState.filterShareableFiles(it) } ?: listOf()
        if (files.isEmpty() && fileShareState.connectPassword.value.isEmpty()) {
            if (request.isApiRequest()) {
                LogKit.d(AppStrings.ui_preparing_files_api)
                return LinkShareHttpResponse.text(401, AppStrings.ui_file_preparation_in_progress)
            }
            LogKit.d(AppStrings.ui_preparing_files_html)
            val pageAssets = getPageAssets()
            return htmlResponse {
                HtmlTemplates.waitingPage(assets = pageAssets)(this)
            }
        }

        val search = request.query("search")
        val visibleFiles = files.filter { file ->
            val searchMatch = file.name.contains(search.orEmpty())
            val visibilityMatch = sharedFileInfo?.allowHidden == true || !file.isHidden
            searchMatch && visibilityMatch
        }
        if (!request.isApiRequest() && search.isNullOrEmpty() && visibleFiles.size == 1) {
            val sharedRoot = visibleFiles.first()
            if (sharedRoot.isDirectory) {
                LogKit.d(AppStrings.ui_open_directory_arg0.format(arg0 = (sharedRoot.path)))
                return LinkShareHttpResponse.redirect("/${sharedRoot.name}".urlPath())
            }

            val shareDisk = fileShareState.resolveLinkShareDisk(sharedRoot)
            if (shareDisk != null && fileShareState.isLinkShareFileAllowed(sharedRoot)) {
                LogKit.i(AppStrings.ui_share_file_in_browser_arg0.format(arg0 = (sharedRoot.path)))
                return downloadSharedFile(
                    root = sharedRoot,
                    disk = shareDisk,
                    file = sharedRoot,
                    path = sharedRoot.path,
                    request = request,
                    delivery = LinkShareFileDelivery.Preview,
                )
            }
        }

        LogKit.d(AppStrings.ui_root_directory_list_count_arg0.format(arg0 = (visibleFiles.size).toString()))
        return respondByRequestType(
            exchange = exchange,
            search = search,
            files = visibleFiles
                .map { file -> file.withCopy(path = "/${file.name}".urlPath()) }
                .sortedWith(compareByDescending<FileSimpleInfo> { item -> item.isDirectory }.then(NaturalOrderComparator()))
        )
    }

    private suspend fun handleSharedPath(exchange: LinkShareHttpExchange): LinkShareHttpResponse {
        val request = exchange.request
        val clientRequest = exchange.getOrNull(LINK_SHARE_DEVICE_KEY) ?: request.getClientDeviceInfo()
            ?: return LinkShareHttpResponse.bytes(statusCode = 403)
        val urlPath = request.path
        LogKit.i(AppStrings.ui_get_arg0_from_device_id_arg1_name_arg2_type_arg3.format(arg0 = (urlPath), arg1 = (clientRequest.id), arg2 = (clientRequest.name), arg3 = (clientRequest.type).toString()))
        fileShareState.deviceRequestLogFor(clientRequest).addPath(urlPath)

        val authorizedFiles = exchange.getOrNull(LINK_SHARE_AUTHORIZATION_KEY)
        if (authorizedFiles == null) {
            LogKit.w(AppStrings.ui_redirect_to_root_directory_arg0.format(arg0 = (urlPath)))
            return if (request.isApiRequest()) {
                LinkShareHttpResponse.text(403, AppStrings.ui_unauthorized_access)
            } else {
                LinkShareHttpResponse.redirect("/")
            }
        }

        val rawParentFileSimpleInfo = authorizedFiles.files.find { item -> urlPath.matchesSharedRoot(item) }
        if (rawParentFileSimpleInfo != null && !fileShareState.isLinkShareFileAllowed(rawParentFileSimpleInfo)) {
            LogKit.w(AppStrings.ui_the_file_cannot_be_shared_arg0.format(arg0 = (urlPath)))
            return respondShareForbidden(AppStrings.ui_no_right_to_share_this_path)
        }

        val files = fileShareState.filterShareableFiles(authorizedFiles.files)
        val parentFileSimpleInfo = files.find { item -> urlPath.matchesSharedRoot(item) }
        if (parentFileSimpleInfo == null) {
            LogKit.w(AppStrings.ui_the_path_does_not_exist_redirecting_to_the_root_directory_arg0.format(arg0 = (urlPath)))
            return if (request.isApiRequest()) {
                LinkShareHttpResponse.text(404, AppStrings.ui_path_does_not_exist)
            } else {
                LinkShareHttpResponse.redirect("/")
            }
        }
        val shareDisk = fileShareState.resolveLinkShareDisk(parentFileSimpleInfo)
        if (!fileShareState.isLinkShareFileAllowed(parentFileSimpleInfo) || shareDisk == null) {
            LogKit.w(AppStrings.ui_link_sharing_disk_unavailable_or_no_permissions_arg0.format(arg0 = (urlPath)))
            return respondShareForbidden(AppStrings.ui_no_right_to_share_this_path)
        }

        val pathSegments = urlPath.parsePath()
        val relativeSegments = resolveSharedRelativeSegments(parentFileSimpleInfo, pathSegments)
            ?: return LinkShareHttpResponse.text(400, AppStrings.ui_path_invalid)
        val path = buildSharedPath(parentFileSimpleInfo.path, relativeSegments, shareDisk.pathSeparator)
        if (!isSharedPathSafe(parentFileSimpleInfo, shareDisk, relativeSegments)) {
            LogKit.w(AppStrings.ui_link_to_share_path_over_shared_root_or_containing_symbolic_link_arg0.format(arg0 = (path)))
            return respondShareForbidden(AppStrings.ui_the_path_cannot_be_accessed)
        }
        val fileSimpleInfoResult = getSharedFileInfo(
            root = parentFileSimpleInfo,
            disk = shareDisk,
            path = path,
            relativeSegments = relativeSegments
        ).getOrNull()

        if (fileSimpleInfoResult == null) {
            if (request.isApiRequest()) {
                LogKit.w(AppStrings.ui_api_request_file_does_not_exist_arg0.format(arg0 = (path)))
                return LinkShareHttpResponse.text(404, AppStrings.ui_file_does_not_exist)
            }
            LogKit.w(AppStrings.ui_redirecting_to_root_directory_arg0.format(arg0 = (path)))
            return LinkShareHttpResponse.redirect("/")
        }
        val resolvedPath = fileSimpleInfoResult.path

        if (!authorizedFiles.allowHidden && containsHiddenSharedPath(
                root = parentFileSimpleInfo,
                disk = shareDisk,
                relativeSegments = relativeSegments
            )
        ) {
            LogKit.w(AppStrings.ui_unable_to_access_hidden_file_arg0.format(arg0 = (urlPath)))
            return respondHiddenFileForbidden(request)
        }

        if (!fileSimpleInfoResult.isDirectory) {
            LogKit.i(AppStrings.ui_download_file_arg0.format(arg0 = (fileSimpleInfoResult.path)))
            return downloadSharedFile(
                root = parentFileSimpleInfo,
                disk = shareDisk,
                file = fileSimpleInfoResult,
                path = resolvedPath,
                request = request,
                delivery = request.resolveFileDelivery(),
            )
        }

        val search = request.query("search")
        val fileSimpleInfos = getSharedFileList(parentFileSimpleInfo, shareDisk, resolvedPath)
            .getOrDefault(listOf())
            .filter { file ->
                val searchMatch = file.name.contains(search.orEmpty())
                val visibilityMatch = authorizedFiles.allowHidden || !file.isHidden
                searchMatch && visibilityMatch
            }
            .map { file -> file.withCopy(path = "$urlPath/${file.name}".urlPath()) }
            .sortedWith(compareByDescending<FileSimpleInfo> { item -> item.isDirectory }.then(NaturalOrderComparator()))

        LogKit.d(AppStrings.ui_directory_browsing_path_arg0.format(arg0 = (path)))
        return respondByRequestType(exchange, search, fileSimpleInfos)
    }

    private fun handleFavicon(exchange: LinkShareHttpExchange): LinkShareHttpResponse {
        val favicon = buildFaviconResource()
        val incomingEtag = exchange.request.header("If-None-Match")
        if (incomingEtag != null && incomingEtag == favicon.etag) {
            return LinkShareHttpResponse.bytes(statusCode = 304)
        }
        return LinkShareHttpResponse.bytes(
            bytes = favicon.bytes,
            contentType = favicon.contentType,
            headers = listOf(
                LinkShareHttpHeader("Cache-Control", "public, max-age=86400, immutable"),
                LinkShareHttpHeader("ETag", favicon.etag),
                LinkShareHttpHeader("Content-Length", favicon.bytes.size.toString()),
            ),
        )
    }

    private suspend fun handleStatic(exchange: LinkShareHttpExchange): LinkShareHttpResponse {
        val staticPath = resolveLinkShareStaticRelativePath(exchange.request.path)
            ?: return LinkShareHttpResponse.bytes(statusCode = 404)
        if (staticPath == "theme-config.css") {
            return try {
                LinkShareHttpResponse.text(
                    text = getThemeConfigCss(),
                    contentType = "text/css",
                    headers = LinkShareHttpHeaderPolicies.staticResponseHeaders(staticPath),
                )
            } catch (error: Throwable) {
                LogKit.e(AppStrings.ui_failed_to_generate_theme_style_arg0.format(arg0 = (error.message).toString()), error)
                LinkShareHttpResponse.bytes(statusCode = 500)
            }
        }

        val resource = loadStaticResource(staticPath) ?: return LinkShareHttpResponse.bytes(statusCode = 404)
        val incomingEtag = exchange.request.header("If-None-Match")
        if (incomingEtag != null && incomingEtag == resource.etag) {
            return LinkShareHttpResponse.bytes(statusCode = 304)
        }
        return LinkShareHttpResponse.bytes(
            bytes = resource.bytes,
            contentType = resource.contentType,
            headers = LinkShareHttpHeaderPolicies.staticResponseHeaders(
                staticPath = staticPath,
                etag = resource.etag,
                contentLength = resource.size.toLong()
            ),
        )
    }

    private suspend fun respondByRequestType(
        exchange: LinkShareHttpExchange,
        search: String?,
        files: List<FileSimpleInfo>,
    ): LinkShareHttpResponse {
        val request = exchange.request
        return if (request.isApiRequest()) {
            LogKit.d(AppStrings.ui_api_list_response_count_arg0.format(arg0 = (files.size).toString()))
            LinkShareHttpResponse.text(
                text = Json.encodeToString(files),
                contentType = "application/json",
            )
        } else {
            val pageAssets = getPageAssets()
            val scripts = mapOf(
                "Bash" to Pair("sh", readShellFile(request, "files/share-file/shell/script.sh", exchange)),
                "PowerShell" to Pair("ps1", readShellFile(request, "files/share-file/shell/script.ps1", exchange)),
                "CMD" to Pair("bat", readShellFile(request, "files/share-file/shell/script.bat", exchange))
            )
            LogKit.d(AppStrings.ui_html_list_responsive_count_arg0.format(arg0 = (files.size).toString()))
            val breadcrumbs = buildBreadcrumbs(
                path = request.path,
                language = resolveBrowserLanguage(request.header("Accept-Language")),
            )
            val authorization = exchange.getOrNull(LINK_SHARE_AUTHORIZATION_KEY)
            val normalizedUploadTarget = normalizeUploadTargetPath(request.path)
            val allowUpload = authorization?.allowUpload == true && normalizedUploadTarget != "/"
            val canRequestUpload = authorization?.allowUpload == false && normalizedUploadTarget != "/"
            htmlResponse(
                headers = listOf(LinkShareHttpHeader("Cache-Control", "no-store")),
            ) {
                HtmlTemplates.indexPage(
                    search = search,
                    files = files,
                    scripts = scripts,
                    breadcrumbs = breadcrumbs,
                    allowUpload = allowUpload,
                    canRequestUpload = canRequestUpload,
                    linkSharePageConfig = request.buildLinkSharePageConfig(),
                    assets = pageAssets
                )(this)
            }
        }
    }

    private suspend fun handleShareUploadCheck(exchange: LinkShareHttpExchange): LinkShareHttpResponse {
        val request = resolveShareUploadRequestOrRespond(exchange) ?: return exchange.responseOrForbidden()
        val existing = getUploadExistingInfo(request.target.disk, request.destinationPath)
        return LinkShareHttpResponse.text(
            text = """{"exists":${existing != null},"isDirectory":${existing?.isDirectory == true}}""",
            contentType = "application/json",
        )
    }

    private fun handleShareUploadPermissionRequest(exchange: LinkShareHttpExchange): LinkShareHttpResponse {
        val request = exchange.request
        ensureShareUploadApiRequest(request)?.let { response -> return response }
        val device = exchange.getOrNull(LINK_SHARE_DEVICE_KEY) ?: request.getClientDeviceInfo()
            ?: return LinkShareHttpResponse.text(403, AppStrings.ui_device_information_invalid)
        val authorization = exchange.getOrNull(LINK_SHARE_AUTHORIZATION_KEY)
            ?: return LinkShareHttpResponse.text(403, AppStrings.ui_unauthorized_access)
        if (authorization.allowUpload) {
            return LinkShareHttpResponse.text(200, AppStrings.ui_allowed_to_upload)
        }
        return when (fileShareState.requestLinkShareUploadPermission(device)) {
            LinkShareUploadPermissionRequestResult.Accepted ->
                LinkShareHttpResponse.text(202, AppStrings.ui_upload_permission_request_has_been_submitted)

            LinkShareUploadPermissionRequestResult.AlreadyPending ->
                LinkShareHttpResponse.text(202, AppStrings.ui_upload_permission_request_is_waiting_for_processing)

            LinkShareUploadPermissionRequestResult.AlreadyAllowed ->
                LinkShareHttpResponse.text(200, AppStrings.ui_allowed_to_upload)

            LinkShareUploadPermissionRequestResult.Rejected ->
                LinkShareHttpResponse.text(403, AppStrings.ui_upload_permission_request_has_been_denied)

            LinkShareUploadPermissionRequestResult.NotAuthorized ->
                LinkShareHttpResponse.text(403, AppStrings.ui_unauthorized_access)
        }
    }

    private suspend fun handleShareUpload(exchange: LinkShareHttpExchange): LinkShareHttpResponse {
        val request = resolveShareUploadRequestOrRespond(exchange) ?: return exchange.responseOrForbidden()
        val existing = getUploadExistingInfo(request.target.disk, request.destinationPath)
        val hadExistingDestination = existing != null
        val isChunkContinuation = request.isChunkContinuation()
        if (isChunkContinuation && existing == null) {
            return LinkShareHttpResponse.text(409, AppStrings.ui_upload_chunk_missing_start_file)
        }
        if (existing != null) {
            if (isChunkContinuation && existing.isDirectory) {
                return LinkShareHttpResponse.text(409, AppStrings.ui_target_exists)
            }
            if (!request.overwrite && !isChunkContinuation) {
                return LinkShareHttpResponse.text(409, AppStrings.ui_target_exists)
            }
            if (!isChunkContinuation) {
                val deleteResult = deleteUploadDestination(
                    disk = request.target.disk,
                    path = request.destinationPath,
                    isDirectory = existing.isDirectory
                )
                if (deleteResult.isFailure || !deleteResult.getOrDefault(false)) {
                    return LinkShareHttpResponse.text(500, deleteResult.exceptionOrNull()?.message ?: AppStrings.ui_covered_old_files_failed)
                }
            }
        }

        val writeResult = when (request.item.type) {
            ShareUploadItemType.Directory -> createUploadDirectory(request.target.disk, request.destinationPath)
            ShareUploadItemType.File -> writeUploadFile(request, exchange.request)
        }

        if (writeResult.isFailure || !writeResult.getOrDefault(false)) {
            return LinkShareHttpResponse.text(500, writeResult.exceptionOrNull()?.message ?: AppStrings.network_upload_failed)
        }

        updateUploadCleanupState(request, hadExistingDestination)
        return LinkShareHttpResponse.text(text = """{"success":true}""", contentType = "application/json")
    }

    private suspend fun handleShareUploadCancel(exchange: LinkShareHttpExchange): LinkShareHttpResponse {
        val request = resolveShareUploadRequestOrRespond(exchange) ?: return exchange.responseOrForbidden()
        if (request.item.type != ShareUploadItemType.File) {
            return LinkShareHttpResponse.text(text = """{"success":true}""", contentType = "application/json")
        }
        if (!consumeUploadCleanupCandidate(request)) {
            return LinkShareHttpResponse.text(text = """{"success":true}""", contentType = "application/json")
        }

        val existing = getUploadExistingInfo(request.target.disk, request.destinationPath)
            ?: return LinkShareHttpResponse.text(text = """{"success":true}""", contentType = "application/json")
        if (existing.isDirectory) {
            return LinkShareHttpResponse.text(409, AppStrings.ui_target_exists)
        }

        val deleteResult = deleteUploadDestination(
            disk = request.target.disk,
            path = request.destinationPath,
            isDirectory = false
        )
        if (deleteResult.isFailure || !deleteResult.getOrDefault(false)) {
            return LinkShareHttpResponse.text(500, deleteResult.exceptionOrNull()?.message ?: AppStrings.ui_cleanup_incomplete_uploads_failed)
        }
        return LinkShareHttpResponse.text(text = """{"success":true}""", contentType = "application/json")
    }

    private suspend fun updateUploadCleanupState(request: ShareUploadRequest, hadExistingDestination: Boolean) {
        val range = request.contentRange ?: return
        if (request.item.type != ShareUploadItemType.File) return
        if (range.isLast) {
            clearUploadCleanupCandidate(request)
        } else if (!hadExistingDestination) {
            markUploadCleanupCandidate(request)
        }
    }

    private suspend fun markUploadCleanupCandidate(request: ShareUploadRequest) {
        uploadCleanupMutex.withLock {
            cancellableUploadPaths += request.uploadCleanupKey()
        }
    }

    private suspend fun clearUploadCleanupCandidate(request: ShareUploadRequest) {
        uploadCleanupMutex.withLock {
            cancellableUploadPaths -= request.uploadCleanupKey()
        }
    }

    private suspend fun consumeUploadCleanupCandidate(request: ShareUploadRequest): Boolean {
        return uploadCleanupMutex.withLock {
            cancellableUploadPaths.remove(request.uploadCleanupKey())
        }
    }

    private suspend fun resolveShareUploadRequestOrRespond(exchange: LinkShareHttpExchange): ShareUploadRequest? {
        val request = exchange.request
        fun reject(statusCode: Int, message: String): ShareUploadRequest? {
            exchange.put(LINK_SHARE_UPLOAD_REJECTION_KEY, LinkShareHttpResponse.text(statusCode, message))
            return null
        }

        ensureShareUploadApiRequest(request)?.let { response ->
            exchange.put(LINK_SHARE_UPLOAD_REJECTION_KEY, response)
            return null
        }
        val authorization = exchange.getOrNull(LINK_SHARE_AUTHORIZATION_KEY)
        if (authorization?.allowUpload != true) {
            exchange.put(LINK_SHARE_UPLOAD_REJECTION_KEY, LinkShareHttpResponse.text(403, AppStrings.ui_upload_not_allowed))
            return null
        }

        val clientRequest = exchange.getOrNull(LINK_SHARE_DEVICE_KEY) ?: request.getClientDeviceInfo()
        if (clientRequest == null) {
            exchange.put(LINK_SHARE_UPLOAD_REJECTION_KEY, LinkShareHttpResponse.text(403, AppStrings.ui_device_information_invalid))
            return null
        }

        val target = resolveShareUploadTarget(exchange) ?: return null

        val item = resolveShareUploadItem(request)
        if (item == null) {
            exchange.put(LINK_SHARE_UPLOAD_REJECTION_KEY, LinkShareHttpResponse.text(400, AppStrings.ui_upload_path_invalid))
            return null
        }

        val destinationPath = buildSharedPath(
            rootPath = target.path,
            relativeSegments = item.segments,
            separator = target.disk.pathSeparator
        )
        if (!isSharedPathSafe(
                root = target.root,
                disk = target.disk,
                relativeSegments = target.relativeSegments + item.segments,
                allowNonExistentLeaf = true,
            )
        ) {
            return reject(403, AppStrings.ui_upload_path_exceeds_shared_directory_or_contains_symbolic_links)
        }
        val overwrite = request.query("overwrite") == "true"
        val contentLength = request.header("Content-Length")?.toLongOrNull()
            ?: request.body.contentLength
        val contentRange = if (item.type == ShareUploadItemType.File) {
            val rangeHeader = request.header("Content-Range")
            if (rangeHeader.isNullOrBlank()) {
                null
            } else {
                val range = parseUploadContentRange(rangeHeader)
                    ?: return reject(400, AppStrings.ui_upload_of_the_slice_range_invalid)
                if (contentLength == null || contentLength < 0L) {
                    return reject(411, AppStrings.ui_missing_slice_size)
                }
                if (contentLength != range.length) {
                    return reject(400, AppStrings.ui_upload_mismatched_chunk_length)
                }
                val querySize = request.query("size")?.toLongOrNull()
                if (querySize != null && querySize != range.totalSize) {
                    return reject(400, AppStrings.ui_upload_file_size_does_not_match)
                }
                range
            }
        } else {
            null
        }
        val declaredSize = when (item.type) {
            ShareUploadItemType.Directory -> 0L
            ShareUploadItemType.File -> contentRange?.totalSize
                ?: request.query("size")?.toLongOrNull()
                ?: contentLength
        }
        if (item.type == ShareUploadItemType.File && (declaredSize == null || declaredSize < 0L)) {
            exchange.put(LINK_SHARE_UPLOAD_REJECTION_KEY, LinkShareHttpResponse.text(411, AppStrings.ui_missing_file_size))
            return null
        }

        return ShareUploadRequest(
            target = target,
            item = item,
            destinationPath = destinationPath,
            overwrite = overwrite,
            declaredSize = declaredSize ?: 0L,
            contentRange = contentRange,
        )
    }

    private suspend fun resolveShareUploadTarget(exchange: LinkShareHttpExchange): ShareUploadTarget? {
        val request = exchange.request
        val targetUrlPath = normalizeUploadTargetPath(request.query("target"))
        fun reject(statusCode: Int, message: String): ShareUploadTarget? {
            exchange.put(LINK_SHARE_UPLOAD_REJECTION_KEY, LinkShareHttpResponse.text(statusCode, message))
            return null
        }
        if (targetUrlPath == "/") {
            return reject(400, AppStrings.ui_enter_the_specific_shared_directory_and_upload)
        }

        val authorizedFiles = exchange.getOrNull(LINK_SHARE_AUTHORIZATION_KEY)
            ?: return reject(403, AppStrings.ui_unauthorized_access)

        val rawParentFileSimpleInfo = authorizedFiles.files.find { item -> targetUrlPath.matchesSharedRoot(item) }
        if (rawParentFileSimpleInfo != null && !fileShareState.isLinkShareFileAllowed(rawParentFileSimpleInfo)) {
            return reject(403, AppStrings.ui_no_right_to_share_this_path)
        }

        val files = fileShareState.filterShareableFiles(authorizedFiles.files)
        val parentFileSimpleInfo = files.find { item -> targetUrlPath.matchesSharedRoot(item) }
            ?: return reject(404, AppStrings.ui_path_does_not_exist)

        val shareDisk = fileShareState.resolveLinkShareDisk(parentFileSimpleInfo)
        if (!fileShareState.isLinkShareFileAllowed(parentFileSimpleInfo) || shareDisk == null) {
            return reject(403, AppStrings.ui_no_right_to_share_this_path)
        }
        if (!shareDisk.supportsHttpShareUpload()) {
            return reject(403, AppStrings.ui_the_current_shared_directory_does_not_support_file_uploads)
        }

        val pathSegments = targetUrlPath.parsePath()
        val relativeSegments = resolveSharedRelativeSegments(parentFileSimpleInfo, pathSegments)
            ?: return reject(400, AppStrings.ui_upload_target_path_invalid)
        val path = buildSharedPath(parentFileSimpleInfo.path, relativeSegments, shareDisk.pathSeparator)
        if (!isSharedPathSafe(parentFileSimpleInfo, shareDisk, relativeSegments)) {
            return reject(403, AppStrings.ui_upload_target_exceeds_shared_directory_or_contains_symbolic_links)
        }

        if (!authorizedFiles.allowHidden && containsHiddenSharedPath(
                root = parentFileSimpleInfo,
                disk = shareDisk,
                relativeSegments = relativeSegments
            )
        ) {
            return reject(403, AppStrings.ui_no_right_to_access_hidden_files)
        }

        val targetInfo = getSharedFileInfo(
            root = parentFileSimpleInfo,
            disk = shareDisk,
            path = path,
            relativeSegments = relativeSegments
        ).getOrNull()
        if (targetInfo == null || !targetInfo.isDirectory) {
            return reject(400, AppStrings.ui_upload_target_is_not_a_directory)
        }

        return ShareUploadTarget(parentFileSimpleInfo, shareDisk, targetInfo.path, relativeSegments)
    }

    private fun ensureShareUploadApiRequest(request: LinkShareHttpRequest): LinkShareHttpResponse? {
        if (request.header("X-API-Request") != "true") {
            return LinkShareHttpResponse.text(403, AppStrings.ui_upload_request_missing_api_identifier)
        }
        val origin = request.header("Origin")
        if (
            !origin.isNullOrBlank() &&
            !LinkShareHttpHeaderPolicies.isSameOrigin(
                origin,
                request.host,
                request.port,
                request.scheme,
                advertisedHostProvider.get(),
            )
        ) {
            return LinkShareHttpResponse.text(403, AppStrings.ui_upload_request_source_invalid)
        }
        return null
    }

    private fun resolveShareUploadItem(request: LinkShareHttpRequest): ShareUploadItem? {
        val rawRelativePath = request.query("relativePath") ?: return null
        val segments = rawRelativePath
            .replace('\\', '/')
            .trim('/')
            .split('/')
            .filter { item -> item.isNotEmpty() }
        if (segments.isEmpty()) return null
        if (segments.any { item -> item == "." || item == ".." || item.contains('\u0000') }) return null
        val type = when (request.query("type")) {
            "directory" -> ShareUploadItemType.Directory
            "file" -> ShareUploadItemType.File
            else -> return null
        }
        return ShareUploadItem(type, segments.joinToString("/"), segments)
    }

    private fun normalizeUploadTargetPath(rawTarget: String?): String {
        val decoded = runCatching {
            decodeLinkShareUrlComponent(rawTarget ?: "/", plusAsSpace = false)
        }.getOrDefault(rawTarget ?: "/")
        val prefixed = if (decoded.startsWith("/")) decoded else "/$decoded"
        val trimmed = prefixed.trimEnd('/')
        return trimmed.ifBlank { "/" }
    }

    private fun DiskBase.supportsHttpShareUpload(): Boolean {
        return menuPermission?.write == true && (this is Local || this is Device)
    }

    private suspend fun getUploadExistingInfo(disk: DiskBase, path: String): FileSimpleInfo? {
        return when (disk) {
            is Local -> withContext(Dispatchers.Default) { FileUtils.getFile(FileAccessPermission.Allowed, path).getOrNull() }
            is Device -> disk.files.get(path).getOrNull()
            else -> null
        }
    }

    private suspend fun createUploadDirectory(disk: DiskBase, path: String): Result<Boolean> {
        return when (disk) {
            is Local -> withContext(Dispatchers.Default) {
                runCatching {
                    PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, path)
                    true
                }
            }
            is Device -> disk.files.createFolders(listOf(path)).fold(
                onSuccess = { results -> results.firstOrNull() ?: Result.success(false) },
                onFailure = { error -> Result.failure(error) }
            )
            else -> Result.failure(Exception(AppStrings.ui_the_current_shared_directory_does_not_support_file_uploads))
        }
    }

    private suspend fun deleteUploadDestination(disk: DiskBase, path: String, isDirectory: Boolean): Result<Boolean> {
        return when (disk) {
            is Local -> withContext(Dispatchers.Default) {
                runCatching {
                    if (isDirectory) {
                        PathUtils.deleteDirectory(FileAccessPermission.Allowed, path)
                        true
                    } else {
                        FileUtils.deleteFile(FileAccessPermission.Allowed, path).getOrElse { error -> throw error }
                    }
                }
            }
            is Device -> {
                if (isDirectory) {
                    disk.paths.deleteDirectory(path)
                } else {
                    disk.files.delete(listOf(path)).fold(
                        onSuccess = { results -> results.firstOrNull() ?: Result.success(false) },
                        onFailure = { error -> Result.failure(error) }
                    )
                }
            }
            else -> Result.failure(Exception(AppStrings.ui_the_current_shared_directory_does_not_support_file_uploads))
        }
    }

    private suspend fun writeUploadFile(uploadRequest: ShareUploadRequest, httpRequest: LinkShareHttpRequest): Result<Boolean> {
        val parentPath = uploadRequest.destinationPath.parentUploadPath(uploadRequest.target.disk.pathSeparator)
        if (parentPath != null) {
            val parentResult = createUploadDirectory(uploadRequest.target.disk, parentPath)
            if (parentResult.isFailure || !parentResult.getOrDefault(false)) {
                return Result.failure(parentResult.exceptionOrNull() ?: Exception(AppStrings.ui_failed_create_parent_directory))
            }
        }

        if (uploadRequest.declaredSize == 0L) {
            return createUploadFile(uploadRequest.target.disk, uploadRequest.destinationPath)
        }

        uploadRequest.contentRange?.let { range ->
            return writeUploadFileRange(uploadRequest, httpRequest, range)
        }

        val buffer = ByteArray(HTTP_SHARE_UPLOAD_CHUNK_SIZE)
        var offset = 0L
        while (true) {
            val read = httpRequest.body.read(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            val bytes = buffer.copyOf(read)
            val writeResult = writeUploadFileChunk(
                disk = uploadRequest.target.disk,
                path = uploadRequest.destinationPath,
                fileSize = uploadRequest.declaredSize,
                bytes = bytes,
                offset = offset,
                blockIndex = offset / HTTP_SHARE_UPLOAD_CHUNK_SIZE.toLong()
            )
            if (writeResult.isFailure || !writeResult.getOrDefault(false)) {
                return Result.failure(writeResult.exceptionOrNull() ?: Exception(AppStrings.ui_write_failed))
            }
            offset += read.toLong()
        }

        if (offset != uploadRequest.declaredSize) {
            return Result.failure(Exception(AppStrings.ui_upload_data_length_mismatch))
        }
        return Result.success(true)
    }

    private suspend fun writeUploadFileRange(
        uploadRequest: ShareUploadRequest,
        httpRequest: LinkShareHttpRequest,
        range: ShareUploadContentRange,
    ): Result<Boolean> {
        val buffer = ByteArray(minOf(HTTP_SHARE_UPLOAD_CHUNK_SIZE.toLong(), range.length).toInt())
        var offset = range.start
        var remaining = range.length
        while (remaining > 0) {
            val readLength = minOf(buffer.size.toLong(), remaining).toInt()
            val read = httpRequest.body.read(buffer, 0, readLength)
            if (read < 0) {
                return Result.failure(Exception(AppStrings.ui_upload_of_chunk_data_length_mismatch))
            }
            if (read == 0) continue
            val bytes = buffer.copyOf(read)
            val writeResult = writeUploadFileChunk(
                disk = uploadRequest.target.disk,
                path = uploadRequest.destinationPath,
                fileSize = uploadRequest.declaredSize,
                bytes = bytes,
                offset = offset,
                blockIndex = offset / HTTP_SHARE_UPLOAD_CHUNK_SIZE.toLong()
            )
            if (writeResult.isFailure || !writeResult.getOrDefault(false)) {
                return Result.failure(writeResult.exceptionOrNull() ?: Exception(AppStrings.ui_write_failed))
            }
            offset += read.toLong()
            remaining -= read.toLong()
        }
        return Result.success(true)
    }

    private suspend fun createUploadFile(disk: DiskBase, path: String): Result<Boolean> {
        return when (disk) {
            is Local -> withContext(Dispatchers.Default) { FileUtils.createFile(FileAccessPermission.Allowed, path) }
            is Device -> disk.files.createFiles(listOf(path)).fold(
                onSuccess = { results -> results.firstOrNull() ?: Result.success(false) },
                onFailure = { error -> Result.failure(error) }
            )
            else -> Result.failure(Exception(AppStrings.ui_the_current_shared_directory_does_not_support_file_uploads))
        }
    }

    private suspend fun writeUploadFileChunk(
        disk: DiskBase,
        path: String,
        fileSize: Long,
        bytes: ByteArray,
        offset: Long,
        blockIndex: Long,
    ): Result<Boolean> {
        return when (disk) {
            is Local -> withContext(Dispatchers.Default) {
                FileUtils.writeBytes(FileAccessPermission.Allowed, path, fileSize, bytes, offset)
            }
            is Device -> {
                val client = disk.resolveUploadFileClient()
                    ?: return Result.failure(Exception(AppStrings.ui_the_device_cannot_be_written_into))
                client.writeBytes(
                    fileSize = fileSize,
                    blockIndex = blockIndex,
                    blockLength = bytes.size.toLong(),
                    path = path,
                    byteArray = bytes,
                    startOffset = offset
                )
            }
            else -> Result.failure(Exception(AppStrings.ui_the_current_shared_directory_does_not_support_file_uploads))
        }
    }

    private fun Device.resolveUploadFileClient(): DeviceFileClient? {
        return fileClient ?: host.values.firstOrNull()?.fileRouteClient
    }

    private fun String.parentUploadPath(separator: String): String? {
        val pathSeparator = separator.ifBlank { "/" }
        val normalized = trimEnd('/', '\\')
        val index = normalized.lastIndexOf(pathSeparator)
        if (index < 0) return null
        if (index == 0 && pathSeparator == "/") return "/"
        return normalized.substring(0, index).ifBlank { pathSeparator }
    }

    private suspend fun downloadSharedFile(
        root: FileSimpleInfo,
        disk: DiskBase,
        file: FileSimpleInfo,
        path: String,
        request: LinkShareHttpRequest,
        delivery: LinkShareFileDelivery = request.resolveFileDelivery(),
    ): LinkShareHttpResponse {
        ClipboardUrlShareSourceRegistry.find(root.path)?.let { source ->
            return respondClipboardUrlShareFile(source, file, request, delivery)
        }
        return when (disk) {
            is Local -> platformFileResponder.downloadLocalFile(path, file, request, delivery)
            is Device,
            is Share -> respondSharedByteRangeDownload(
                disk = disk,
                file = file.withSharedProtocol(root),
                path = path,
                request = request,
                delivery = delivery,
            )
            else -> LinkShareHttpResponse.text(400, AppStrings.ui_the_disk_does_not_support_link_downloads)
        }
    }

    private fun respondClipboardUrlShareFile(
        source: ClipboardUrlShareSource,
        file: FileSimpleInfo,
        request: LinkShareHttpRequest,
        delivery: LinkShareFileDelivery,
    ): LinkShareHttpResponse {
        val presentation = resolveLinkShareFilePresentation(
            fileName = file.name,
            requestedDelivery = delivery,
            fileMimeTypeHint = file.mineType,
        )
        val headers = presentation.headers.toMutableList()
        val fileSize = source.totalBytes.coerceAtLeast(0L)
        val range = parseRangeHeader(request.header("Range"), fileSize)
        if (fileSize == 0L) {
            headers += LinkShareHttpHeader("Content-Length", "0")
            return LinkShareHttpResponse.bytes(
                bytes = byteArrayOf(),
                contentType = presentation.contentType,
                headers = headers,
            )
        }

        val start = range?.first ?: 0L
        val endInclusive = range?.last ?: (fileSize - 1L)
        val contentLength = endInclusive - start + 1L
        val statusCode = if (range == null) 200 else 206
        if (range != null) {
            headers += LinkShareHttpHeader("Content-Range", "bytes $start-$endInclusive/$fileSize")
        }
        headers += LinkShareHttpHeader("Content-Length", contentLength.toString())
        return LinkShareHttpResponse.stream(
            statusCode = statusCode,
            contentType = presentation.contentType,
            contentLength = contentLength,
            headers = headers,
        ) {
            source.streamRange(start, endInclusive + 1L, this)
        }
    }

    private fun respondSharedByteRangeDownload(
        disk: DiskBase,
        file: FileSimpleInfo,
        path: String,
        request: LinkShareHttpRequest,
        delivery: LinkShareFileDelivery,
    ): LinkShareHttpResponse {
        val presentation = resolveLinkShareFilePresentation(
            fileName = file.name,
            requestedDelivery = delivery,
            fileMimeTypeHint = file.mineType,
        )
        val headers = presentation.headers.toMutableList()

        val fileSize = file.size.coerceAtLeast(0L)
        val range = parseRangeHeader(request.header("Range"), fileSize)
        if (fileSize == 0L) {
            headers += LinkShareHttpHeader("Content-Length", "0")
            return LinkShareHttpResponse.bytes(
                bytes = byteArrayOf(),
                contentType = presentation.contentType,
                headers = headers,
            )
        }

        val start = range?.first ?: 0L
        val endInclusive = range?.last ?: (fileSize - 1)
        val contentLength = endInclusive - start + 1
        var statusCode = 200
        if (range != null) {
            statusCode = 206
            headers += LinkShareHttpHeader("Content-Range", "bytes $start-$endInclusive/$fileSize")
        }
        headers += LinkShareHttpHeader("Content-Length", contentLength.toString())
        return LinkShareHttpResponse.stream(
            statusCode = statusCode,
            contentType = presentation.contentType,
            contentLength = contentLength,
            headers = headers,
        ) {
            streamSharedFileRange(disk, path, start, endInclusive + 1)
        }
    }

    private suspend fun LinkShareHttpResponseBodyWriter.streamSharedFileRange(
        disk: DiskBase,
        path: String,
        startOffset: Long,
        endOffset: Long,
    ) = coroutineScope {
        var nextReadOffset = startOffset
        var nextWriteOffset = startOffset
        val pendingReads = mutableMapOf<Long, kotlinx.coroutines.Deferred<ByteArray>>()

        fun launchRead(offset: Long) = async(Dispatchers.Default) {
            val nextOffset = minOf(offset + MAX_LENGTH.toLong(), endOffset)
            val bytes = readSharedFileRange(disk, path, offset, nextOffset).getOrElse { error -> throw error }
            val expectedSize = (nextOffset - offset).toInt()
            if (bytes.size != expectedSize) {
                throw Exception(AppStrings.ui_read_file_range_length_mismatch_expect_arg0_actual_arg1_range_arg2_arg3.format(arg0 = (expectedSize).toString(), arg1 = (bytes.size).toString(), arg2 = (offset).toString(), arg3 = (nextOffset).toString()))
            }
            bytes
        }

        fun fillPrefetchWindow() {
            while (nextReadOffset < endOffset && pendingReads.size < SHARED_DOWNLOAD_PREFETCH_CHUNKS) {
                val offset = nextReadOffset
                pendingReads[offset] = launchRead(offset)
                nextReadOffset = minOf(offset + MAX_LENGTH.toLong(), endOffset)
            }
        }

        fillPrefetchWindow()
        while (nextWriteOffset < endOffset) {
            val bytes = pendingReads.remove(nextWriteOffset)?.await() ?: throw Exception(AppStrings.ui_read_file_range_failed)
            write(bytes)
            nextWriteOffset += bytes.size
            fillPrefetchWindow()
        }
    }

    private suspend fun readSharedFileRange(
        disk: DiskBase,
        path: String,
        startOffset: Long,
        endOffset: Long,
    ): Result<ByteArray> {
        return when (disk) {
            is Device -> disk.files.readBytes(path, startOffset, endOffset)
            is Share -> {
                if (disk.protocol == ShareProtocol.Remote) {
                    disk.readBytes(path, startOffset, endOffset)
                } else {
                    Result.failure(Exception(AppStrings.ui_system_shares_should_be_processed_locally))
                }
            }
            else -> Result.failure(Exception(AppStrings.ui_the_disk_does_not_support_range_reading))
        }
    }

    private fun parseRangeHeader(rangeHeader: String?, fileSize: Long): LongRange? {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=") || fileSize <= 0) return null
        val rangeParts = rangeHeader.substring(6).split("-", limit = 2)
        if (rangeParts.size != 2) return null
        val start = rangeParts[0].toLongOrNull() ?: return null
        val end = if (rangeParts[1].isNotEmpty()) rangeParts[1].toLongOrNull() ?: return null else fileSize - 1
        if (start < 0 || end >= fileSize || start > end) return null
        return start..end
    }

    private fun parseUploadContentRange(rangeHeader: String): ShareUploadContentRange? {
        val value = rangeHeader.trim()
        if (!value.startsWith("bytes ")) return null
        val rangeAndTotal = value.substringAfter("bytes ").split("/", limit = 2)
        if (rangeAndTotal.size != 2) return null
        val byteRange = rangeAndTotal[0].split("-", limit = 2)
        if (byteRange.size != 2) return null
        val start = byteRange[0].toLongOrNull() ?: return null
        val endInclusive = byteRange[1].toLongOrNull() ?: return null
        val totalSize = rangeAndTotal[1].toLongOrNull() ?: return null
        if (start !in 0L..endInclusive || totalSize <= 0L || endInclusive >= totalSize) return null
        return ShareUploadContentRange(start, endInclusive, totalSize)
    }

    private suspend fun getSharedFileInfo(
        root: FileSimpleInfo,
        disk: DiskBase,
        path: String,
        relativeSegments: List<String>,
    ): Result<FileSimpleInfo> {
        if (ClipboardUrlShareSourceRegistry.find(root.path) != null) {
            return if (relativeSegments.isEmpty()) {
                Result.success(root)
            } else {
                Result.failure(IllegalArgumentException("URL share source has no child path"))
            }
        }
        if (relativeSegments.isEmpty()) {
            if (disk is Share && disk.protocol == ShareProtocol.Remote) {
                return Result.success(root.withSharedProtocol(root))
            }
            return getSharedFileByPath(root, disk, path)
        }
        if (disk is Share && disk.protocol == ShareProtocol.Remote) {
            return findSharedChildInfo(root, disk, relativeSegments)
        }
        if (isContentUriShareRoot(root, disk)) {
            return findSharedChildInfo(root, disk, relativeSegments)
        }
        return getSharedFileByPath(root, disk, path)
    }

    private suspend fun getSharedFileByPath(root: FileSimpleInfo, disk: DiskBase, path: String): Result<FileSimpleInfo> {
        val result = when (disk) {
            is Local -> withContext(Dispatchers.Default) { FileUtils.getFile(FileAccessPermission.Allowed, path) }
            is Device -> disk.files.get(path)
            is Share -> {
                if (disk.protocol == ShareProtocol.System) {
                    withContext(Dispatchers.Default) { FileUtils.getFile(FileAccessPermission.Allowed, path) }
                } else {
                    Result.failure(Exception(AppStrings.ui_remote_sharing_does_not_support_direct_query_of_file_information))
                }
            }
            is NetworkAccess -> disk.getFile(path)
            else -> Result.failure(Exception(AppStrings.ui_the_disk_does_not_support_link_sharing))
        }
        return result.map { item -> item.withSharedProtocol(root) }
    }

    private suspend fun findSharedChildInfo(root: FileSimpleInfo, disk: DiskBase, relativeSegments: List<String>): Result<FileSimpleInfo> {
        val childName = relativeSegments.lastOrNull() ?: return Result.failure(Exception(AppStrings.ui_path_does_not_exist))
        val parentPath = resolveSharedWalkPath(root, disk, relativeSegments.dropLast(1))
            ?: return Result.failure(Exception(AppStrings.ui_path_does_not_exist))
        return getSharedFileList(root, disk, parentPath).mapCatching { children ->
            children.firstOrNull { item -> item.name == childName } ?: throw Exception(AppStrings.ui_path_does_not_exist)
        }
    }

    private suspend fun resolveSharedWalkPath(
        root: FileSimpleInfo,
        disk: DiskBase,
        relativeSegments: List<String>,
    ): String? {
        if (relativeSegments.isEmpty()) return root.path
        var currentPath = root.path
        relativeSegments.forEach { segment ->
            val child = getSharedFileList(root, disk, currentPath)
                .getOrNull()
                ?.firstOrNull { item -> item.name == segment }
                ?: return null
            currentPath = child.path
        }
        return currentPath
    }

    private suspend fun getSharedFileList(root: FileSimpleInfo, disk: DiskBase, path: String): Result<List<FileSimpleInfo>> {
        val result = when (disk) {
            is Local -> listLocalSharedFiles(path)
            is Device -> disk.paths.getList(path)
            is Share -> {
                if (disk.protocol == ShareProtocol.System) {
                    val systemPath = if (path == "/") "content://" else path
                    listLocalSharedFiles(systemPath)
                } else {
                    disk.getFileList(path)
                }
            }
            is NetworkAccess -> disk.getList(path)
            else -> Result.failure(Exception(AppStrings.ui_the_disk_does_not_support_link_sharing))
        }
        return result.map { files -> files.map { item -> item.withSharedProtocol(root) } }
    }

    private suspend fun isSharedPathSafe(
        root: FileSimpleInfo,
        disk: DiskBase,
        relativeSegments: List<String>,
        allowNonExistentLeaf: Boolean = false,
    ): Boolean {
        if (!relativeSegments.isSafeSharedRelativeSegments()) return false
        if (ClipboardUrlShareSourceRegistry.find(root.path) != null) {
            return relativeSegments.isEmpty() && !allowNonExistentLeaf
        }
        if (isContentUriShareRoot(root, disk)) {
            return isRegisteredContentUriPath(root, disk, relativeSegments, allowNonExistentLeaf)
        }
        val targetPath = buildSharedPath(root.path, relativeSegments, disk.pathSeparator)
        if (disk is Local) {
            return PathUtils.isPathWithinRoot(FileAccessPermission.Allowed, root.path, targetPath, allowNonExistentLeaf)
        }
        if (relativeSegments.isEmpty()) return true

        var currentPath = root.path
        relativeSegments.forEachIndexed { index, segment ->
            val child = getSharedFileList(root, disk, currentPath)
                .getOrNull()
                ?.firstOrNull { item -> item.name == segment }
            if (child == null) {
                return allowNonExistentLeaf && index == relativeSegments.lastIndex
            }
            if (!child.isSymbolicLinkKnown || child.isSymbolicLink) return false
            if (index < relativeSegments.lastIndex && !child.isDirectory) return false
            currentPath = child.path
        }
        return true
    }

    private suspend fun isRegisteredContentUriPath(
        root: FileSimpleInfo,
        disk: DiskBase,
        relativeSegments: List<String>,
        allowNonExistentLeaf: Boolean,
    ): Boolean {
        if (relativeSegments.isEmpty()) return root.path.startsWith("content://") || root.path == "content://"
        var currentPath = root.path
        relativeSegments.forEachIndexed { index, segment ->
            val child = getSharedFileList(root, disk, currentPath)
                .getOrNull()
                ?.firstOrNull { item -> item.name == segment }
            if (child == null) {
                return allowNonExistentLeaf && index == relativeSegments.lastIndex
            }
            if (!child.path.startsWith("content://")) return false
            if (index < relativeSegments.lastIndex && !child.isDirectory) return false
            currentPath = child.path
        }
        return true
    }

    private suspend fun containsHiddenSharedPath(root: FileSimpleInfo, disk: DiskBase, relativeSegments: List<String>): Boolean {
        if (root.isHidden || root.name.isHiddenSharedPathSegment()) return true
        if (relativeSegments.any { segment -> segment.isHiddenSharedPathSegment() }) return true
        return relativeSegments.indices.any { index ->
            val segments = relativeSegments.subList(0, index + 1)
            val checkPath = buildSharedPath(root.path, segments, disk.pathSeparator)
            getSharedFileInfo(root, disk, checkPath, segments).getOrNull()?.isHidden == true
        }
    }

    private fun resolveSharedRelativeSegments(root: FileSimpleInfo, pathSegments: List<String>): List<String>? {
        if (pathSegments.isEmpty()) return emptyList()
        val relativeSegments = if (pathSegments.first() == root.name) pathSegments.drop(1) else pathSegments
        return relativeSegments.takeIf { segments -> segments.isSafeSharedRelativeSegments() }
    }

    private fun String.matchesSharedRoot(root: FileSimpleInfo): Boolean {
        val rootPath = "/${root.name}"
        return this == rootPath || this.startsWith("$rootPath/")
    }

    private fun isContentUriShareRoot(root: FileSimpleInfo, disk: DiskBase): Boolean {
        return root.path.startsWith("content://") ||
            (disk is Share && disk.protocol == ShareProtocol.System)
    }

    internal suspend fun isSharedPathSafeForTest(
        root: FileSimpleInfo,
        disk: DiskBase,
        relativeSegments: List<String>,
        allowNonExistentLeaf: Boolean = false,
    ): Boolean = isSharedPathSafe(root, disk, relativeSegments, allowNonExistentLeaf)

    private fun buildSharedPath(rootPath: String, relativeSegments: List<String>, separator: String): String {
        require(relativeSegments.isSafeSharedRelativeSegments()) { "Unsafe shared path segments" }
        if (relativeSegments.isEmpty()) return rootPath
        val pathSeparator = separator.ifBlank { "/" }
        val normalizedRootPath = rootPath.trimEnd('/', '\\')
        return normalizedRootPath + pathSeparator + relativeSegments.joinToString(pathSeparator)
    }

    private fun List<String>.isSafeSharedRelativeSegments(): Boolean {
        return none { segment ->
            segment == "." ||
                segment == ".." ||
                segment.contains('\u0000') ||
                segment.contains('/') ||
                segment.contains('\\')
        }
    }

    private fun String.isHiddenSharedPathSegment(): Boolean {
        return startsWith(".") && this != "." && this != ".."
    }

    private fun respondShareForbidden(message: String): LinkShareHttpResponse = LinkShareHttpResponse.text(403, message)

    private fun respondHiddenFileForbidden(request: LinkShareHttpRequest): LinkShareHttpResponse {
        return if (request.isApiRequest()) {
            LinkShareHttpResponse.text(403, AppStrings.ui_no_right_to_access_hidden_files)
        } else {
            LinkShareHttpResponse.redirect("/")
        }
    }

    private suspend fun getPageAssets(): PageAssets {
        val inlineStyles = runCatching { getSharedStyles() }
            .onFailure { error -> LogKit.e(AppStrings.ui_load_shared_styles_failed_arg0.format(arg0 = (error.message).toString()), error) }
            .getOrNull()
        val themeConfigCss = runCatching { getThemeConfigCss() }
            .onFailure { error -> LogKit.e(AppStrings.ui_failed_to_generate_theme_configuration_style_arg0.format(arg0 = (error.message).toString()), error) }
            .getOrNull()
        return PageAssets(inlineStyles = inlineStyles, themeConfigCss = themeConfigCss)
    }

    private suspend fun readShellFile(
        request: LinkShareHttpRequest,
        path: String,
        exchange: LinkShareHttpExchange,
    ): String {
        val publicHost = resolvePublicHttpHost(request.host, advertisedHostProvider.get())
        val fullUrl = "${request.scheme}://$publicHost:${request.port}"
        val template = loadShellTemplate(path) ?: ""
        val localizedTemplate = if (
            resolveBrowserLanguage(request.header("Accept-Language")) == ResolvedAppLanguage.English
        ) {
            translateShellTemplateToEnglish(template)
        } else {
            template
        }
        val kind = linkShareDownloadScriptKind(path)
        val pathSegments = request.path.trim('/').split('/').filter { segment -> segment.isNotEmpty() }
        val rawTargetDir = pathSegments.lastOrNull().orEmpty()
        return localizedTemplate
            .replace("#API_SERVER#", escapeLinkShareDownloadScriptValue(fullUrl, kind))
            .replace(
                "#USER_AGENT#",
                escapeLinkShareDownloadScriptValue(request.header("User-Agent").orEmpty(), kind),
            )
            .replace(
                "#LINK_SHARE_SESSION_HEADER#",
                escapeLinkShareDownloadScriptValue(LINK_SHARE_SESSION_HEADER, kind),
            )
            .replace(
                "#LINK_SHARE_SESSION_TOKEN#",
                escapeLinkShareDownloadScriptValue(
                    exchange.getOrNull(LINK_SHARE_SESSION_TOKEN_KEY)
                        ?: request.cookies[LINK_SHARE_SESSION_COOKIE].orEmpty(),
                    kind,
                ),
            )
            .replace(
                "#ROOT_PATH#",
                escapeLinkShareDownloadScriptValue(
                    encodeLinkShareScriptRootPath(
                        if (request.path == "/") "" else request.path,
                        kind,
                    ),
                    kind,
                ),
            )
            .replace(
                "#TARGET_DIR#",
                escapeLinkShareDownloadScriptValue(sanitizeLinkShareScriptFileName(rawTargetDir), kind),
            )
    }

    internal suspend fun readShellFileForTest(
        request: LinkShareHttpRequest,
        templatePath: String,
        sessionToken: String? = null,
    ): String {
        val exchange = LinkShareHttpExchange(request)
        if (sessionToken != null) {
            exchange.put(LINK_SHARE_SESSION_TOKEN_KEY, sessionToken)
        }
        return readShellFile(request, templatePath, exchange)
    }

    private fun translateShellTemplateToEnglish(template: String): String {
        val translations = linkedMapOf(
            AppStrings.ui_target_directory_arg0_target_dir_does_not_exist_creating_automatically.format(arg0 = ('$').toString()) to
                "Target directory (${'$'}TARGET_DIR) does not exist. Creating it...",
            AppStrings.ui_target_directory_arg0_target_dir_does_not_exist_creating.format(arg0 = ('$').toString()) to
                "Target directory (${'$'}TARGET_DIR) does not exist. Creating it...",
            AppStrings.ui_warning_the_current_directory_is_not_an_empty_directory_and_downloading_may to
                "Warning: the current directory is not empty. Existing files may be overwritten.",
            AppStrings.ui_warning_the_directory_is_empty_and_continuing_may_overwrite_files to
                "Warning: the directory is not empty. Existing files may be overwritten.",
            AppStrings.ui_continue_with_the_enter_key_or_press_any_other_key_to_cancel_the_operation to
                "Press Enter to continue, or type any text then press Enter to cancel...",
            AppStrings.ui_continue_with_enter_cancel_with_any_other_key to
                "Press Enter to continue, or type any text then press Enter to cancel...",
            AppStrings.ui_operation_canceled to "Operation cancelled.",
            AppStrings.ui_users_have_confirmed_continuing_to_cover_the_download to "Confirmed. Continuing download...",
            AppStrings.ui_users_confirmed_that_the_file_will_be_continued_downloading to "Confirmed. Continuing download...",
            AppStrings.ui_process_path to "Processing path:",
            AppStrings.ui_error_cannot_parse_path to "Error: unable to parse the response for path",
            AppStrings.ui_link_share_script_create_directory to "Creating directory:",
            AppStrings.ui_unable_to_create_directory to "Error: unable to create directory",
            AppStrings.ui_download_file to "Downloading file:",
            AppStrings.ui_error_cannot_download_file to "Error: unable to download file",
            AppStrings.ui_start_downloading_files_and_creating_directories_to_the_current_folder to
                "Starting recursive download into the current folder...",
            AppStrings.ui_download_statistics to "========== Download Summary ==========",
            AppStrings.ui_total_folder_count to "Total directories:",
            AppStrings.ui_folder_created_successfully to "Created directories:",
            AppStrings.ui_link_share_script_failed_directory_count to "Failed directories:",
            AppStrings.ui_total_file_count to "Total files:",
            AppStrings.ui_successful_download to "Downloaded files:",
            AppStrings.ui_download_failed_file to "Failed files:",
            AppStrings.ui_total_success_rate to "Overall success rate:",
            AppStrings.ui_link_share_script_completed to "Done!",
        )
        return translations.entries.fold(template) { output, (source, target) ->
            output.replace(source, target)
        }
    }

    private fun buildBreadcrumbs(
        path: String,
        language: ResolvedAppLanguage,
    ): List<BreadcrumbItem> {
        val homeLabel = when (language) {
            ResolvedAppLanguage.English -> "Home"
            ResolvedAppLanguage.SimplifiedChinese -> AppStrings.ui_home
        }
        val normalizedPath = path.trim()
        if (normalizedPath.isEmpty() || normalizedPath == "/") return listOf(BreadcrumbItem(homeLabel))
        val segments = normalizedPath.trimStart('/').trimEnd('/').split('/').filter { item -> item.isNotEmpty() }
        if (segments.isEmpty()) return listOf(BreadcrumbItem(homeLabel))
        val breadcrumbs = mutableListOf(BreadcrumbItem(homeLabel, "/"))
        var cumulative = ""
        segments.forEachIndexed { index, segment ->
            cumulative += "/$segment"
            val isLast = index == segments.lastIndex
            breadcrumbs += BreadcrumbItem(
                label = decodeLinkShareUrlComponent(segment, plusAsSpace = true),
                href = if (isLast) null else cumulative
            )
        }
        return breadcrumbs
    }

    private fun LinkShareHttpRequest.buildLinkSharePageConfig(): LinkSharePageConfig {
        val httpPort = configuredHttpPort ?: port
        val httpsPort = configuredHttpsPort
        val publicHost = resolvePublicHttpHost(host, advertisedHostProvider.get())
        val fingerprint = if (httpsPort != null) currentDeviceTlsFingerprint() else ""
        val consentKey = if (httpsPort != null) {
            buildLinkShareHttpsConsentKey(publicHost, httpPort, fingerprint)
        } else {
            ""
        }
        return LinkSharePageConfig(
            httpBaseUrl = buildLinkShareBaseUrl("http", publicHost, httpPort),
            httpsBaseUrl = httpsPort?.takeIf { consentKey.isNotBlank() }?.let { port ->
                buildLinkShareBaseUrl("https", publicHost, port)
            },
            tlsFingerprintSha256 = fingerprint,
            httpsConsentKey = consentKey
        )
    }

    private fun LinkShareHttpRequest.getClientDeviceInfo(): Device? {
        val clientIp = remoteHost
        val userAgent = header("User-Agent") ?: return null
        val browser = parseBrowser(userAgent)
        val os = parseOperatingSystem(userAgent)
        val deviceType = when {
            userAgent.contains("Android", ignoreCase = true) -> DeviceType.Android
            userAgent.contains("iPhone", ignoreCase = true) ||
                userAgent.contains("iPad", ignoreCase = true) -> DeviceType.IOS
            (userAgent.contains("ktor", ignoreCase = true) || userAgent.contains("okhttp", ignoreCase = true)) &&
                !userAgent.contains("Android", ignoreCase = true) -> DeviceType.JVM
            else -> DeviceType.JS
        }
        val displayBrowser = browser.takeIf { item -> item.isNotBlank() && item != "Unknown Browser" }
        val displayOs = os.takeIf { item -> item.isNotBlank() && item != "Unknown OS" }
        val composedName = buildString {
            if (!displayBrowser.isNullOrBlank()) append(displayBrowser)
            if (!displayOs.isNullOrBlank()) {
                if (isNotEmpty()) append(" - ")
                append(displayOs)
            }
        }.ifBlank { "Unknown" }
        return Device(clientIp.orEmpty(), composedName, "/", mutableMapOf(), deviceType, "")
    }

    private fun LinkShareHttpRequest.resolveLinkShareClientId(): String {
        val existing = cookies[LINK_SHARE_CLIENT_COOKIE]?.takeIf { item -> item.isSafeLinkShareToken() }
        return existing ?: deriveStableLinkShareClientId(remoteHost, header("User-Agent"))
    }

    private fun LinkShareHttpRequest.resolveLinkShareSession(
        clientId: String,
        fingerprint: ShareTokenFingerprint,
    ): LinkShareSession? {
        val headerToken = header(LINK_SHARE_SESSION_HEADER)?.takeIf { item -> item.isSafeLinkShareToken() }
        val cookieToken = cookies[LINK_SHARE_SESSION_COOKIE]?.takeIf { item -> item.isSafeLinkShareToken() }
        return fileShareState.resolveLinkShareSession(
            token = headerToken ?: cookieToken,
            clientId = if (headerToken == null) clientId else null,
            fingerprint = fingerprint
        )
    }

    private fun LinkShareHttpRequest.buildLinkShareTokenFingerprint(): ShareTokenFingerprint {
        return ShareTokenFingerprint(clientIp = remoteHost, userAgent = header("User-Agent"))
    }

    private fun linkShareSessionCookie(request: LinkShareHttpRequest, token: String): LinkShareHttpCookie {
        return linkShareCookie(request, LINK_SHARE_SESSION_COOKIE, token, LINK_SHARE_SESSION_COOKIE_MAX_AGE_SECONDS)
    }

    private fun linkShareCookie(
        request: LinkShareHttpRequest,
        name: String,
        value: String,
        maxAge: Int,
    ): LinkShareHttpCookie {
        return LinkShareHttpCookie(
            name = name,
            value = value,
            path = "/",
            maxAgeSeconds = maxAge,
            secure = request.scheme.equals("https", ignoreCase = true),
            httpOnly = true,
            sameSite = "Lax"
        )
    }

    private suspend fun respondPasswordPage(error: String?, redirectPath: String = "/"): LinkShareHttpResponse {
        val pageAssets = getPageAssets()
        return htmlResponse(401, listOf(LinkShareHttpHeader("Cache-Control", "no-store"))) {
            HtmlTemplates.passwordPage(error, redirectPath, assets = pageAssets)(this)
        }
    }

    private suspend fun respondPasswordThrottled(
        request: LinkShareHttpRequest,
        redirectPath: String,
        lockout: LinkSharePasswordLockout,
    ): LinkShareHttpResponse {
        val retryAfter = LinkShareHttpHeader("Retry-After", lockout.retryAfterSeconds.toString())
        return if (request.isApiRequest()) {
            LinkShareHttpResponse.text(
                statusCode = 429,
                text = AppStrings.ui_too_many_attempts_try_again_later,
                headers = listOf(LinkShareHttpHeader("Cache-Control", "no-store"), retryAfter),
            )
        } else {
            val pageAssets = getPageAssets()
            htmlResponse(
                statusCode = 429,
                headers = listOf(LinkShareHttpHeader("Cache-Control", "no-store"), retryAfter),
            ) {
                HtmlTemplates.passwordPage(AppStrings.ui_too_many_attempts_try_again_later, redirectPath, assets = pageAssets)(this)
            }
        }
    }

    private fun htmlResponse(
        statusCode: Int = 200,
        headers: List<LinkShareHttpHeader> = emptyList(),
        body: HTML.() -> Unit,
    ): LinkShareHttpResponse {
        val html = createHTML().html { body() }
        return LinkShareHttpResponse.text(statusCode, html, "text/html; charset=UTF-8", headers)
    }

    private fun sanitizeLinkShareRedirect(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank() || value.length > 2048) return "/"
        if (!value.startsWith("/") || value.startsWith("//") || value.contains('\\')) return "/"
        if (value.any { item -> item.code < 0x20 }) return "/"
        return value
    }


    private suspend fun loadStaticResource(staticPath: String): StaticResource? {
        val resourcePath = normalizeLinkShareBundledResourcePath("files/share-file/static/$staticPath")
            ?: return null
        val bytes = readLinkShareResourceBytes(
            resourcePath = resourcePath,
            logName = AppStrings.ui_static_resources,
            displayPath = staticPath
        ) ?: return null
        return StaticResource(
            bytes = bytes,
            contentType = LinkShareHttpHeaderPolicies.contentTypeForPath(staticPath),
            etag = "${staticPath}_${bytes.contentHashCode()}",
            size = bytes.size
        )
    }

    private suspend fun loadShellTemplate(path: String): String? {
        val resourcePath = normalizeLinkShareBundledResourcePath(path) ?: return null
        return readLinkShareResourceBytes(
            resourcePath = resourcePath,
            logName = AppStrings.ui_shell_template,
            displayPath = resourcePath
        )?.decodeToString()
    }

    private suspend fun readLinkShareResourceBytes(
        resourcePath: String,
        logName: String,
        displayPath: String,
    ): ByteArray? {
        val safeResourcePath = normalizeLinkShareBundledResourcePath(resourcePath) ?: return null
        val bundledError = runCatching { Res.readBytes(safeResourcePath) }
            .onSuccess { bytes -> return bytes }
            .exceptionOrNull()

        val developmentBytes = readDevelopmentResourceBytes(safeResourcePath)
        if (developmentBytes != null) {
            val reason = bundledError?.message?.takeIf { message -> message.isNotBlank() }
            LogKit.i(
                when (reason) {
                    null -> AppStrings.ui_arg0_has_been_used_to_revert_to_source_code_resources_arg1.format(arg0 = (logName), arg1 = (displayPath))
                    else -> AppStrings.ui_arg0_has_been_used_to_revert_to_source_code_resources_arg1_arg2.format(arg0 = (logName), arg1 = (displayPath), arg2 = (reason))
                }
            )
            return developmentBytes
        }

        if (bundledError != null) {
            LogKit.w(AppStrings.ui_arg0_loading_failed_arg1_arg2.format(arg0 = (logName), arg1 = (displayPath), arg2 = (bundledError.message).toString()))
        }
        return null
    }

    private fun readDevelopmentResourceBytes(resourcePath: String): ByteArray? {
        val bundledRelative = normalizeLinkShareBundledResourcePath(resourcePath) ?: return null
        var root: String? = PathUtils.getAppPath().trimEnd('/', '\\')
        repeat(DEVELOPMENT_RESOURCE_ROOT_SEARCH_DEPTH) {
            val currentRoot = root?.takeIf { item -> item.isNotBlank() } ?: return null
            val resourcesRoot = "$currentRoot/core/src/commonMain/composeResources"
            if (PathUtils.exists(FileAccessPermission.Allowed, resourcesRoot)) {
                val candidate = "$resourcesRoot/$bundledRelative"
                if (!PathUtils.isPathWithinRoot(
                        FileAccessPermission.Allowed,
                        resourcesRoot,
                        candidate,
                    )
                ) {
                    return null
                }
                val bytes = runCatching {
                    FileUtils.readFile(FileAccessPermission.Allowed, candidate).getOrNull()
                }.getOrNull()
                if (bytes != null) return bytes
            }
            root = currentRoot.parentPath()
        }
        return null
    }

    private fun String.parentPath(): String? {
        val normalized = trimEnd('/', '\\')
        val separatorIndex = maxOf(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'))
        return when {
            separatorIndex <= 0 -> null
            else -> normalized.substring(0, separatorIndex)
        }
    }

    private suspend fun getSharedStyles(): String? {
        val resource = loadStaticResource("styles/shared-styles.css")
        if (resource == null) {
            LogKit.w(AppStrings.ui_shared_styles_not_found_styles_shared_styles_css)
            return null
        }
        return runCatching { resource.bytes.decodeToString() }
            .onFailure { error -> LogKit.e(AppStrings.ui_shared_style_content_decoding_failed_arg0.format(arg0 = (error.message).toString()), error) }
            .getOrNull()
    }

    private suspend fun getThemeConfigCss(): String {
        val colorSchemes = mainState.currentColorSchemes.value
        val lightScheme = colorSchemes?.light ?: getDefaultColorScheme(false)
        val darkScheme = colorSchemes?.dark ?: getDefaultColorScheme(true)
        val key = ThemeConfigCacheKey(lightHash = lightScheme.hashCode(), darkHash = darkScheme.hashCode())
        themeCssMutex.withLock {
            val cached = cachedThemeCss
            if (cached != null && cached.key == key) return cached.script
            val script = ThemeConfigScriptBuilder.build(lightScheme = lightScheme, darkScheme = darkScheme)
            cachedThemeCss = CachedThemeConfigCss(key, script)
            return script
        }
    }

    private fun buildFaviconResource(): StaticResource {
        val colorSchemes = mainState.currentColorSchemes.value
        val lightScheme = colorSchemes?.light ?: getDefaultColorScheme(false)
        val darkScheme = colorSchemes?.dark ?: getDefaultColorScheme(true)
        val lightPrimary = lightScheme.primary.toHex()
        val darkPrimary = darkScheme.primary.toHex()
        val definition = svgIconDefinition(SvgIcon.Folder)
        val svg = if (definition != null) {
            buildString {
                append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"")
                append(definition.viewBox)
                append("\" role=\"img\" aria-hidden=\"true\" focusable=\"false\" width=\"64\" height=\"64\">")
                append("<style>:root{color-scheme:light dark;}path{fill:")
                append(lightPrimary)
                append(";}")
                if (!lightPrimary.equals(darkPrimary, ignoreCase = true)) {
                    append("@media (prefers-color-scheme: dark){path{fill:")
                        .append(darkPrimary)
                        .append(";}}")
                }
                append("</style>")
                definition.paths.forEach { path ->
                    append("<path d=\"")
                    append(path)
                    append("\"/>")
                }
                append("</svg>")
            }
        } else {
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\" role=\"img\" aria-hidden=\"true\" focusable=\"false\" width=\"64\" height=\"64\"><rect width=\"64\" height=\"64\" rx=\"12\" fill=\"$lightPrimary\"/></svg>"
        }
        val bytes = svg.encodeToByteArray()
        return StaticResource(
            bytes = bytes,
            contentType = "image/svg+xml",
            etag = "favicon_${lightPrimary}_${darkPrimary}",
            size = bytes.size
        )
    }

    private fun Color.toHex(): String {
        val argb = toArgb()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return buildString(7) {
            append('#')
            append(r.toHexComponent())
            append(g.toHexComponent())
            append(b.toHexComponent())
        }
    }

    private fun Int.toHexComponent(): String {
        val hex = toString(16).uppercase()
        return if (hex.length == 1) "0$hex" else hex
    }

    private suspend fun LinkShareHttpRequest.readBodyText(): String {
        if (body is LinkShareHttpRequestBody.Bytes) {
            return body.copyBytes().decodeToString()
        }
        val bytes = mutableListOf<Byte>()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = body.read(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            repeat(read) { index -> bytes += buffer[index] }
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun LinkShareHttpResponse.withCommonHeaders(request: LinkShareHttpRequest): LinkShareHttpResponse {
        val existing = headers.toMutableList()
        val headerNames = existing.map { item -> item.name.lowercase() }.toMutableSet()
        fun addIfAbsent(header: LinkShareHttpHeader) {
            if (header.name.lowercase() !in headerNames) {
                existing += header
                headerNames += header.name.lowercase()
            }
        }
        LinkShareHttpHeaderPolicies.securityHeaders(request.path).forEach(::addIfAbsent)
        LinkShareHttpHeaderPolicies.corsHeaders(request, advertisedHostProvider.get()).forEach(::addIfAbsent)
        return copy(headers = existing)
    }

    private fun LinkShareHttpExchange.responseOrForbidden(): LinkShareHttpResponse {
        return getOrNull(LINK_SHARE_UPLOAD_REJECTION_KEY) ?: LinkShareHttpResponse.bytes(statusCode = 403)
    }

    private data class ThemeConfigCacheKey(val lightHash: Int, val darkHash: Int)
    private data class CachedThemeConfigCss(val key: ThemeConfigCacheKey, val script: String)
}

private fun LinkShareHttpResponse.withExchangeCookies(exchange: LinkShareHttpExchange): LinkShareHttpResponse {
    val cookies = listOfNotNull(
        exchange.getOrNull(LINK_SHARE_PENDING_COOKIE_KEY),
        exchange.getOrNull(LINK_SHARE_PENDING_SESSION_COOKIE_KEY),
    )
    if (cookies.isEmpty()) return this
    return copy(headers = headers + cookies.map { cookie -> LinkShareHttpHeader("Set-Cookie", cookie.toHeaderValue()) })
}

internal fun String.urlPath(): String {
    if (isEmpty()) return this
    return split('/').joinToString("/") { segment ->
        if (segment.isEmpty()) "" else encodeUrlPathPart(segment)
    }
}

private fun encodeUrlPathPart(value: String): String {
    val bytes = value.encodeToByteArray()
    return buildString {
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            val char = unsigned.toChar()
            if (
                char in 'A'..'Z' ||
                char in 'a'..'z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '_' ||
                char == '.' ||
                char == '~'
            ) {
                append(char)
            } else {
                append('%')
                append(unsigned.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}

data class StaticResource(
    val bytes: ByteArray,
    val contentType: String,
    val etag: String,
    val size: Int,
)

private val LINK_SHARE_DEVICE_KEY = LinkShareHttpAttributeKey<Device>("LinkShareDevice")
private val LINK_SHARE_AUTHORIZATION_KEY = LinkShareHttpAttributeKey<LinkShareAuthorization>("LinkShareAuthorization")
private val LINK_SHARE_SESSION_TOKEN_KEY = LinkShareHttpAttributeKey<String>("LinkShareSessionToken")
private val LINK_SHARE_PENDING_COOKIE_KEY = LinkShareHttpAttributeKey<LinkShareHttpCookie>("LinkSharePendingCookie")
private val LINK_SHARE_PENDING_SESSION_COOKIE_KEY = LinkShareHttpAttributeKey<LinkShareHttpCookie>("LinkSharePendingSessionCookie")
private val LINK_SHARE_UPLOAD_REJECTION_KEY = LinkShareHttpAttributeKey<LinkShareHttpResponse>("LinkShareUploadRejection")

private const val LINK_SHARE_AUTH_PATH = "/auth"
private const val LINK_SHARE_CLIENT_COOKIE = "FolderSpanLinkShareClient"
private const val LINK_SHARE_SESSION_COOKIE = "FolderSpanLinkShareSession"
private const val LINK_SHARE_SESSION_HEADER = "X-FolderSpan-Link-Session"
private const val LINK_SHARE_TICKET_QUERY = "ticket"
private const val LINK_SHARE_CLIENT_COOKIE_MAX_AGE_SECONDS = 12 * 60 * 60
private const val LINK_SHARE_SESSION_COOKIE_MAX_AGE_SECONDS = 12 * 60 * 60
private const val SHARED_DOWNLOAD_PREFETCH_CHUNKS = 4
private const val HTTP_SHARE_UPLOAD_CHUNK_SIZE = MAX_LENGTH
private const val DEVELOPMENT_RESOURCE_ROOT_SEARCH_DEPTH = 6

private enum class ShareUploadItemType {
    File,
    Directory
}

private data class ShareUploadItem(
    val type: ShareUploadItemType,
    val relativePath: String,
    val segments: List<String>,
)

private data class ShareUploadTarget(
    val root: FileSimpleInfo,
    val disk: DiskBase,
    val path: String,
    val relativeSegments: List<String>,
)

private data class ShareUploadRequest(
    val target: ShareUploadTarget,
    val item: ShareUploadItem,
    val destinationPath: String,
    val overwrite: Boolean,
    val declaredSize: Long,
    val contentRange: ShareUploadContentRange?,
) {
    fun isChunkContinuation(): Boolean {
        return item.type == ShareUploadItemType.File && contentRange?.isFirst == false
    }

    fun uploadCleanupKey(): String {
        return "${target.path}\u0000$destinationPath"
    }
}

private data class ShareUploadContentRange(
    val start: Long,
    val endInclusive: Long,
    val totalSize: Long,
) {
    val length: Long
        get() = endInclusive - start + 1

    val isFirst: Boolean
        get() = start == 0L

    val isLast: Boolean
        get() = endInclusive + 1 == totalSize
}
