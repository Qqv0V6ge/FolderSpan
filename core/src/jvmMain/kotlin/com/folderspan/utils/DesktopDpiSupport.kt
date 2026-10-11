package com.folderspan.utils

import strings.AppStrings

import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlin.math.abs
import kotlin.math.roundToInt

object DesktopDpiSupport {
    private const val BASELINE_DPI = 96f
    private const val MIN_REASONABLE_DPI = 72
    private const val MAX_REASONABLE_DPI = 768
    private const val MIN_REASONABLE_SCALE = 0.75f
    private const val MAX_REASONABLE_SCALE = 4f
    private const val SCALE_EPSILON = 0.05f
    private const val UI_SCALE_PROPERTY = "sun.java2d.uiScale"

    fun applyDesktopUiScaleProperty(
        uiScalePropertyValue: String? = System.getProperty(UI_SCALE_PROPERTY),
        systemScale: Float? = readSystemScale()
    ) {
        if (!uiScalePropertyValue.isNullOrBlank()) {
            LogKit.i(AppStrings.ui_desktop_ui_scaling_use_existing_jvm_configuration_uiscale_arg0.format(arg0 = (uiScalePropertyValue)))
            return
        }

        LogKit.i(AppStrings.ui_desktop_scale_automatic_detection_scale_arg0.format(arg0 = (systemScale?.let(::formatScale) ?: "unknown")))

        val targetScale = systemScale ?: return
        if (!shouldOverrideScale(currentScale = 1f, targetScale = targetScale)) {
            LogKit.i(AppStrings.ui_desktop_ui_scaling_system_scaling_arg0_no_need_override.format(arg0 = (formatScale(targetScale))))
            return
        }

        val scaleText = formatScale(targetScale)
        System.setProperty(UI_SCALE_PROPERTY, scaleText)
        LogKit.i(AppStrings.ui_desktop_ui_scaling_auto_set_arg0.format(arg0 = (scaleText)))
    }

    fun readSystemScale(): Float? {
        val osName = System.getProperty("os.name").orEmpty()
        val linuxDetection = LinuxDesktopDpiSupport.detectSystemScale(osName = osName)
        val windowsDetection = WindowsDesktopDpiSupport.detectSystemScale(osName = osName)
        val macDetection = MacDesktopDpiSupport.detectSystemScale(osName = osName)
        val transformScale = readGraphicsTransformScale()
        val screenDpi = readScreenDpi()
        val dpiScale = screenDpi?.let(::scaleFromDpi)
        val platformScale = linuxDetection?.selectedScale
            ?: windowsDetection?.selectedScale
            ?: macDetection?.selectedScale
        val systemScale = platformScale ?: selectSystemScale(
            transformScale = transformScale,
            dpiScale = dpiScale
        )

        val platformFields = linuxDetection?.asLogFields()
            ?: windowsDetection?.asLogFields()
            ?: macDetection?.asLogFields()
            ?: "platform=unsupported"
        LogKit.i(
            AppStrings.ui_desktop_scaling_detection_os_arg0_arg1.format(arg0 = (osName), arg1 = (platformFields)) +
                "transform=${transformScale?.let(::formatScale) ?: "unknown"}, " +
                "dpi=${screenDpi ?: "unknown"}, dpiScale=${dpiScale?.let(::formatScale) ?: "unknown"}, " +
                "selected=${systemScale?.let(::formatScale) ?: "unknown"}"
        )
        return systemScale
    }

    fun readScreenDpi(): Int? {
        return runCatching { Toolkit.getDefaultToolkit().screenResolution }
            .getOrNull()
            ?.takeIf { item -> item in MIN_REASONABLE_DPI..MAX_REASONABLE_DPI }
    }

    fun readWindowScale(
        graphicsConfiguration: GraphicsConfiguration?,
        fallbackSystemScale: Float?
    ): Float? {
        val transformScale = graphicsConfiguration?.let(::readGraphicsTransformScale)
        return selectSystemScale(
            transformScale = transformScale,
            dpiScale = fallbackSystemScale
        )
    }

    fun resolveDensityOverride(
        currentDensity: Float,
        systemScale: Float? = readSystemScale(),
        osName: String = System.getProperty("os.name").orEmpty()
    ): Float? {
        LogKit.i(
            AppStrings.ui_compose_scaling_detection_os_arg0_current_arg1_target_arg2.format(arg0 = (osName), arg1 = (formatScale(currentDensity)), arg2 = (systemScale?.let(::formatScale) ?: "unknown"))
        )

        val targetScale = systemScale ?: return null
        if (!shouldSynchronizeDensity(currentDensity = currentDensity, targetScale = targetScale)) {
            LogKit.i(AppStrings.ui_compose_scaling_detection_currently_no_need_override_density)
            return null
        }
        return targetScale
    }

    internal fun selectSystemScale(transformScale: Float?, dpiScale: Float?): Float? {
        return transformScale
            ?.takeIf { scale -> shouldOverrideScale(currentScale = 1f, targetScale = scale) }
            ?: dpiScale
            ?: transformScale
    }

    internal fun readGraphicsTransformScale(): Float? {
        return runCatching {
            val configuration = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .defaultConfiguration
            readGraphicsTransformScale(configuration)
        }.getOrNull()
    }

    private fun readGraphicsTransformScale(configuration: GraphicsConfiguration): Float? {
        val scaleX = configuration.defaultTransform.scaleX.toFloat()
        val scaleY = configuration.defaultTransform.scaleY.toFloat()
        return sanitizeScale(maxOf(scaleX, scaleY))
    }

    internal fun scaleFromDpi(screenDpi: Int): Float? {
        return scaleFromRawDpi(screenDpi.toFloat())
    }

    internal fun scaleFromRawDpi(screenDpi: Float): Float? {
        if (!screenDpi.isFinite()) return null
        if (screenDpi !in 72.0..768.0) return null
        return sanitizeScale(screenDpi / BASELINE_DPI)
    }

    internal fun sanitizeScale(scale: Float): Float? {
        if (!scale.isFinite()) return null
        val normalized = normalizeScale(scale)
        return normalized.takeIf { item -> item in MIN_REASONABLE_SCALE..MAX_REASONABLE_SCALE }
    }

    internal fun shouldOverrideScale(currentScale: Float, targetScale: Float): Boolean {
        return !(!currentScale.isFinite() || !targetScale.isFinite()) && abs(targetScale - 1f) > SCALE_EPSILON && abs(targetScale - currentScale) > SCALE_EPSILON
    }

    internal fun shouldSynchronizeDensity(currentDensity: Float, targetScale: Float): Boolean {
        return currentDensity.isFinite() &&
            targetScale.isFinite() &&
            abs(targetScale - currentDensity) > SCALE_EPSILON
    }

    fun resolveWindowResizeRatio(currentScale: Float, targetScale: Float): Float? {
        val normalizedCurrentScale = sanitizeScale(currentScale) ?: return null
        val normalizedTargetScale = sanitizeScale(targetScale) ?: return null
        if (abs(normalizedTargetScale - normalizedCurrentScale) <= SCALE_EPSILON) return null
        return normalizedTargetScale / normalizedCurrentScale
    }

    internal fun formatScale(scale: Float): String {
        val normalized = normalizeScale(scale)
        val rounded = normalized.roundToInt().toFloat()
        return if (abs(normalized - rounded) <= 0.001f) {
            rounded.roundToInt().toString()
        } else {
            normalized.toString()
        }
    }

    internal fun isLinuxOs(osName: String = System.getProperty("os.name").orEmpty()): Boolean {
        return osName.contains("linux", ignoreCase = true)
    }

    internal fun isWindowsOs(osName: String = System.getProperty("os.name").orEmpty()): Boolean {
        return osName.contains("windows", ignoreCase = true)
    }

    internal fun isMacOs(osName: String = System.getProperty("os.name").orEmpty()): Boolean {
        return osName.contains("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true)
    }

    private fun normalizeScale(scale: Float): Float {
        return (scale * 100f).roundToInt() / 100f
    }
}
