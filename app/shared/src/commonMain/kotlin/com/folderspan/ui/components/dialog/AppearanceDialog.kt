package com.folderspan.ui.components.dialog

import strings.AppStrings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.folderspan.ui.theme.parseHexColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun SeedColorPickerDialog(
    currentHex: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val initialColor = parseHexColor(currentHex) ?: MaterialTheme.colorScheme.primary
    val initialHsv = remember(initialColor) { initialColor.toHsv() }

    var hue by rememberSaveable(currentHex) { mutableStateOf(initialHsv[0]) }
    var saturation by rememberSaveable(currentHex) { mutableStateOf(initialHsv[1]) }
    var value by rememberSaveable(currentHex) { mutableStateOf(initialHsv[2]) }

    var customHex by rememberSaveable(currentHex) { mutableStateOf(currentHex.uppercase()) }

    val previewColor = remember(hue, saturation, value) {
        hsvToColor(hue, saturation, value)
    }
    val previewHex = remember(previewColor) { previewColor.toHexString() }
    val hexIsValid = remember(customHex) { parseHexColor(customHex) != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = hexIsValid,
                onClick = {
                    val targetHex = if (hexIsValid) customHex.uppercase() else previewHex
                    onConfirm(targetHex)
                }
            ) {
                Text(AppStrings.ui_save)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppStrings.ui_cancel) } },
        title = { Text(AppStrings.ui_choose_theme_color) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        color = previewColor,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.size(56.dp)
                    ) {}
                    Column {
                        Text(
                            text = previewHex,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Hue ${hue.toInt()} • Sat ${(saturation * 100).toInt()}% • Tone ${(value * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                ColorWheel(
                    hue = hue,
                    saturation = saturation,
                    enabled = true,
                    onChange = { h, s ->
                        hue = h
                        saturation = s
                        customHex = hsvToColor(h, s, value).toHexString()
                    }
                )

                SliderGroup(
                    label = AppStrings.ui_brightness,
                    value = value,
                    valueRange = 0f..1f,
                    onValueChange = { v ->
                        value = v
                        customHex = hsvToColor(hue, saturation, value).toHexString()
                    }
                )
            }
        }
    )
}

@Composable
private fun ColorWheel(
    hue: Float,
    saturation: Float,
    enabled: Boolean,
    onChange: (Float, Float) -> Unit
) {
    val wheelColors = remember {
        buildList {
            for (angle in 0..360 step 15) {
                add(hsvToColor(angle.toFloat(), 1f, 1f))
            }
            add(hsvToColor(0f, 1f, 1f))
        }
    }

    Box(
        modifier = Modifier
            .size(220.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val dimension = min(size.width, size.height).toFloat()
                    handlePointer(offset, dimension, onChange)
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        val dimension = min(size.width, size.height).toFloat()
                        handlePointer(offset, dimension, onChange)
                    },
                    onDrag = { change, _ ->
                        val dimension = min(size.width, size.height).toFloat()
                        handlePointer(change.position, dimension, onChange)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            drawCircle(
                brush = Brush.sweepGradient(colors = wheelColors),
                radius = radius,
                center = center
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White, Color.Transparent)),
                radius = radius,
                center = center
            )

            val indicatorRadius = radius * saturation
            val angleRad = hue.toDouble() * PI / 180.0
            val indicatorCenter = Offset(
                x = center.x + (indicatorRadius * cos(angleRad)).toFloat(),
                y = center.y + (indicatorRadius * sin(angleRad)).toFloat()
            )
            drawCircle(color = Color.White, center = indicatorCenter, radius = 10f)
            drawCircle(
                color = Color.Black.copy(alpha = 0.4f),
                center = indicatorCenter,
                radius = 10f,
                style = Stroke(2f)
            )
        }
    }
}

private fun handlePointer(offset: Offset, dimension: Float, onChange: (Float, Float) -> Unit) {
    if (dimension <= 0f) return
    val radius = dimension / 2f
    val center = Offset(radius, radius)
    val dx = offset.x - center.x
    val dy = offset.y - center.y
    val distance = hypot(dx.toDouble(), dy.toDouble()).coerceIn(0.0, radius.toDouble())
    val angleDegrees = atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI
    val hue = ((angleDegrees + 360.0) % 360.0).toFloat()
    val saturation = (distance / radius).toFloat().coerceIn(0f, 1f)
    onChange(hue, saturation)
}

@Composable
private fun SliderGroup(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val displayValue = if (valueRange.endInclusive <= 1f) (value * 100f).toInt() else value.toInt()
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = displayValue.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

private fun Color.toHexString(): String {
    fun channel(value: Float): String {
        val intValue = (value * 255f).roundToInt().coerceIn(0, 255)
        return intValue.toString(16).padStart(2, '0').uppercase()
    }
    return "#${channel(red)}${channel(green)}${channel(blue)}"
}

private fun Color.toHsv(): FloatArray {
    val r = red
    val g = green
    val b = blue
    val maxChannel = max(r, max(g, b))
    val minChannel = min(r, min(g, b))
    val delta = maxChannel - minChannel

    val hueSector = when {
        delta == 0f -> 0f
        maxChannel == r -> ((g - b) / delta).let { value -> if (value < 0f) value + 6f else value }
        maxChannel == g -> ((b - r) / delta) + 2f
        else -> ((r - g) / delta) + 4f
    }
    val rawHue = hueSector * 60f

    val saturation = if (maxChannel == 0f) 0f else delta / maxChannel
    val normalizedHue = ((rawHue.toDouble() + 360.0) % 360.0).toFloat()

    return floatArrayOf(normalizedHue, saturation.coerceIn(0f, 1f), maxChannel.coerceIn(0f, 1f))
}

private fun hsvToColor(hue: Float, saturation: Float, value: Float): Color {
    val h = (hue % 360f + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    if (s == 0f) return Color(v, v, v)

    val c = v * s
    val x = c * (1 - abs((h / 60f) % 2 - 1))
    val m = v - c

    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    return Color(r1 + m, g1 + m, b1 + m)
}
