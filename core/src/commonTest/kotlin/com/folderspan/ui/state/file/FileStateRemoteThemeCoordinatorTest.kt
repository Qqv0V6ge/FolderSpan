package com.folderspan.ui.state.file

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.ui.state.main.MainState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class FileStateRemoteThemeCoordinatorTest {
    @Test
    fun appearanceChangeFetchesMatchingRemoteColorsWithoutReloadingTheDesk() = runTest {
        val mainState = MainState()
        val device = device("first")
        val desk = MutableStateFlow<DiskBase>(device)
        val darkResponse = CompletableDeferred<Unit>()
        val modes = mutableListOf<Boolean>()
        FileStateRemoteThemeCoordinator(backgroundScope, desk, mainState, { _, isDark ->
            modes += isDark
            if (isDark) {
                darkResponse.await()
                DARK_COLORS
            } else {
                LIGHT_COLORS
            }
        })
        runCurrent()
        assertSame(LIGHT_COLORS, mainState.remoteColorScheme.value)

        mainState.setCurrentDarkTheme(true)
        assertNull(mainState.remoteColorScheme.value)
        assertNull(mainState.remoteColorSchemeFor(true))
        runCurrent()
        assertEquals(listOf(false, true), modes)
        assertNull(mainState.remoteColorScheme.value)

        darkResponse.complete(Unit)
        runCurrent()
        assertSame(DARK_COLORS, mainState.remoteColorScheme.value)
        assertSame(device, desk.value)
    }

    @Test
    fun oldDeviceIgnoringCancellationDoesNotDelayOrOverwriteNewDevice() = runTest {
        val mainState = MainState()
        val first = device("first")
        val second = device("second")
        val desk = MutableStateFlow<DiskBase>(first)
        val oldResponse = CompletableDeferred<Unit>()
        val requests = mutableListOf<String>()
        val coordinator = FileStateRemoteThemeCoordinator(backgroundScope, desk, mainState, { device, _ ->
            requests += device.id
            if (device === first) {
                withContext(NonCancellable) { oldResponse.await() }
                LIGHT_COLORS
            } else {
                SECOND_COLORS
            }
        })
        runCurrent()

        desk.value = second
        coordinator.onDeskChanged()
        runCurrent()
        assertEquals(listOf("first", "second"), requests)
        assertSame(SECOND_COLORS, mainState.remoteColorScheme.value)

        oldResponse.complete(Unit)
        runCurrent()
        assertSame(SECOND_COLORS, mainState.remoteColorScheme.value)
    }

    @Test
    fun oldAppearanceIgnoringCancellationCannotOverwriteNewAppearance() = runTest {
        val mainState = MainState()
        val desk = MutableStateFlow<DiskBase>(device("first"))
        val oldResponse = CompletableDeferred<Unit>()
        FileStateRemoteThemeCoordinator(backgroundScope, desk, mainState, { _, isDark ->
            if (isDark) {
                DARK_COLORS
            } else {
                withContext(NonCancellable) { oldResponse.await() }
                LIGHT_COLORS
            }
        })
        runCurrent()

        mainState.setCurrentDarkTheme(true)
        runCurrent()
        assertSame(DARK_COLORS, mainState.remoteColorScheme.value)

        oldResponse.complete(Unit)
        runCurrent()
        assertSame(DARK_COLORS, mainState.remoteColorScheme.value)
    }

    @Test
    fun returningLocalClearsRemoteColorsBeforeTheObserverRuns() = runTest {
        val mainState = MainState()
        val desk = MutableStateFlow<DiskBase>(device("first"))
        var requests = 0
        val coordinator = FileStateRemoteThemeCoordinator(backgroundScope, desk, mainState, { _, _ ->
            requests++
            LIGHT_COLORS
        })
        runCurrent()
        assertSame(LIGHT_COLORS, mainState.remoteColorScheme.value)

        desk.value = Local(name = "local", pathSeparator = "/")
        coordinator.onDeskChanged()
        assertNull(mainState.remoteColorScheme.value)
        assertNull(mainState.remoteColorSchemeFor(false))
        runCurrent()
        assertNull(mainState.remoteColorScheme.value)
        assertEquals(1, requests)
    }

    @Test
    fun rapidDeviceRoundTripStillReloadsTheTheme() = runTest {
        val mainState = MainState()
        val first = device("first")
        val desk = MutableStateFlow<DiskBase>(first)
        var requests = 0
        val coordinator = FileStateRemoteThemeCoordinator(backgroundScope, desk, mainState, { _, _ ->
            requests++
            if (requests == 1) LIGHT_COLORS else SECOND_COLORS
        })
        runCurrent()
        assertSame(LIGHT_COLORS, mainState.remoteColorScheme.value)

        desk.value = device("second")
        coordinator.onDeskChanged()
        desk.value = first
        coordinator.onDeskChanged()
        assertNull(mainState.remoteColorScheme.value)
        runCurrent()

        assertEquals(2, requests)
        assertSame(SECOND_COLORS, mainState.remoteColorScheme.value)
    }

    @Test
    fun failedThemeRequestFallsBackAndTheNextAppearanceCanRecover() = runTest {
        val mainState = MainState()
        val desk = MutableStateFlow<DiskBase>(device("first"))
        val failures = mutableListOf<Exception>()
        val failure = IllegalStateException("theme unavailable")
        FileStateRemoteThemeCoordinator(
            backgroundScope,
            desk,
            mainState,
            loadColorScheme = { _, isDark -> if (isDark) DARK_COLORS else throw failure },
            onFailure = { failures += it },
        )
        runCurrent()
        assertNull(mainState.remoteColorScheme.value)
        assertEquals(listOf<Exception>(failure), failures)

        mainState.setCurrentDarkTheme(true)
        runCurrent()
        assertSame(DARK_COLORS, mainState.remoteColorScheme.value)
    }

    private fun device(id: String) = Device(
        id = id,
        name = id,
        pathSeparator = "/",
        host = mutableMapOf(),
        type = DeviceType.JVM,
        token = "",
    )

    private companion object {
        val LIGHT_COLORS = lightColorScheme(primary = Color.Red)
        val DARK_COLORS = darkColorScheme(primary = Color.Blue)
        val SECOND_COLORS = lightColorScheme(primary = Color.Green)
    }
}
