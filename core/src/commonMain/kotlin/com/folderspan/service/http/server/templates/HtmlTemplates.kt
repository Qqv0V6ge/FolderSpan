package com.folderspan.service.http.server.templates

import strings.AppStrings

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.http.server.LinkSharePageConfig
import com.folderspan.service.http.server.templates.route.BreadcrumbItem
import kotlinx.html.HTML

/**
 * HTML DSL模板生成器，用于替代FreeMarker模板
 * 现在使用拆分后的模板结构
 */
object HtmlTemplates {

    /**
     * 生成共享文件列表页面
     */
    fun indexPage(
        search: String? = null,
        files: List<FileSimpleInfo> = emptyList(),
        scripts: Map<String, Pair<String, String>> = emptyMap(),
        breadcrumbs: List<BreadcrumbItem> = emptyList(),
        allowUpload: Boolean = false,
        canRequestUpload: Boolean = false,
        linkSharePageConfig: LinkSharePageConfig? = null,
        assets: PageAssets = PageAssets()
    ): HTML.() -> Unit =
        TemplateFactory.indexTemplate.render(
            search,
            files,
            scripts,
            breadcrumbs,
            allowUpload,
            canRequestUpload,
            linkSharePageConfig,
            assets
        )

    /**
     * 生成密码验证页面
     */
    fun passwordPage(
        error: String? = null,
        redirectPath: String = "/",
        assets: PageAssets = PageAssets()
    ): HTML.() -> Unit = TemplateFactory.passwordTemplate.render(error, redirectPath, assets)

    /**
     * 生成等待页面
     */
    fun waitingPage(assets: PageAssets = PageAssets()): HTML.() -> Unit =
        TemplateFactory.waitingTemplate.render(assets)

    /**
     * 生成访问被拒绝页面
     */
    fun requestRejectedPage(
        message: String = AppStrings.ui_the_request_to_access_the_device_has_been_denied,
        assets: PageAssets = PageAssets()
    ): HTML.() -> Unit = TemplateFactory.requestRejectedTemplate.render(message, assets)
}
