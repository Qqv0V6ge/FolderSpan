package com.folderspan.ui.state.main

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.service.mcp.http.createMcpHttpTestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncEventTriggerIntegrationJvmTest {
    @Test fun headlessDeviceChangesUseTheExistingSchedulerAndRetainFirstPreviewAndOwnershipGuards() = runBlocking {
        val root = Files.createTempDirectory("sync-event-entry")
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        var state: SyncState? = null
        var deviceState: DeviceState? = null
        var taskState: TaskState? = null
        try {
            val database = createMcpHttpTestDatabase(driver)
            val local = SyncLocalStore(database, driver)
            val runtimeTasks = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore(),
                TempTaskCleanupStore(root.resolve("cleanup").toString()))
            taskState = runtimeTasks
            val store = TempSyncTaskStore(root.toString())
            val owned = store.saveTask(SyncTask(id = 1, name = "Event preview", sourceType = SyncEndpointType.Device,
                sourceRef = "remote", sourcePath = "/source", targetType = SyncEndpointType.Local, targetPath = "/target",
                ownerDeviceId = "local", mode = SyncMode.Mirror, triggerOnDeviceConnect = true, notifyLevel = SyncNotifyLevel.Off))
            val foreign = store.saveTask(owned.copy(id = 2, ownerDeviceId = "other"))
            stopKoin()
            val application = startKoin { modules(module {
                single { database }; single { local }; single { runtimeTasks }; single { MainState() }; single { DeviceState() }
            }) }
            val devices = application.koin.get<DeviceState>()
            deviceState = devices
            val sync = SyncState(store) { SyncDeviceIdentity("local", "Local") }
            state = sync
            sync.start()
            assertFalse(local.localState(owned.id)?.confirmationNotified == true)
            withContext(Dispatchers.Main) {
                devices.devices.add(Device("remote", "Remote", "/", mutableMapOf(), DeviceType.JVM, ""))
            }
            // No recomposer or global snapshot notification is required by the scheduler's device observation.
            withTimeout(4_000) {
                while (local.localState(owned.id)?.confirmationNotified != true) delay(10)
            }
            assertFalse(local.localState(foreign.id)?.confirmationNotified == true)
            assertTrue(store.loadRuns(owned.id).isEmpty())
            assertTrue(store.loadRuns(foreign.id).isEmpty())
            assertFalse(sync.hasActiveSyncRun(owned.id))
        } finally {
            state?.stop()
            deviceState?.stop()
            taskState?.stop()
            stopKoin()
            driver.close()
            root.toFile().deleteRecursively()
        }
    }
}
