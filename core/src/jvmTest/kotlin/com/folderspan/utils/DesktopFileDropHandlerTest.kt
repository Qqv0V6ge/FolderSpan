package com.folderspan.utils

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopFileDropHandlerTest {
    @Test
    fun acceptsFileListsAndTextButRejectsImages() {
        assertTrue(supportsDesktopDrop(arrayOf(DataFlavor.javaFileListFlavor)))
        assertTrue(supportsDesktopDrop(StringSelection("文字").transferDataFlavors))
        assertFalse(supportsDesktopDrop(arrayOf(DataFlavor.imageFlavor)))
    }

    @Test
    fun preservesDroppedTextIncludingLineBreaks() {
        val text = "  /tmp/example\nhttps://example.com/文件  "
        assertEquals(text, readDesktopDropText(StringSelection(text)))
        assertNull(readDesktopDropText(StringSelection(" \n ")))
    }

    @Test
    fun readsUtf8TextStreams() {
        val flavor = DataFlavor("text/plain;charset=UTF-8;class=java.io.InputStream")
        val text = "拖入文本"
        val transferable = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(flavor)
            override fun isDataFlavorSupported(candidate: DataFlavor) = candidate == flavor
            override fun getTransferData(candidate: DataFlavor) =
                ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        }
        assertTrue(supportsDesktopDrop(transferable.transferDataFlavors))
        assertEquals(text, readDesktopDropText(transferable))
    }
}
