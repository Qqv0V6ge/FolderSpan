package com.folderspan.service.http.server.templates.route

import strings.AppStrings

import com.folderspan.service.http.server.templates.BaseTemplate
import com.folderspan.service.http.server.templates.PageAssets
import com.folderspan.service.http.server.templates.SvgIcon
import com.folderspan.service.http.server.templates.svgIcon
import kotlinx.html.*

/**
 * 访问请求被拒绝页面模板
 */
class RequestRejectedTemplate : BaseTemplate() {
    /**
     * 生成访问被拒绝页面
     */
    fun render(message: String = AppStrings.ui_the_request_to_access_the_device_has_been_denied, assets: PageAssets = PageAssets()): HTML.() -> Unit = {
        lang = "zh"
        head {
            applyCommonHead(AppStrings.ui_access_denied, assets)
        }
        body {
            applyBodyClasses()
            renderSkipLink()

            main {
                id = "mainContent"
                classes = setOf("page-container", "page-container--centered")
                attributes["tabindex"] = "-1"

                div {
                    classes = setOf("surface-card", "surface-card--narrow", "text-center")

                    div {
                        classes = setOf("waiting-illustration")
                        div {
                            classes = setOf("waiting-illustration__icon")
                            attributes["aria-hidden"] = "true"
                            svgIcon(SvgIcon.Info, "icon--xl")
                        }
                    }

                    h1 {
                        classes = setOf("form-title")
                        +AppStrings.ui_access_denied
                    }

                    p {
                        classes = setOf("form-subtitle")
                        attributes["role"] = "alert"
                        +message
                    }
                }
            }
        }
    }
}
