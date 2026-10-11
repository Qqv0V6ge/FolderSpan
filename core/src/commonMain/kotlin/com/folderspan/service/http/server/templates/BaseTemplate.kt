package com.folderspan.service.http.server.templates

import strings.AppStrings

import kotlinx.html.*

/**
 * 基础HTML模板，定义共用的HTML结构和样式
 */
abstract class BaseTemplate {
    /**
     * 应用通用的头部元素
     */
    protected fun HEAD.applyCommonHead(title: String, assets: PageAssets = PageAssets()) {
        meta { charset = "UTF-8" }
        meta { name = "viewport"; content = "width=device-width, initial-scale=1.0" }
        title { +title }
        script {
            src = "/static/i18n.js"
        }
        val inlineStyles = assets.inlineStyles?.takeIf { item ->  item.isNotBlank() }
        if (inlineStyles != null) {
            style {
                unsafe {
                    +inlineStyles
                }
            }
        } else {
            link {
                rel = "stylesheet"
                href = "/static/styles/shared-styles.css"
            }
        }

        assets.themeConfigCss
            ?.takeIf { item ->  item.isNotBlank() }
            ?.let { themeCss ->
                style {
                    unsafe { +themeCss }
                }
            }
            ?: link {
                rel = "stylesheet"
                href = "/static/theme-config.css"
            }
    }

    /**
     * 应用通用的body类
     */
    protected fun BODY.applyBodyClasses() {
        classes = setOf("app-body")
    }

    /**
     * 渲染跳转到主要内容的无障碍链接
     */
    protected fun BODY.renderSkipLink(targetId: String = "mainContent") {
        a {
            href = "#$targetId"
            classes = setOf("skip-link")
            +AppStrings.ui_jump_to_the_main_content
        }
    }
}
