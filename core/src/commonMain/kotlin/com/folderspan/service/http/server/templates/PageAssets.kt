package com.folderspan.service.http.server.templates

/**
 * 页面静态资源配置。
 *
 * @property inlineStyles 需要内联到 HTML 中的全局样式表（若为空则退回到外链）。
 * @property themeConfigCss 需要内联到 HTML 中的主题配置样式（若为空则退回到外链）。
 */
data class PageAssets(
    val inlineStyles: String? = null,
    val themeConfigCss: String? = null
)
