package com.folderspan.service.http.server.templates.route

import strings.AppStrings

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.extensions.formatFileSize
import com.folderspan.getSocketDevice
import com.folderspan.service.http.server.LinkSharePageConfig
import com.folderspan.service.http.server.linkshare.resolveLinkShareBrowserPreviewContentType
import com.folderspan.service.http.server.templates.BaseTemplate
import com.folderspan.service.http.server.templates.PageAssets
import com.folderspan.service.http.server.templates.SvgIcon
import com.folderspan.service.http.server.templates.svgIcon
import kotlinx.html.*

data class BreadcrumbItem(
    val label: String,
    val href: String? = null
)

/**
 * 索引页面模板
 */
class IndexTemplate : BaseTemplate() {
    /**
     * 生成共享文件列表页面
     */
    fun render(
        search: String? = null,
        files: List<FileSimpleInfo> = emptyList(),
        scripts: Map<String, Pair<String, String>> = emptyMap(),
        breadcrumbs: List<BreadcrumbItem> = emptyList(),
        allowUpload: Boolean = false,
        canRequestUpload: Boolean = false,
        linkSharePageConfig: LinkSharePageConfig? = null,
        assets: PageAssets = PageAssets()
    ): HTML.() -> Unit = {
        val title = AppStrings.ui_share_arg0.format(arg0 = (getSocketDevice().name))
        lang = "zh"
        head {
            applyCommonHead(title, assets)
        }
        body {
            applyBodyClasses()
            applyLinkSharePageConfig(linkSharePageConfig)
            renderSkipLink()

            main {
                id = "mainContent"
                classes = setOf("page-container", "page-container--share")
                attributes["tabindex"] = "-1"

                section {
                    classes = setOf("share-layout")
                    attributes["aria-labelledby"] = "sharePageTitle"

                    header {
                        classes = setOf("share-top-app-bar")

                        div {
                            classes = setOf("share-top-app-bar__identity")
                            div {
                                classes = setOf("share-top-app-bar__avatar")
                                attributes["aria-hidden"] = "true"
                                svgIcon(SvgIcon.Folder)
                            }
                            div {
                                classes = setOf("share-top-app-bar__titles")
                                h1 {
                                    id = "sharePageTitle"
                                    classes = setOf("page-header__title")
                                    +title
                                }
                                p {
                                    classes = setOf("page-header__subtitle")
                                    +buildFileSummary(files)
                                }
                            }
                        }

                        renderShareActions(allowUpload, canRequestUpload)
                    }

                    section {
                        classes = setOf("content-surface")

                        renderBreadcrumbs(breadcrumbs)

                        if (allowUpload) {
                            renderUploadSection()
                        }

                        div {
                            classes = setOf("search-section")
                            form {
                                action = ""
                                method = FormMethod.get
                                classes = setOf("search-form")
                                attributes["role"] = "search"

                                div {
                                    classes = setOf("text-field", "text-field--search")

                                    label {
                                        htmlFor = "searchInput"
                                        classes = setOf("sr-only")
                                        +AppStrings.ui_search_files
                                    }

                                    span {
                                        classes = setOf("text-field__icon")
                                        svgIcon(SvgIcon.Search)
                                    }

                                    input {
                                        type = InputType.search
                                        name = "search"
                                        id = "searchInput"
                                        placeholder = AppStrings.ui_search_files
                                        value = search ?: ""
                                        classes = setOf("search-input")
                                        attributes["aria-label"] = AppStrings.ui_search_files
                                        attributes["aria-describedby"] = "searchInputHelp"
                                        attributes["enterkeyhint"] = "search"
                                        attributes["inputmode"] = "search"
                                    }
                                }
                                p {
                                    id = "searchInputHelp"
                                    classes = setOf("sr-only")
                                    +AppStrings.ui_input_keywords_and_press_enter_to_filter_the_file_list
                                }
                            }
                        }

                        section {
                            classes = setOf("files-section")
                            attributes["aria-labelledby"] = "filesSectionTitle"

                            div {
                                classes = setOf("files-section__header")
                                h2 {
                                    id = "filesSectionTitle"
                                    classes = setOf("section-title")
                                    +AppStrings.ui_shared_content
                                }
                                span {
                                    classes = setOf("section-counter")
                                    +AppStrings.ui_item_count_arg0.format(arg0 = (files.size).toString())
                                }
                            }

                            renderFileGrid(files)
                        }
                    }
                }
            }

            // 批量下载弹窗
            renderBatchDownloadModal(scripts)
            renderZipDownloadModal(linkSharePageConfig)
            renderHttpsConsentModal()

            if (allowUpload) {
                renderUploadProgressModal()
                renderUploadDropOverlay()
            }

            // JavaScript
            renderJavaScript(allowUpload)
        }
    }

    private fun BODY.applyLinkSharePageConfig(config: LinkSharePageConfig?) {
        if (config == null) return
        attributes["data-link-share-http-base-url"] = config.httpBaseUrl
        attributes["data-link-share-https-base-url"] = config.httpsBaseUrl.orEmpty()
        attributes["data-link-share-https-available"] = config.httpsAvailable.toString()
        attributes["data-link-share-tls-fingerprint"] = config.tlsFingerprintSha256
        attributes["data-link-share-https-consent-key"] = config.httpsConsentKey
        attributes["data-link-share-https-consent-cookie"] = config.httpsConsentCookie
    }

    private fun buildFileSummary(files: List<FileSimpleInfo>): String {
        val directoryCount = files.count { item -> item.isDirectory }
        val fileCount = files.size - directoryCount
        return when {
            directoryCount > 0 && fileCount > 0 -> AppStrings.ui_arg0_folders_arg1_files.format(arg0 = (directoryCount).toString(), arg1 = (fileCount).toString())
            directoryCount > 0 -> AppStrings.ui_arg0_folders.format(arg0 = (directoryCount).toString())
            fileCount > 0 -> AppStrings.ui_arg0_files.format(arg0 = (fileCount).toString())
            else -> AppStrings.ui_no_visible_projects
        }
    }

    private fun FlowContent.renderUploadSection() {
        section {
            id = "uploadSection"
            classes = setOf("upload-section")
            attributes["aria-label"] = AppStrings.ui_upload_files_and_folders

            input {
                id = "uploadFileInput"
                type = InputType.file
                classes = setOf("hidden")
                attributes["aria-hidden"] = "true"
            }

            input {
                id = "uploadDirectoryInput"
                type = InputType.file
                classes = setOf("hidden")
                attributes["webkitdirectory"] = ""
                attributes["directory"] = ""
                attributes["aria-hidden"] = "true"
            }

        }
    }

    private fun FlowContent.renderBreadcrumbs(breadcrumbs: List<BreadcrumbItem>) {
        if (breadcrumbs.isEmpty()) return

        nav {
            attributes["aria-label"] = AppStrings.ui_page_path
            classes = setOf("breadcrumbs")

            breadcrumbs.forEachIndexed { index, item ->
                if (index > 0) {
                    span {
                        classes = setOf("breadcrumb-separator")
                        attributes["aria-hidden"] = "true"
                        svgIcon(SvgIcon.ChevronRight)
                    }
                }

                if (item.href != null) {
                    a {
                        href = item.href
                        classes = setOf("breadcrumb-item", "breadcrumb-item--link")
                        if (index == breadcrumbs.lastIndex) {
                            attributes["aria-current"] = "page"
                        }
                        if (index == 0) {
                            svgIcon(SvgIcon.Home)
                        }
                        +item.label
                    }
                } else {
                    span {
                        classes = setOf("breadcrumb-item")
                        if (index == breadcrumbs.lastIndex) {
                            attributes["aria-current"] = "page"
                        }
                        if (index == 0) {
                            svgIcon(SvgIcon.Home)
                        }
                        +item.label
                    }
                }
            }
        }
    }

    internal fun FlowContent.renderFileGrid(files: List<FileSimpleInfo>) {
        div {
            id = "fileGrid"
            classes = setOf("file-grid")
            attributes["role"] = "region"
            attributes["aria-label"] = AppStrings.ui_share_file_and_folder_lists
            attributes["aria-live"] = "polite"

            if (files.isEmpty()) {
                div {
                    classes = setOf("empty-state")
                    attributes["role"] = "status"
                    attributes["aria-live"] = "polite"
                    svgIcon(SvgIcon.Info, "icon--xl")
                    p {
                        classes = setOf("text-muted")
                        +AppStrings.ui_no_matching_file_or_folder_found
                    }
                }
                return@div
            }

            files.forEach { file ->
                if (file.isDirectory) {
                    a {
                        href = file.path
                        classes = setOf("file-card", "file-card--directory", "ripple") +
                            if (file.isHidden) setOf("file-card--hidden") else emptySet()

                        div {
                            classes = setOf("file-card__content")

                            div {
                                classes = setOf("file-card__icon")
                                svgIcon(SvgIcon.Folder)
                            }

                            div {
                                classes = setOf("file-card__text")
                                span {
                                    classes = setOf("file-card__title")
                                    +file.name
                                }
                                span {
                                    classes = setOf("file-card__meta")
                                    +AppStrings.ui_arg0_items.format(arg0 = (file.size).toString())
                                }
                            }
                        }

                        span {
                            classes = setOf("file-card__action")
                            attributes["aria-hidden"] = "true"
                            svgIcon(SvgIcon.ChevronRight)
                        }
                    }
                } else {
                    val previewContentType = resolveLinkShareBrowserPreviewContentType(
                        fileName = file.name,
                        fileMimeTypeHint = file.mineType,
                    )
                    div {
                        classes = setOf("file-card", "file-card--file") +
                            if (file.isHidden) setOf("file-card--hidden") else emptySet()

                        a {
                            href = file.path
                            classes = setOf("file-card__content")
                            attributes["download"] = file.name
                            attributes["aria-label"] = AppStrings.ui_download_arg0.format(arg0 = (file.name))
                            renderFileCardContent(file)
                        }

                        div {
                            classes = setOf("file-card__actions")

                            if (previewContentType != null) {
                                a {
                                    href = linkSharePreviewUrl(file.path)
                                    classes = setOf("file-card__open")
                                    attributes["target"] = "_blank"
                                    attributes["rel"] = "noopener"
                                    attributes["type"] = previewContentType
                                    attributes["data-preview-mime"] = previewContentType
                                    attributes["aria-label"] = AppStrings.ui_open_arg0_in_the_browser.format(arg0 = (file.name))
                                    attributes["title"] = AppStrings.ui_open_in_the_browser
                                    svgIcon(SvgIcon.OpenInNew)
                                }
                            }

                            a {
                                href = file.path
                                attributes["download"] = file.name
                                attributes["aria-label"] = AppStrings.ui_download_arg0.format(arg0 = (file.name))
                                attributes["title"] = AppStrings.ui_download
                                classes = setOf("file-card__download")
                                svgIcon(SvgIcon.Download)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.renderFileCardContent(file: FileSimpleInfo) {
        div {
            classes = setOf("file-card__icon")
            svgIcon(SvgIcon.File)
        }

        div {
            classes = setOf("file-card__text")
            span {
                classes = setOf("file-card__title")
                +file.name
            }
            span {
                classes = setOf("file-card__meta")
                +file.size.formatFileSize()
            }
        }
    }

    private fun FlowContent.renderShareActions(allowUpload: Boolean, canRequestUpload: Boolean) {
        div {
            classes = setOf("share-actions")
            if (allowUpload) {
                button {
                    id = "selectUploadFiles"
                    type = ButtonType.button
                    classes = setOf("btn", "btn--tonal", "ripple")
                    attributes["aria-controls"] = "uploadFileInput"
                    svgIcon(SvgIcon.File)
                    span { +AppStrings.ui_select_file }
                }

                button {
                    id = "selectUploadDirectory"
                    type = ButtonType.button
                    classes = setOf("btn", "btn--tonal", "ripple")
                    attributes["aria-controls"] = "uploadDirectoryInput"
                    svgIcon(SvgIcon.Folder)
                    span { +AppStrings.ui_select_folder }
                }
            } else if (canRequestUpload) {
                button {
                    id = "requestUploadPermission"
                    type = ButtonType.button
                    classes = setOf("btn", "btn--tonal", "ripple")
                    attributes["aria-label"] = AppStrings.ui_request_to_upload_permissions
                    svgIcon(SvgIcon.Upload)
                    span { +AppStrings.ui_request_to_upload_permissions }
                }
            }

            button {
                id = "floatingBatchDownloadBtn"
                type = ButtonType.button
                classes = setOf("btn", "btn--primary", "ripple")
                attributes["aria-controls"] = "batchDownloadModal"
                attributes["aria-haspopup"] = "dialog"
                attributes["aria-expanded"] = "false"
                svgIcon(SvgIcon.Download)
                span { +AppStrings.ui_batch_download }
            }
        }
    }

    private fun BODY.renderUploadDropOverlay() {
        div {
            id = "uploadPageDropOverlay"
            classes = setOf("upload-page-drop-overlay", "hidden")
            attributes["aria-hidden"] = "true"

            div {
                classes = setOf("upload-page-drop-overlay__panel")
                div {
                    classes = setOf("upload-page-drop-overlay__icon")
                    svgIcon(SvgIcon.Upload)
                }
                span {
                    classes = setOf("upload-page-drop-overlay__title")
                    +AppStrings.ui_release_to_upload_current_directory
                }
            }
        }
    }

    private fun BODY.renderBatchDownloadModal(scripts: Map<String, Pair<String, String>>) {
        div {
            id = "batchDownloadModal"
            classes = setOf("modal-overlay", "hidden")
            attributes["aria-hidden"] = "true"

            div {
                classes = setOf("modal")
                attributes["role"] = "dialog"
                attributes["aria-modal"] = "true"
                attributes["tabindex"] = "-1"
                attributes["aria-labelledby"] = "batchDownloadModalTitle"

                h3 {
                    id = "batchDownloadModalTitle"
                    classes = setOf("modal__title")
                    +AppStrings.ui_batch_download
                }

                if (scripts.isNotEmpty()) {
                    div {
                        classes = setOf("script-tabbar")
                        div {
                            classes = setOf("tabs")
                            attributes["role"] = "tablist"
                            var firstScript = true
                            scripts.forEach { (scriptType, scriptInfo) ->
                                val tabId = "${scriptType.lowercase()}-tab"
                                val panelId = "${scriptType.lowercase()}-script"
                                button {
                                    type = ButtonType.button
                                    classes = setOf("tab") + if (firstScript) setOf("tab--active") else emptySet()
                                    attributes["id"] = tabId
                                    attributes["role"] = "tab"
                                    attributes["aria-controls"] = panelId
                                    attributes["aria-selected"] = if (firstScript) "true" else "false"
                                    attributes["tabindex"] = if (firstScript) "0" else "-1"
                                    attributes["data-tab"] = scriptType.lowercase()
                                    attributes["data-ext"] = scriptInfo.first
                                    +scriptType
                                }
                                firstScript = false
                            }
                        }
                        button {
                            id = "downloadScript"
                            type = ButtonType.button
                            classes = setOf("btn", "btn--primary", "ripple", "script-tabbar__action")
                            +AppStrings.ui_download_script
                        }
                    }

                    div {
                        classes = setOf("script-content")

                        var firstScript = true
                        scripts.forEach { (scriptType, scriptInfo) ->
                            val panelId = "${scriptType.lowercase()}-script"
                            val tabId = "${scriptType.lowercase()}-tab"
                            div {
                                id = panelId
                                classes = setOf("script-panel") + if (firstScript) emptySet() else setOf("hidden")
                                attributes["role"] = "tabpanel"
                                attributes["aria-labelledby"] = tabId
                                attributes["tabindex"] = "0"
                                if (!firstScript) {
                                    attributes["aria-hidden"] = "true"
                                }
                                pre {
                                    classes = setOf("font-mono")
                                    +scriptInfo.second
                                }
                            }
                            firstScript = false
                        }
                    }
                } else {
                    p {
                        classes = setOf("text-muted")
                        attributes["role"] = "status"
                        +AppStrings.ui_no_available_script
                    }
                }

                div {
                    classes = setOf("modal-actions")
                    button {
                        id = "closeModal"
                        type = ButtonType.button
                        classes = setOf("btn", "btn--outlined", "ripple")
                        attributes["aria-label"] = AppStrings.ui_close_the_batch_download_pop_up
                        +AppStrings.ui_cancel
                    }
                    button {
                        id = "openZipDownloadModal"
                        type = ButtonType.button
                        classes = setOf("btn", "btn--primary", "ripple")
                        svgIcon(SvgIcon.Download)
                        span { +AppStrings.ui_download_zip }
                    }
                }
            }
        }
    }

    private fun BODY.renderZipDownloadModal(config: LinkSharePageConfig?) {
        div {
            id = "zipDownloadModal"
            classes = setOf("modal-overlay", "hidden")
            attributes["aria-hidden"] = "true"

            div {
                classes = setOf("modal")
                attributes["role"] = "dialog"
                attributes["aria-modal"] = "true"
                attributes["tabindex"] = "-1"
                attributes["aria-labelledby"] = "zipDownloadModalTitle"

                h3 {
                    id = "zipDownloadModalTitle"
                    classes = setOf("modal__title")
                    +AppStrings.ui_download_zip
                }

                section {
                    classes = setOf("batch-download-panel")
                    attributes["aria-label"] = AppStrings.ui_download_from_browser_zip

                    p {
                        id = "zipDownloadStatus"
                        classes = setOf("batch-download-status", "text-muted")
                        attributes["role"] = "status"
                        attributes["aria-live"] = "polite"
                        if (config?.httpsAvailable == true) {
                            +AppStrings.ui_can_the_current_shared_content_be_saved_as_a_zip_file
                        } else {
                            +AppStrings.ui_the_current_platform_does_not_support_downloading_zip_files_you_can
                        }
                    }

                    renderWakeLockToggle("zipKeepAwakeActiveToggle")

                    div {
                        id = "zipDownloadProgress"
                        classes = setOf("batch-download-progress", "hidden")
                        attributes["aria-hidden"] = "true"

                        div {
                            classes = setOf("upload-task__progress")
                            div {
                                id = "zipDownloadProgressBar"
                                classes = setOf("upload-task__progress-bar")
                                attributes["style"] = "width: 0%"
                            }
                        }
                    }
                }

                div {
                    classes = setOf("modal-actions")
                    button {
                        id = "closeZipDownloadModal"
                        type = ButtonType.button
                        classes = setOf("btn", "btn--outlined", "ripple")
                        attributes["aria-label"] = AppStrings.ui_close_zip_download_window
                        +AppStrings.ui_cancel
                    }
                    button {
                        id = "startStreamBatchDownload"
                        type = ButtonType.button
                        classes = setOf("btn", "btn--primary", "ripple")
                        svgIcon(SvgIcon.Download)
                        span { +AppStrings.ui_package_and_save_zip }
                    }
                    button {
                        id = "cancelStreamBatchDownload"
                        type = ButtonType.button
                        classes = setOf("btn", "btn--outlined", "ripple", "hidden")
                        +AppStrings.ui_cancel_packaging
                    }
                }
            }
        }
    }

    private fun BODY.renderUploadProgressModal() {
        div {
            id = "uploadProgressModal"
            classes = setOf("modal-overlay", "hidden")
            attributes["aria-hidden"] = "true"

            div {
                classes = setOf("modal")
                attributes["role"] = "dialog"
                attributes["aria-modal"] = "true"
                attributes["tabindex"] = "-1"
                attributes["aria-labelledby"] = "uploadProgressModalTitle"

                h3 {
                    id = "uploadProgressModalTitle"
                    classes = setOf("modal__title")
                    +AppStrings.ui_upload_progress
                }

                section {
                    classes = setOf("upload-progress-panel")
                    attributes["aria-label"] = AppStrings.ui_upload_progress

                    renderWakeLockToggle("uploadKeepAwakeToggle")

                    div {
                        id = "uploadQueue"
                        classes = setOf("upload-queue", "hidden")
                        attributes["aria-live"] = "polite"
                    }

                    div {
                        id = "uploadCompletionPanel"
                        classes = setOf("upload-completion-panel", "hidden")
                        attributes["aria-hidden"] = "true"
                        attributes["role"] = "status"
                        h4 {
                            classes = setOf("upload-completion-panel__title")
                            +AppStrings.ui_upload_completed
                        }
                        p {
                            classes = setOf("upload-completion-panel__text")
                            +AppStrings.ui_refresh_page_to_view_latest_file_description
                        }
                    }
                }

                div {
                    classes = setOf("modal-actions")
                    button {
                        id = "cancelUploadProgress"
                        type = ButtonType.button
                        classes = setOf("btn", "btn--outlined", "ripple", "hidden")
                        attributes["aria-label"] = AppStrings.ui_cancel_upload
                        svgIcon(SvgIcon.Cancel)
                        span {
                            id = "cancelUploadProgressLabel"
                            +AppStrings.ui_cancel_upload
                        }
                    }
                    button {
                        id = "refreshAfterUpload"
                        type = ButtonType.button
                        classes = setOf("btn", "btn--primary", "ripple", "hidden")
                        attributes["aria-label"] = AppStrings.ui_refresh_page_to_view_latest_file_action
                        svgIcon(SvgIcon.Refresh)
                        span { +AppStrings.ui_refresh_the_page }
                    }
                    button {
                        id = "closeUploadProgressModal"
                        type = ButtonType.button
                        classes = setOf("btn", "btn--outlined", "ripple")
                        attributes["aria-label"] = AppStrings.ui_close_the_upload_progress_dialog
                        +AppStrings.ui_close
                    }
                }
            }
        }
    }

    private fun FlowContent.renderWakeLockToggle(idValue: String) {
        label {
            classes = setOf("wake-lock-toggle")
            input {
                id = idValue
                type = InputType.checkBox
                classes = setOf("wake-lock-switch__input")
                attributes["role"] = "switch"
                attributes["aria-checked"] = "false"
            }
            span {
                classes = setOf("wake-lock-switch__track")
                attributes["aria-hidden"] = "true"
                span {
                    classes = setOf("wake-lock-switch__thumb")
                }
            }
            span {
                classes = setOf("wake-lock-switch__label")
                +AppStrings.ui_screen_is_on
            }
        }
    }

    private fun BODY.renderHttpsConsentModal() {
        div {
            id = "httpsConsentModal"
            classes = setOf("modal-overlay", "hidden")
            attributes["aria-hidden"] = "true"

            div {
                classes = setOf("modal")
                attributes["role"] = "dialog"
                attributes["aria-modal"] = "true"
                attributes["tabindex"] = "-1"
                attributes["aria-labelledby"] = "httpsConsentModalTitle"

                h3 {
                    id = "httpsConsentModalTitle"
                    classes = setOf("modal__title")
                    +AppStrings.ui_to_switch_to_https
                }

                p {
                    classes = setOf("text-muted")
                    +AppStrings.ui_download_zip_requires_secure_context_folderspan_uses_the_local_self_signed
                }

                div {
                    classes = setOf("modal-actions")
                    button {
                        id = "declineHttpsDownload"
                        type = ButtonType.button
                        classes = setOf("btn", "btn--outlined", "ripple")
                        +AppStrings.ui_stay_on_http
                    }
                    button {
                        id = "continueHttpsDownload"
                        type = ButtonType.button
                        classes = setOf("btn", "btn--primary", "ripple")
                        +AppStrings.ui_continue_using_https
                    }
                }
            }
        }
    }

    private fun BODY.renderJavaScript(allowUpload: Boolean) {
        script {
            src = "/static/share/share-wake-lock.js"
        }
        script {
            src = "/static/share/share-zip-download.js"
        }
        script {
            src = "/static/share/share-index.js?v=http-base-url-2"
        }
        if (allowUpload) {
            script {
                src = "/static/share/share-upload.js?v=http-base-url-2"
            }
        }
    }
}

internal fun linkSharePreviewUrl(path: String): String {
    val fragmentIndex = path.indexOf('#')
    val basePath = if (fragmentIndex >= 0) path.substring(0, fragmentIndex) else path
    val fragment = if (fragmentIndex >= 0) path.substring(fragmentIndex) else ""
    val separator = if ('?' in basePath) '&' else '?'
    return "$basePath${separator}preview=1$fragment"
}
