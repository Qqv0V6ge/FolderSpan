package com.folderspan.clipboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.ui.state.file.ExternalFileResourceLeaseRegistry
import java.awt.Color
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.JTextField
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopClipboardImagePreparationTest {
    @Test
    fun desktopTextSnapshotUsesCapturedTransferable() {
        val content = desktopClipboardTextContent(StringSelection("https://example.com/file.zip"))

        assertEquals(listOf("https://example.com/file.zip"), content?.texts)
        assertFalse(hasDesktopClipboardFilePayload(StringSelection("plain text")))
    }

    private var leaseId: String? = null

    @AfterTest
    fun tearDown() {
        leaseId?.let(ExternalFileResourceLeaseRegistry::releaseProducer)
    }

    @Test
    fun awtClipboardImageIsEncodedAsLeasedPngFile() {
        val image = BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, Color.RED.rgb)
        }

        val batch = prepareDesktopClipboardImage(image, epochMillis = 123L)
        val lease = assertNotNull(batch.lease)
        leaseId = lease.id

        val source = batch.files.single()
        assertEquals("clipboard-image-123.png", source.name)
        val decoded = ImageIO.read(File(source.path))
        assertNotNull(decoded)
        assertEquals(2, decoded.width)
        assertEquals(3, decoded.height)
        assertTrue(source.size > 0L)
        assertOwnerOnlyStagingPermissions(File(lease.rootPath.orEmpty()), File(source.path))

        ExternalFileResourceLeaseRegistry.releaseProducer(lease.id)
        leaseId = null
        assertTrue(!File(source.path).exists())
    }

    @Test
    fun desktopClipboardPrefersARealFileOverItsImageRepresentation() {
        val root = Files.createTempDirectory("folderspan-desktop-clipboard")
        try {
            val file = root.resolve("photo.png").toFile().apply { writeText("real-file") }
            val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            val batch = prepareDesktopClipboardTransferable(
                transferableOf(
                    DataFlavor.javaFileListFlavor to listOf(file, file),
                    DataFlavor.imageFlavor to image,
                )
            )

            assertEquals(listOf("photo.png"), batch.files.map { item -> item.name })
            assertEquals(2, batch.representedItemCount)
            assertEquals(1, batch.skipped.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun desktopClipboardIgnoresTextAndReportsInvalidImage() {
        val textOnly = prepareDesktopClipboardTransferable(StringSelection("/tmp/not-a-file"))
        assertTrue(textOnly.files.isEmpty())
        assertTrue(textOnly.skipped.isEmpty())

        val invalid = prepareDesktopClipboardTransferable(
            transferableOf(DataFlavor.imageFlavor to "not-an-image")
        )
        assertTrue(invalid.files.isEmpty())
        assertEquals(1, invalid.representedItemCount)
        assertEquals(1, invalid.skipped.size)
    }

    @Test
    fun desktopDispatcherHandlesNativePasteShortcutWithoutComposeFocus() {
        var pasteCount = 0

        val handled = dispatchDesktopClipboardPasteKeyEvent(
            event = keyEvent(modifiers = InputEvent.CTRL_DOWN_MASK),
            enabled = true,
        ) {
            pasteCount++
        }

        assertTrue(handled)
        assertEquals(1, pasteCount)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun desktopEffectInstallsNativeShortcutDispatcher() = runComposeUiTest {
        var pasteCount = 0
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("paste snapshot"), null)
        setContent {
            PlatformClipboardFilePasteEffect(
                enabled = true,
                onPasteRequest = { pasteCount++ },
                onTextPasteRequest = { pasteCount++ },
            )
        }
        waitForIdle()

        val handled = KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(
            keyEvent(modifiers = InputEvent.CTRL_DOWN_MASK)
        )
        waitForIdle()

        assertTrue(handled)
        assertEquals(1, pasteCount)
    }

    @Test
    fun desktopDispatcherPreservesEditableAndModifiedPasteEvents() {
        var pasteCount = 0
        val onPaste: () -> Unit = { pasteCount++ }

        assertFalse(
            dispatchDesktopClipboardPasteKeyEvent(
                event = keyEvent(component = JTextField(), modifiers = InputEvent.CTRL_DOWN_MASK),
                enabled = true,
                onPaste = onPaste,
            )
        )
        assertFalse(
            dispatchDesktopClipboardPasteKeyEvent(
                event = keyEvent(modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
                enabled = true,
                onPaste = onPaste,
            )
        )
        assertFalse(
            dispatchDesktopClipboardPasteKeyEvent(
                event = keyEvent(modifiers = InputEvent.CTRL_DOWN_MASK),
                enabled = false,
                onPaste = onPaste,
            )
        )
        assertEquals(0, pasteCount)
    }

    private fun keyEvent(
        component: java.awt.Component = JPanel(),
        modifiers: Int,
    ): KeyEvent = KeyEvent(
        component,
        KeyEvent.KEY_PRESSED,
        123L,
        modifiers,
        KeyEvent.VK_V,
        'V',
    )

    private fun transferableOf(vararg values: Pair<DataFlavor, Any>): Transferable = object : Transferable {
        private val content = values.toMap()

        override fun getTransferDataFlavors(): Array<DataFlavor> = content.keys.toTypedArray()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in content

        override fun getTransferData(flavor: DataFlavor): Any =
            content[flavor] ?: throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
    }

    private fun assertOwnerOnlyStagingPermissions(directory: File, file: File) {
        val fileStore = runCatching { Files.getFileStore(directory.toPath()) }.getOrNull() ?: return
        if (!fileStore.supportsFileAttributeView("posix")) return
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            Files.getPosixFilePermissions(directory.toPath()),
        )
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            ),
            Files.getPosixFilePermissions(file.toPath()),
        )
    }
}
