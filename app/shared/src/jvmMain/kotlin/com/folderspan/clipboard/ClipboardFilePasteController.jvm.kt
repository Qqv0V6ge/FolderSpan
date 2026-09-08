package com.folderspan.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.folderspan.data.file.FileProtocol
import com.folderspan.ui.screen.main.shouldHandleClipboardFilePasteShortcut
import com.folderspan.ui.state.file.ExternalFileResourceLease
import com.folderspan.ui.state.file.ExternalFileResourceLeaseRegistry
import com.folderspan.ui.state.file.ExternalFileSkip
import com.folderspan.ui.state.file.ExternalFileSkipReason
import com.folderspan.ui.state.file.PreparedExternalFileBatch
import com.folderspan.ui.state.file.buildPreparedExternalFileBatch
import com.folderspan.ui.state.file.clipboardImageFileName
import com.folderspan.ui.state.file.externalFileStagingRootPath
import com.folderspan.ui.state.file.newExternalFileLeaseId
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.prepareDesktopExternalFiles
import java.awt.Graphics2D
import java.awt.Image
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.TextComponent
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import javax.imageio.ImageIO
import javax.swing.text.JTextComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

actual suspend fun readClipboardFileBatch(): PreparedExternalFileBatch = withContext(Dispatchers.IO) {
    val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull()
        ?: return@withContext PreparedExternalFileBatch()
    val transferable = runCatching { clipboard.getContents(null) }.getOrNull()
        ?: return@withContext PreparedExternalFileBatch()
    prepareDesktopClipboardTransferable(transferable)
}

internal fun prepareDesktopClipboardTransferable(transferable: Transferable): PreparedExternalFileBatch {
    if (runCatching { transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }.getOrDefault(false)) {
        val files = runCatching {
            (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                .orEmpty()
                .filterIsInstance<File>()
        }.getOrDefault(emptyList())
        if (files.isNotEmpty()) return prepareDesktopExternalFiles(files)
    }
    if (!runCatching { transferable.isDataFlavorSupported(DataFlavor.imageFlavor) }.getOrDefault(false)) {
        return PreparedExternalFileBatch()
    }
    val image = runCatching { transferable.getTransferData(DataFlavor.imageFlavor) as? Image }.getOrNull()
        ?: return PreparedExternalFileBatch(
            skipped = listOf(ExternalFileSkip(ExternalFileSkipReason.Unreadable)),
            representedItemCount = 1,
        )
    return prepareDesktopClipboardImage(image)
}

internal fun prepareDesktopClipboardImage(
    image: Image,
    epochMillis: Long = Clock.System.now().toEpochMilliseconds(),
): PreparedExternalFileBatch {
    val bufferedImage = image.toBufferedImage() ?: return PreparedExternalFileBatch(
        skipped = listOf(ExternalFileSkip(ExternalFileSkipReason.Unreadable)),
        representedItemCount = 1,
    )
    val leaseId = newExternalFileLeaseId()
    val rootPath = externalFileStagingRootPath(leaseId)
    val rootDirectory = File(rootPath)
    val fileName = clipboardImageFileName(epochMillis) ?: return PreparedExternalFileBatch()
    val outputFile = File(rootDirectory, fileName)
    val written = runCatching {
        (rootDirectory.mkdirs() || rootDirectory.isDirectory) &&
            restrictOwnerOnlyDirectory(rootDirectory) &&
            ImageIO.write(bufferedImage, "png", outputFile) &&
            restrictOwnerOnlyFile(outputFile)
    }.getOrDefault(false)
    if (!written) {
        rootDirectory.deleteRecursively()
        return PreparedExternalFileBatch(
            skipped = listOf(ExternalFileSkip(ExternalFileSkipReason.Unreadable, fileName)),
            representedItemCount = 1,
        )
    }
    val source = FileUtils.getFile(FileAccessPermission.Allowed, outputFile.absolutePath)
        .getOrNull()
        ?.withCopy(protocol = FileProtocol.Local, protocolId = "")
    if (source == null) {
        rootDirectory.deleteRecursively()
        return PreparedExternalFileBatch(
            skipped = listOf(ExternalFileSkip(ExternalFileSkipReason.Unreadable, fileName)),
            representedItemCount = 1,
        )
    }
    val lease = ExternalFileResourceLease(leaseId, rootPath)
    ExternalFileResourceLeaseRegistry.register(lease)
    return buildPreparedExternalFileBatch(
        files = listOf(source),
        lease = lease,
        representedItemCount = 1,
    )
}

private fun restrictOwnerOnlyDirectory(directory: File): Boolean {
    restrictOwnerOnlyPath(directory, directory = true)
    return directory.isDirectory
}

private fun restrictOwnerOnlyFile(file: File): Boolean {
    restrictOwnerOnlyPath(file, directory = false)
    return file.isFile
}

private fun restrictOwnerOnlyPath(file: File, directory: Boolean) {
    runCatching {
        val permissions = if (directory) {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        } else {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )
        }
        Files.setPosixFilePermissions(file.toPath(), permissions)
    }
}

private fun Image.toBufferedImage(): BufferedImage? {
    if (this is BufferedImage) return this
    val resolvedWidth = getWidth(null)
    val resolvedHeight = getHeight(null)
    if (resolvedWidth <= 0 || resolvedHeight <= 0) return null
    val buffered = BufferedImage(resolvedWidth, resolvedHeight, BufferedImage.TYPE_INT_ARGB)
    val graphics: Graphics2D = buffered.createGraphics()
    return try {
        if (graphics.drawImage(this, 0, 0, null)) buffered else null
    } finally {
        graphics.dispose()
    }
}

internal fun dispatchDesktopClipboardPasteKeyEvent(
    event: KeyEvent,
    enabled: Boolean,
    onPaste: () -> Unit,
): Boolean {
    if (
        !enabled ||
        event.isConsumed ||
        event.component is TextComponent ||
        event.component is JTextComponent
    ) {
        return false
    }
    val shouldHandle = shouldHandleClipboardFilePasteShortcut(
        isFileBrowserActive = true,
        isEditableContext = false,
        isVKey = event.keyCode == KeyEvent.VK_V,
        isCtrlPressed = event.isControlDown,
        isMetaPressed = event.isMetaDown,
        isAltPressed = event.isAltDown,
        isShiftPressed = event.isShiftDown,
        isKeyDown = event.id == KeyEvent.KEY_PRESSED,
    )
    if (shouldHandle) onPaste()
    return shouldHandle
}

@Composable
actual fun PlatformClipboardFilePasteEffect(
    enabled: Boolean,
    onPasteRequest: (ClipboardFileBatchReader) -> Unit,
    onTextPasteRequest: (ClipboardContent) -> Unit,
) {
    DisposableEffect(enabled, onPasteRequest, onTextPasteRequest) {
        if (!enabled) {
            onDispose {}
        } else {
            val keyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            val dispatcher = KeyEventDispatcher { event ->
                dispatchDesktopClipboardPasteKeyEvent(event, enabled = true) {
                    val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull()
                    val transferable = runCatching { clipboard?.getContents(null) }.getOrNull()
                        ?: return@dispatchDesktopClipboardPasteKeyEvent
                    if (hasDesktopClipboardFilePayload(transferable)) {
                        onPasteRequest {
                            withContext(Dispatchers.IO) {
                                prepareDesktopClipboardTransferable(transferable)
                            }
                        }
                    } else {
                        desktopClipboardTextContent(transferable)?.let(onTextPasteRequest)
                    }
                }
            }
            keyboardFocusManager.addKeyEventDispatcher(dispatcher)
            onDispose {
                keyboardFocusManager.removeKeyEventDispatcher(dispatcher)
            }
        }
    }
}

internal fun hasDesktopClipboardFilePayload(transferable: Transferable): Boolean {
    val hasFiles = runCatching {
        transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) &&
            (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                .orEmpty()
                .filterIsInstance<File>()
                .isNotEmpty()
    }.getOrDefault(false)
    return hasFiles || runCatching {
        transferable.isDataFlavorSupported(DataFlavor.imageFlavor)
    }.getOrDefault(false)
}

internal fun desktopClipboardTextContent(transferable: Transferable): ClipboardContent? {
    if (!runCatching { transferable.isDataFlavorSupported(DataFlavor.stringFlavor) }.getOrDefault(false)) {
        return null
    }
    val text = runCatching {
        transferable.getTransferData(DataFlavor.stringFlavor) as? String
    }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
    return ClipboardContent(texts = listOf(text))
}
