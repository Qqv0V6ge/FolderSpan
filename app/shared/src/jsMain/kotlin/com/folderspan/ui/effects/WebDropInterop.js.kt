package com.folderspan.ui.effects

import org.w3c.dom.DataTransferItem
import org.w3c.dom.DataTransferItemList

internal fun dataTransferItemAt(items: DataTransferItemList, index: Int): DataTransferItem? {
    val dynamicItems = items.asDynamic()
    val item = if (jsTypeOf(dynamicItems.item) == "function") {
        dynamicItems.item(index)
    } else {
        dynamicItems[index]
    }
    if (item == null) return null
    return item.unsafeCast<DataTransferItem>()
}

internal fun webkitGetAsEntry(item: DataTransferItem): FileSystemEntry? {
    val dynamicItem = item.asDynamic()
    if (jsTypeOf(dynamicItem.webkitGetAsEntry) != "function") return null
    val entry = dynamicItem.webkitGetAsEntry() ?: return null
    return entry.unsafeCast<FileSystemEntry>()
}
