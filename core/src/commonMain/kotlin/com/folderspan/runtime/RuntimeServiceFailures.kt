package com.folderspan.runtime

import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Public diagnostics contain service codes, never exception text or credentials. */
object RuntimeServiceFailures {
    private val failures = MutableStateFlow<Map<String, String>>(emptyMap())
    val state = failures.asStateFlow()

    fun clear() { failures.value = emptyMap() }

    fun report(service: String, error: Throwable) {
        failures.update { it + (service to "service_failed") }
        LogKit.e("Runtime service failed: $service", error)
    }

    suspend fun observe(service: String, block: suspend CoroutineScope.() -> Unit) {
        try {
            coroutineScope(block)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            report(service, error)
        }
    }
}
