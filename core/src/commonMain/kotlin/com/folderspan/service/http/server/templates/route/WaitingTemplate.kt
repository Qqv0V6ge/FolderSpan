package com.folderspan.service.http.server.templates.route

import strings.AppStrings

import com.folderspan.service.http.server.templates.BaseTemplate
import com.folderspan.service.http.server.templates.PageAssets
import com.folderspan.service.http.server.templates.SvgIcon
import com.folderspan.service.http.server.templates.svgIcon
import kotlinx.html.*

/**
 * 等待页面模板
 */
class WaitingTemplate : BaseTemplate() {
    /**
     * 生成等待页面
     */
    fun render(assets: PageAssets = PageAssets()): HTML.() -> Unit = {
        lang = "zh"
        head {
            applyCommonHead(AppStrings.ui_file_preparation_in_progress, assets)
            meta { httpEquiv = "refresh"; content = "5" }
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
                            svgIcon(SvgIcon.Clock, "icon--xl")
                        }
                    }

                    h1 {
                        classes = setOf("form-title")
                        +AppStrings.ui_file_preparation_in_progress
                    }

                    p {
                        classes = setOf("form-subtitle")
                        attributes["role"] = "status"
                        attributes["aria-live"] = "polite"
                        +AppStrings.ui_opponent_is_preparing_a_file_please_wait_a_moment
                    }

                    div {
                        classes = setOf("waiting-illustration")
                        div {
                            classes = setOf("spinner")
                            attributes["role"] = "presentation"
                        }
                    }
                }
            }
        }
    }
}
