package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.service.mcp.file.FileEndpointRef
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CopyDestinationDiscoveryJvmTest {
    @Test
    fun concurrentTargetsEachPublishTheirCompletion() = runBlocking {
        Executors.newFixedThreadPool(4).asCoroutineDispatcher().use { dispatcher ->
            val discovery = CopyDestinationDiscovery("runtime", CoroutineScope(coroutineContext + dispatcher))
            try {
                val barrier = CyclicBarrier(4)
                val sources = List(4) { index ->
                    val id = "device-$index"
                    CopyDestinationSource(CopyEndpointIdentity("runtime", FileEndpointRef(FileProtocol.Device, id), id, "/"), id) {
                        barrier.await(5, TimeUnit.SECONDS)
                        emptyList()
                    }
                }
                repeat(100) {
                    discovery.refresh(sources)
                    val loaded = withTimeoutOrNull(5_000) { discovery.loads.first { loads -> loads.none { it.loading } } }
                    assertNotNull(loaded, "A concurrent update was lost: ${discovery.loads.value}")
                    assertTrue(loaded.all { it.error == null }, loaded.toString())
                }
            } finally {
                discovery.close()
            }
        }
    }
}
