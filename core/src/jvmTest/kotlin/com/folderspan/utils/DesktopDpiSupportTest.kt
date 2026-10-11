package com.folderspan.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDpiSupportTest {
    @Test
    fun scaleFromDpiConvertsCommonDesktopScales() {
        assertEquals(1f, DesktopDpiSupport.scaleFromDpi(96))
        assertEquals(1.25f, DesktopDpiSupport.scaleFromDpi(120))
        assertEquals(1.5f, DesktopDpiSupport.scaleFromDpi(144))
        assertEquals(2f, DesktopDpiSupport.scaleFromDpi(192))
    }

    @Test
    fun scaleFromRawDpiHandlesFloatInput() {
        assertEquals(1.5f, DesktopDpiSupport.scaleFromRawDpi(144f))
        assertNull(DesktopDpiSupport.scaleFromRawDpi(Float.NaN))
    }

    @Test
    fun scaleFromDpiRejectsOutOfRangeValues() {
        assertNull(DesktopDpiSupport.scaleFromDpi(0))
        assertNull(DesktopDpiSupport.scaleFromDpi(48))
        assertNull(DesktopDpiSupport.scaleFromDpi(1200))
    }

    @Test
    fun selectSystemScalePrefersMeaningfulTransformThenDpi() {
        assertEquals(1.5f, DesktopDpiSupport.selectSystemScale(transformScale = 1.5f, dpiScale = 1.25f))
        assertEquals(1.5f, DesktopDpiSupport.selectSystemScale(transformScale = 1f, dpiScale = 1.5f))
        assertEquals(1f, DesktopDpiSupport.selectSystemScale(transformScale = 1f, dpiScale = null))
        assertNull(DesktopDpiSupport.selectSystemScale(transformScale = null, dpiScale = null))
    }

    @Test
    fun applyDesktopUiScalePropertyUsesAutomaticScale() {
        val key = "sun.java2d.uiScale"
        val previous = System.getProperty(key)

        try {
            System.clearProperty(key)
            DesktopDpiSupport.applyDesktopUiScaleProperty(
                uiScalePropertyValue = null,
                systemScale = 1.5f
            )
            assertEquals("1.5", System.getProperty(key))
        } finally {
            if (previous.isNullOrBlank()) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }

    @Test
    fun applyDesktopUiScalePropertyKeepsExistingScaleSetting() {
        val key = "sun.java2d.uiScale"
        val previous = System.getProperty(key)

        try {
            System.setProperty(key, "1.25")
            DesktopDpiSupport.applyDesktopUiScaleProperty(
                uiScalePropertyValue = "1.25",
                systemScale = 1.5f
            )
            assertEquals("1.25", System.getProperty(key))
        } finally {
            if (previous.isNullOrBlank()) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }

    @Test
    fun resolveDensityOverrideUsesAutomaticScaleWhenDiffers() {
        assertEquals(
            1.5f,
            DesktopDpiSupport.resolveDensityOverride(
                currentDensity = 1f,
                systemScale = 1.5f
            )
        )
        assertNull(
            DesktopDpiSupport.resolveDensityOverride(
                currentDensity = 1.5f,
                systemScale = 1.5f
            )
        )
        assertEquals(
            1f,
            DesktopDpiSupport.resolveDensityOverride(
                currentDensity = 1.5f,
                systemScale = 1f
            )
        )
    }

    @Test
    fun shouldSynchronizeDensitySupportsScalingUpAndDown() {
        assertFalse(DesktopDpiSupport.shouldSynchronizeDensity(currentDensity = 1f, targetScale = 1f))
        assertFalse(DesktopDpiSupport.shouldSynchronizeDensity(currentDensity = 1.48f, targetScale = 1.5f))
        assertTrue(DesktopDpiSupport.shouldSynchronizeDensity(currentDensity = 1f, targetScale = 1.5f))
        assertTrue(DesktopDpiSupport.shouldSynchronizeDensity(currentDensity = 1.5f, targetScale = 1f))
    }

    @Test
    fun resolveWindowResizeRatioPreservesLogicalWindowSize() {
        assertEquals(1.3f, DesktopDpiSupport.resolveWindowResizeRatio(currentScale = 1f, targetScale = 1.3f))
        assertEquals(
            1f / 1.3f,
            DesktopDpiSupport.resolveWindowResizeRatio(currentScale = 1.3f, targetScale = 1f)
        )
        assertNull(DesktopDpiSupport.resolveWindowResizeRatio(currentScale = 1.3f, targetScale = 1.3f))
        assertNull(DesktopDpiSupport.resolveWindowResizeRatio(currentScale = Float.NaN, targetScale = 1.3f))
    }

    @Test
    fun shouldOverrideScaleIgnoresIdentityAndTinyDiffs() {
        assertFalse(DesktopDpiSupport.shouldOverrideScale(currentScale = 1f, targetScale = 1f))
        assertFalse(DesktopDpiSupport.shouldOverrideScale(currentScale = 1f, targetScale = 1.03f))
        assertFalse(DesktopDpiSupport.shouldOverrideScale(currentScale = 1.48f, targetScale = 1.5f))
        assertTrue(DesktopDpiSupport.shouldOverrideScale(currentScale = 1f, targetScale = 1.5f))
    }

    @Test
    fun osDetectorsMatchDesktopPlatforms() {
        assertTrue(DesktopDpiSupport.isLinuxOs("Linux"))
        assertTrue(DesktopDpiSupport.isWindowsOs("Windows 11"))
        assertTrue(DesktopDpiSupport.isMacOs("Mac OS X"))
        assertFalse(DesktopDpiSupport.isLinuxOs("Windows 11"))
        assertFalse(DesktopDpiSupport.isWindowsOs("Linux"))
        assertFalse(DesktopDpiSupport.isMacOs("Linux"))
    }
}
