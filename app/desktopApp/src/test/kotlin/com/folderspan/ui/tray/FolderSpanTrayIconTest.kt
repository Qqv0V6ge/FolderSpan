package com.folderspan.ui.tray

import androidx.compose.ui.graphics.Color
import org.jetbrains.skia.Image
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderSpanTrayIconTest {
    @Test
    fun composeNativeTrayPathIsAFileUriJavaCanParse() {
        val file = File.createTempFile("folderspan_tray_", ".png")
        try {
            val path = file.toComposeNativeTrayPath()
            val resolved = File(URI(path.replace(" ", "%20")))

            assertTrue(path.startsWith("file:"))
            assertFalse(path.contains('\\'))
            assertEquals(file.canonicalFile, resolved.canonicalFile)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rendersPngAndWindowsIcoWithTheCurrentSkiaRuntime() {
        val iconFiles = renderFolderSpanTrayIconFiles(Color(0xFF6750A4))
        val pngFile = composeNativeTrayPathToFile(iconFiles.iconPath)
        val icoFile = composeNativeTrayPathToFile(iconFiles.windowsIconPath)

        try {
            val pngBytes = pngFile.readBytes()
            val icoBytes = icoFile.readBytes()

            assertContentEquals(PNG_SIGNATURE, pngBytes.copyOfRange(0, PNG_SIGNATURE.size))
            Image.makeFromEncoded(pngBytes).use { image ->
                assertTrue(image.width > 0)
                assertTrue(image.height > 0)
            }

            assertContentEquals(ICO_HEADER, icoBytes.copyOfRange(0, ICO_HEADER.size))
            assertContentEquals(
                PNG_SIGNATURE,
                icoBytes.copyOfRange(ICO_DATA_OFFSET, ICO_DATA_OFFSET + PNG_SIGNATURE.size),
            )
            assertTrue(iconFiles.iconPath.startsWith("file:"))
            assertTrue(iconFiles.windowsIconPath.startsWith("file:"))
        } finally {
            pngFile.delete()
            icoFile.delete()
        }
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        val ICO_HEADER = byteArrayOf(0, 0, 1, 0, 1, 0)
        const val ICO_DATA_OFFSET = 22
    }
}
