package com.folderspan.ui.tray

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.kdroid.composetray.utils.IconRenderProperties
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class FolderSpanTrayIconFiles(
    val iconPath: String,
    val windowsIconPath: String,
)

internal fun renderFolderSpanTrayIconFiles(fillColor: Color): FolderSpanTrayIconFiles {
    val renderProperties = IconRenderProperties.forCurrentOperatingSystem()
    val pngBytes = renderFolderSpanTrayIconPng(fillColor, renderProperties)
    val pngFile = createTrayIconFile(".png", pngBytes)
    val icoFile = createTrayIconFile(
        suffix = ".ico",
        bytes = pngBytes.toIco(renderProperties.targetWidth, renderProperties.targetHeight),
    )
    return FolderSpanTrayIconFiles(
        iconPath = pngFile.toComposeNativeTrayPath(),
        windowsIconPath = icoFile.toComposeNativeTrayPath(),
    )
}

internal fun File.toComposeNativeTrayPath(): String = toURI().toString()

internal fun composeNativeTrayPathToFile(path: String): File =
    if (path.startsWith("file:")) File(URI(path)) else File(path)

private fun renderFolderSpanTrayIconPng(
    fillColor: Color,
    renderProperties: IconRenderProperties,
): ByteArray {
    val scene = ImageComposeScene(
        width = renderProperties.sceneWidth,
        height = renderProperties.sceneHeight,
        density = renderProperties.sceneDensity,
        coroutineContext = Dispatchers.Unconfined,
    ) {
        Image(
            imageVector = createFolderSpanTrayIcon(fillColor),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
    try {
        return scene.render().use { renderedIcon ->
            if (!renderProperties.requiresScaling) {
                return@use renderedIcon.encodePng()
            }

            Bitmap().use { scaledBitmap ->
                scaledBitmap.allocN32Pixels(renderProperties.targetWidth, renderProperties.targetHeight)
                check(
                    renderedIcon.scalePixels(
                        scaledBitmap.peekPixels() ?: error("Unable to access tray icon pixels"),
                        FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                        true,
                    )
                ) { "Unable to scale tray icon" }
                Image.makeFromBitmap(scaledBitmap).use { scaledImage ->
                    scaledImage.encodePng()
                }
            }
        }
    } finally {
        scene.close()
    }
}

private fun Image.encodePng(): ByteArray =
    encodeToData(EncodedImageFormat.PNG, 100, 6)?.use { data -> data.bytes }
        ?: error("Unable to encode tray icon as PNG")

private fun createTrayIconFile(suffix: String, bytes: ByteArray): File =
    File.createTempFile("folderspan_tray_", suffix).apply {
        writeBytes(bytes)
        deleteOnExit()
    }

private fun ByteArray.toIco(width: Int, height: Int): ByteArray {
    require(width in 1..256 && height in 1..256) {
        "ICO dimensions must be between 1 and 256 pixels"
    }
    return ByteBuffer.allocate(ICO_DATA_OFFSET + size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            putShort(0)
            putShort(1)
            putShort(1)
            put((width % 256).toByte())
            put((height % 256).toByte())
            put(0)
            put(0)
            putShort(1)
            putShort(32)
            putInt(size)
            putInt(ICO_DATA_OFFSET)
            put(this@toIco)
        }
        .array()
}

private const val ICO_DATA_OFFSET = 22

internal fun createFolderSpanTrayIcon(fillColor: Color): ImageVector =
    ImageVector.Builder(
        name = "FolderSpanTrayIcon",
        defaultWidth = 24.dp,
        defaultHeight = 19.2.dp,
        viewportWidth = 20f,
        viewportHeight = 16f,
    ).apply {
        addGroup(translationX = -2f, translationY = -4f)
        addPath(
            pathData = PathParser().parsePathString(folderSpanTrayPath).toNodes(),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(fillColor),
        )
        clearGroup()
    }.build()

private val folderSpanTrayPath = """
    M10 4H4c-1.1 0-1.99 0.9-1.99 2L2 18c0 1.1 0.9 2 2 2h16c1.1 0 2-0.9 2-2V8
    c0-1.1-0.9-2-2-2h-8l-2-2z
    M16.084 14.136c0.336-0.352 0.468-0.824 0.396-1.272l0.636-0.192
    c0.168 0.312 0.5 0.528 0.88 0.528c0.552 0 1-0.448 1-1s-0.448-1-1-1
    C17.448 11.2 17 11.648 17 12.2c0 0.032 0 0.06 0.004 0.088l-0.636 0.192
    c-0.208-0.46-0.644-0.8-1.168-0.868v-0.632c0.456-0.092 0.8-0.496 0.8-0.98
    C16 9.448 15.552 9 15 9s-1 0.448-1 1c0 0.484 0.344 0.888 0.8 0.98v0.632
    c-0.52 0.072-0.956 0.408-1.168 0.868l-0.636-0.192C13 12.26 13 12.232 13 12.2
    c0-0.552-0.448-1-1-1s-1 0.448-1 1 0.448 1 1 1c0.38 0 0.712-0.212 0.88-0.528
    l0.64 0.192c-0.072 0.448 0.06 0.92 0.396 1.272l-0.46 0.572
    C13.32 14.64 13.164 14.6 13 14.6c-0.552 0-1 0.448-1 1s0.448 1 1 1 1-0.448 1-1
    c0-0.244-0.088-0.468-0.232-0.64l0.46-0.572c0.472 0.284 1.072 0.284 1.544 0
    l0.46 0.572c-0.144 0.172-0.232 0.396-0.232 0.64 0 0.552 0.448 1 1 1s1-0.448 1-1
    -0.448-1-1-1c-0.164 0-0.32 0.04-0.456 0.108l-0.46-0.572z
""".trimIndent()
