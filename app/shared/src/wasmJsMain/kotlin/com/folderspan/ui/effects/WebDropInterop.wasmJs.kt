@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.ui.effects

import org.w3c.dom.DataTransferItem
import org.w3c.dom.DataTransferItemList
import org.w3c.files.File

internal fun dataTransferItemAt(items: DataTransferItemList, index: Int): DataTransferItem? =
    dataTransferItemAtJs(items, index)

@JsFun("(items, index) => items && (items.item ? items.item(index) : items[index])")
private external fun dataTransferItemAtJs(items: DataTransferItemList, index: Int): DataTransferItem?

internal fun webkitGetAsEntry(item: DataTransferItem): FileSystemEntry? =
    webkitGetAsEntryJs(item)

@JsFun("(item) => item && item.webkitGetAsEntry ? item.webkitGetAsEntry() : null")
private external fun webkitGetAsEntryJs(item: DataTransferItem): FileSystemEntry?

@JsFun("(file) => file && file.webkitRelativePath ? file.webkitRelativePath : ''")
private external fun webkitRelativePathJs(file: File): String
