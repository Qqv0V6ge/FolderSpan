package com.folderspan.ui.components.avatar

import kotlinx.browser.document
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import org.w3c.dom.HTMLInputElement
import org.w3c.files.FileReader
import strings.AppStrings
import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalWasmJsInterop::class)
internal actual val browserAvatarSourcePicker: BrowserAvatarSourcePicker? =
    BrowserAvatarSourcePicker { onResult ->
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = ".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp"
        input.multiple = false
        var completed = false

        fun complete(result: BrowserAvatarSourceResult) {
            if (completed) return
            completed = true
            input.remove()
            onResult(result)
        }

        input.addEventListener("cancel", {
            complete(BrowserAvatarSourceResult.Cancelled)
        })
        input.addEventListener("change", {
            val file = input.files?.item(0)
            if (file == null) {
                complete(BrowserAvatarSourceResult.Cancelled)
            } else {
                val reader = FileReader()
                reader.addEventListener("load", {
                    val buffer = reader.result as? ArrayBuffer
                    if (buffer == null) {
                        complete(
                            BrowserAvatarSourceResult.Failed(
                                AppStrings.ui_profile_avatar_source_unreadable,
                            ),
                        )
                    } else {
                        complete(BrowserAvatarSourceResult.Selected(Int8Array(buffer).toByteArray()))
                    }
                })
                reader.addEventListener("abort", {
                    complete(BrowserAvatarSourceResult.Cancelled)
                })
                reader.addEventListener("error", {
                    complete(
                        BrowserAvatarSourceResult.Failed(
                            AppStrings.ui_profile_avatar_source_unreadable,
                        ),
                    )
                })
                reader.readAsArrayBuffer(file)
            }
        })
        document.body?.appendChild(input)
        input.click()
    }
