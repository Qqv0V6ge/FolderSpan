package com.folderspan.ui.state.file

import androidx.compose.material3.ColorScheme
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.ui.state.main.MainState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Keeps remote colors independent from directory requests and rejects obsolete responses. */
internal class FileStateRemoteThemeCoordinator(
    scope: CoroutineScope,
    private val deskType: StateFlow<DiskBase>,
    private val mainState: MainState,
    private val loadColorScheme: suspend (Device, Boolean) -> ColorScheme?,
    private val onFailure: (Exception) -> Unit = {},
) {
    private val deskChanges = MutableStateFlow(Any())

    init {
        scope.launch {
            var requestJob: Job? = null
            try {
                combine(deskType, mainState.currentDarkTheme, deskChanges) { desk, isDark, version ->
                    ThemeRequest(desk, isDark, version)
                }.collect { request ->
                    if (!request.isCurrent()) return@collect
                    requestJob?.cancel()
                    mainState.setRemoteColorScheme(null)
                    val device = request.desk as? Device ?: return@collect
                    // Do not join a cancelled transport: an uncooperative old request must not
                    // delay the theme for the newly selected device or appearance.
                    requestJob = scope.launch {
                        val colorScheme = try {
                            loadColorScheme(device, request.isDark)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            currentCoroutineContext().ensureActive()
                            if (request.isCurrent()) onFailure(error)
                            null
                        }
                        currentCoroutineContext().ensureActive()
                        if (request.isCurrent()) {
                            mainState.setRemoteColorScheme(colorScheme, request.isDark)
                        }
                    }
                }
            } finally {
                requestJob?.cancel()
            }
        }
    }

    /** Also invalidates an A → B → A switch that StateFlow may otherwise conflate. */
    fun onDeskChanged() {
        deskChanges.value = Any()
        mainState.setRemoteColorScheme(null)
    }

    private fun ThemeRequest.isCurrent(): Boolean =
        deskType.value === desk &&
            mainState.currentDarkTheme.value == isDark &&
            deskChanges.value === version

    private data class ThemeRequest(val desk: DiskBase, val isDark: Boolean, val version: Any)
}
