package com.folderspan.service.http.server.templates.route

import strings.AppStrings

import com.folderspan.service.http.server.templates.BaseTemplate
import com.folderspan.service.http.server.templates.PageAssets
import com.folderspan.service.http.server.templates.SvgIcon
import com.folderspan.service.http.server.templates.svgIcon
import kotlinx.html.*

/**
 * 密码验证页面模板
 */
class PasswordTemplate : BaseTemplate() {
    /**
     * 生成密码验证页面
     */
    fun render(
        error: String? = null,
        redirectPath: String = "/",
        assets: PageAssets = PageAssets()
    ): HTML.() -> Unit = {
        lang = "zh"
        head {
            applyCommonHead(AppStrings.ui_access_verification, assets)
        }
        body {
            applyBodyClasses()
            renderSkipLink()

            main {
                id = "mainContent"
                classes = setOf("page-container", "page-container--centered")
                attributes["tabindex"] = "-1"

                div {
                    classes = setOf("surface-card", "surface-card--narrow")

                    form {
                        action = "/auth"
                        method = FormMethod.post
                        classes = setOf("form-fields")
                        attributes["aria-labelledby"] = "passwordFormTitle"
                        attributes["aria-describedby"] = "passwordFormDescription"

                        input {
                            type = InputType.hidden
                            name = "redirect"
                            value = redirectPath
                        }

                        div {
                            classes = setOf("text-center")
                            h1 {
                                classes = setOf("form-title")
                                id = "passwordFormTitle"
                                +AppStrings.ui_access_verification
                            }
                            p {
                                classes = setOf("form-subtitle")
                                id = "passwordFormDescription"
                                +AppStrings.ui_this_content_requires_a_password_to_access
                            }
                        }

                        div {
                            classes = setOf("text-field")
                            label {
                                htmlFor = "passwordInput"
                                classes = setOf("sr-only")
                                +AppStrings.ui_access_password
                            }
                            span {
                                classes = setOf("text-field__icon")
                                svgIcon(SvgIcon.Lock)
                            }
                            input {
                                type = InputType.password
                                name = "pwd"
                                id = "passwordInput"
                                placeholder = AppStrings.ui_enter_the_access_password
                                classes = setOf("text-field__input")
                                attributes["autocomplete"] = "current-password"
                                attributes["aria-required"] = "true"
                                attributes["aria-describedby"] = buildString {
                                    append("passwordInputHint")
                                    if (error != null) {
                                        append(" passwordError")
                                    }
                                }
                                if (error != null) {
                                    attributes["aria-invalid"] = "true"
                                }
                                required = true
                            }
                        }

                        p {
                            id = "passwordInputHint"
                            classes = setOf("sr-only")
                            +AppStrings.ui_please_enter_the_shared_password_provided_by_the_device_owner_the_password
                        }

                        if (error != null) {
                            div {
                                classes = setOf("form-error")
                                id = "passwordError"
                                attributes["role"] = "alert"
                                svgIcon(SvgIcon.Info)
                                +error
                            }
                        }

                        button {
                            type = ButtonType.submit
                            classes = setOf("btn", "btn--primary", "btn--block", "ripple")
                            svgIcon(SvgIcon.ChevronRight)
                            span { +AppStrings.ui_check_access }
                        }
                    }
                }
            }
        }
    }
}
