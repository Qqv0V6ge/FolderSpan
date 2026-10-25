package com.folderspan.service.session

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import com.folderspan.data.file.*
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.db.*
import com.folderspan.routes.RawHttpApiDispatcher
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.ui.state.file.FileShareState
import com.folderspan.ui.state.main.*
import com.folderspan.utils.DataEncryptionKey
import com.folderspan.utils.DatabaseReady
import com.folderspan.utils.SettingsUtils
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
internal class ShareSaveFixture(private val driver: SqlDriver = createShareSaveTestDriver()) : AutoCloseable {
    val directory = Files.createTempDirectory("webrtc-share-save").toRealPath()
    val bytes = ByteArray(1024 * 1024) { (it * 31 + 17).toByte() }
    val input = Files.write(directory.resolve("payload.bin"), bytes)
    val outside = Files.write(directory.resolve("private.bin"), byteArrayOf(9))
    val output = directory.resolve("saved.bin")
    private lateinit var database: FolderSpanDatabase
    val tasks = TaskState(TestTaskFailureResultStore(), TestTaskRuntimePersistenceStore())
    lateinit var certificates: DeviceCertificateState
    lateinit var devices: DeviceState
    val dispatcher = RawHttpApiDispatcher(MapSettings())
    // Restore every settings holder, including a previously uninitialized holder.
    private val settingsSnapshot = listOf(
        SettingsUtils, SettingsUtils.fileShare, SettingsUtils.mcp,
        SettingsUtils.easyFileShare, DataEncryptionKey,
    ).map { holder ->
        val field = holder.javaClass.getDeclaredField("settings").apply { isAccessible = true }
        field to field.get(null)
    }
    private var mainInstalled = false
    private var koinStarted = false
    fun allowOrdinaryRead() {
        database.devicePermissionQueries.insertWithId(71L, directory.toString(), true, true, true, true, true, 0L, "WebRTC interop test")
        database.deviceRoleDevicePermissionQueries.insert(2L, 71L)
    }
    fun start() {
        SettingsUtils.init(MapSettings())
        DatabaseReady.markReady()
        database = shareSaveDatabase(driver)
        database.deviceRoleQueries.insertWithId(2L, "Share test", "Share test", 0L)
        certificates = DeviceCertificateState(database)
        Dispatchers.setMain(StandardTestDispatcher())
        mainInstalled = true
        val koin = startKoin { modules(module {
            single { database }
            single { certificates }
            single { tasks }
            single { FileShareState() }
            single { DeviceState() }
        }) }.koin
        koinStarted = true
        devices = koin.get()
        // Prevent discovery/UI background jobs from touching the user's app. Business methods remain real.
        (devices.javaClass.getDeclaredField("mainScope").apply { isAccessible = true }.get(devices) as CoroutineScope).cancel()
        Dispatchers.setMain(Dispatchers.Unconfined)
    }
    override fun close() {
        var failure: Throwable? = null
        fun cleanup(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        cleanup {
            val scheduler = tasks.javaClass.getDeclaredField("taskScheduler").apply { isAccessible = true }.get(tasks)
            val taskScope = scheduler.javaClass.getDeclaredField("taskScope").apply { isAccessible = true }.get(scheduler) as CoroutineScope
            runBlocking { taskScope.coroutineContext.job.cancelAndJoin() }
        }
        if (::devices.isInitialized) {
            cleanup { (devices.javaClass.getDeclaredField("mainScope").apply { isAccessible = true }.get(devices) as CoroutineScope).cancel() }
            cleanup { (devices.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(devices) as HttpClient).close() }
        }
        if (koinStarted) cleanup { stopKoin() }
        if (mainInstalled) cleanup { Dispatchers.resetMain() }
        cleanup { driver.close() }
        cleanup { directory.toFile().deleteRecursively() }
        settingsSnapshot.forEach { (field, previous) -> cleanup { field.set(null, previous) } }
        failure?.let { throw it }
    }
}

internal fun shareSaveDatabase(driver: SqlDriver) = FolderSpanDatabase(
    driver = driver,
    DeviceAdapter = Device.Adapter(shareSaveEnumAdapter<DeviceType>()),
    DeviceConnectAdapter = DeviceConnect.Adapter(shareSaveEnumAdapter<DeviceConnectType>(), shareSaveEnumAdapter<DeviceCategory>()),
    FileBookmarkAdapter = FileBookmark.Adapter(shareSaveEnumAdapter<DrawerBookmarkType>(), shareSaveEnumAdapter<FileProtocol>()),
    FileFavoriteAdapter = FileFavorite.Adapter(shareSaveEnumAdapter<FileProtocol>()),
    FileRecentAdapter = FileRecent.Adapter(shareSaveEnumAdapter<FileProtocol>()),
    FileFilterAdapter = FileFilter.Adapter(shareSaveEnumAdapter<FileFilterType>(), shareSaveStringsAdapter),
    DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(shareSaveEnumAdapter<DeviceConnectType>()),
    FilePathPreferenceAdapter = FilePathPreference.Adapter(
        shareSaveEnumAdapter<FileProtocol>(), shareSaveEnumAdapter<FileFilterSort>(), shareSaveStringsAdapter,
    ),
)

private inline fun <reified T : Enum<T>> shareSaveEnumAdapter(): ColumnAdapter<T, String> =
    object : ColumnAdapter<T, String> {
        override fun decode(databaseValue: String): T = enumValueOf(databaseValue)
        override fun encode(value: T): String = value.name
    }

private val shareSaveStringsAdapter = object : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String): List<String> = databaseValue.split(',').filter(String::isNotEmpty)
    override fun encode(value: List<String>): String = value.joinToString(",")
}
