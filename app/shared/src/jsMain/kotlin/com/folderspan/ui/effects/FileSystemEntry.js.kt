@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.ui.effects

external interface FileSystemEntry : JsAny {
    val isDirectory: Boolean?
    val name: String?
}
