package com.folderspan.service.message

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.device.DeviceCategory
import com.folderspan.data.main.device.DeviceConnectType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.db.Device
import com.folderspan.db.DeviceConnect
import com.folderspan.db.DeviceReceiveShare
import com.folderspan.db.FileBookmark
import com.folderspan.db.FileFavorite
import com.folderspan.db.FileFilter
import com.folderspan.db.FilePathPreference
import com.folderspan.db.FileRecent
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.DatabaseReady
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightDeviceMessageStoreTest {
    @Test
    fun freshSchemaPersistsHistoryAcrossRepositoryRestart(): Unit = runBlocking {
        val testDatabase = createInMemoryDatabase()
        val firstStore = SqlDelightDeviceMessageStore(testDatabase.database)
        val outgoing = message("outgoing-message-0001", "hello", 100L)
        val incoming = message("incoming-message-0001", "world", 200L)

        firstStore.insertOutgoing(PEER, outgoing, createdAtMillis = 101L)
        firstStore.updateOutgoingStatus(
            peerDeviceId = PEER,
            messageId = outgoing.metadata.messageId,
            status = DeviceMessageStatus.Delivered,
            receivedAtMillis = 150L,
        )
        firstStore.persistIncoming(PEER, incoming, receivedAtMillis = 250L, isRead = false)

        val restartedStore = SqlDelightDeviceMessageStore(testDatabase.database)
        val history = restartedStore.page(PEER, limit = 20)

        assertEquals(listOf("world", "hello"), history.map(DeviceStoredMessage::body))
        assertEquals(DeviceMessageStatus.Delivered, history.last().status)
        assertEquals(DeviceMessageStatus.Received, history.first().status)
        assertEquals(1L, restartedStore.conversation(PEER)?.unreadCount)
    }

    @Test
    fun historyIsAggregatedByStablePeerRegardlessOfTransport(): Unit = runBlocking {
        val store = SqlDelightDeviceMessageStore(createInMemoryDatabase().database)

        store.insertOutgoing(PEER, message("session-message-0001", "session", 100L), 100L)
        store.insertOutgoing(PEER, message("webrtc-message-0001", "webrtc", 200L), 200L)

        assertEquals(listOf("webrtc", "session"), store.page(PEER, 20).map(DeviceStoredMessage::body))
        assertEquals(1, store.conversations().size)
    }

    @Test
    fun incomingRetryIsIdempotentAndDoesNotDoubleUnreadCount(): Unit = runBlocking {
        val store = SqlDelightDeviceMessageStore(createInMemoryDatabase().database)
        val incoming = message("incoming-retry-00001", "once", 100L)

        val first = store.persistIncoming(PEER, incoming, receivedAtMillis = 150L, isRead = false)
        val duplicate = store.persistIncoming(PEER, incoming, receivedAtMillis = 300L, isRead = false)

        assertFalse(first.duplicate)
        assertTrue(duplicate.duplicate)
        assertEquals(first.message.localId, duplicate.message.localId)
        assertEquals(1L, store.count(PEER))
        assertEquals(1L, store.conversation(PEER)?.unreadCount)
    }

    @Test
    fun duplicateIdWithDifferentPayloadIsRejected(): Unit = runBlocking {
        val store = SqlDelightDeviceMessageStore(createInMemoryDatabase().database)
        val first = message("incoming-conflict-001", "first", 100L)
        val conflict = message("incoming-conflict-001", "second", 100L)

        store.persistIncoming(PEER, first, receivedAtMillis = 150L, isRead = false)

        assertFailsWith<DeviceMessagePersistenceException> {
            store.persistIncoming(PEER, conflict, receivedAtMillis = 200L, isRead = false)
        }
        assertEquals(1L, store.count(PEER))
    }

    @Test
    fun markReadAndLocalDeleteUpdateDurableConversationState(): Unit = runBlocking {
        val store = SqlDelightDeviceMessageStore(createInMemoryDatabase().database)
        store.persistIncoming(
            PEER,
            message("incoming-unread-0001", "unread", 100L),
            receivedAtMillis = 120L,
            isRead = false,
        )

        store.markConversationRead(PEER)

        assertEquals(0L, store.conversation(PEER)?.unreadCount)
        assertTrue(store.page(PEER, 20).single().isRead)

        store.deleteConversation(PEER)

        assertEquals(0L, store.count(PEER))
        assertNull(store.conversation(PEER))
    }

    @Test
    fun startupRecoveryChangesSendingToUnconfirmedWithoutResend(): Unit = runBlocking {
        val store = SqlDelightDeviceMessageStore(createInMemoryDatabase().database)
        val outgoing = message("interrupted-send-0001", "pending", 100L)
        store.insertOutgoing(PEER, outgoing, createdAtMillis = 100L)

        assertEquals(1L, store.recoverInterruptedSends())

        val recovered = assertNotNull(store.find(PEER, outgoing.metadata.messageId, DeviceMessageDirection.Outgoing))
        assertEquals(DeviceMessageStatus.Unconfirmed, recovered.status)
        assertEquals(1L, store.count(PEER))
    }

    @Test
    fun explicitRetryReusesStableIdOnlyForFailedOrUnconfirmedMessages(): Unit = runBlocking {
        val store = SqlDelightDeviceMessageStore(createInMemoryDatabase().database)
        val outgoing = message("explicit-retry-00001", "retry", 100L)
        store.insertOutgoing(PEER, outgoing, createdAtMillis = 100L)
        store.updateOutgoingStatus(PEER, outgoing.metadata.messageId, DeviceMessageStatus.Failed)

        val retry = store.prepareExplicitRetry(PEER, outgoing.metadata.messageId)

        assertEquals(outgoing.metadata.messageId, retry.messageId)
        assertEquals(DeviceMessageStatus.Sending, retry.status)
        assertEquals(1L, store.count(PEER))
        assertFailsWith<DeviceMessagePersistenceException> {
            store.prepareExplicitRetry(PEER, outgoing.metadata.messageId)
        }
    }

    @Test
    fun pagesUseTimestampAndLocalIdAsStableOrder(): Unit = runBlocking {
        val store = SqlDelightDeviceMessageStore(createInMemoryDatabase().database)
        store.insertOutgoing(PEER, message("paging-message-00001", "one", 100L), 100L)
        store.insertOutgoing(PEER, message("paging-message-00002", "two", 100L), 101L)
        store.insertOutgoing(PEER, message("paging-message-00003", "three", 200L), 200L)

        val firstPage = store.page(PEER, limit = 2)
        val secondPage = store.pageBefore(
            peerDeviceId = PEER,
            beforeSentAtMillis = firstPage.last().sentAtMillis,
            beforeLocalId = firstPage.last().localId,
            limit = 2,
        )

        assertEquals(listOf("three", "two"), firstPage.map(DeviceStoredMessage::body))
        assertEquals(listOf("one"), secondPage.map(DeviceStoredMessage::body))
    }

    @Test
    fun schemaCanBeCreatedInAFileAndReopened(): Unit = runBlocking {
        val databaseFile = Files.createTempFile("folderspan-message-schema-", ".db")
        try {
            val firstDriver = JdbcSqliteDriver("jdbc:sqlite:$databaseFile")
            FolderSpanDatabase.Schema.create(firstDriver)
            val firstStore = SqlDelightDeviceMessageStore(createDatabase(firstDriver))
            firstStore.insertOutgoing(PEER, message("restart-message-00001", "durable", 100L), 100L)
            firstDriver.close()

            val secondDriver = JdbcSqliteDriver("jdbc:sqlite:$databaseFile")
            val secondStore = SqlDelightDeviceMessageStore(createDatabase(secondDriver))
            assertEquals("durable", secondStore.page(PEER, 20).single().body)
            secondDriver.close()
        } finally {
            Files.deleteIfExists(databaseFile)
        }
    }

    @Test
    fun closedStorageRejectsIncomingPersistence(): Unit = runBlocking {
        val testDatabase = createInMemoryDatabase()
        val store = SqlDelightDeviceMessageStore(testDatabase.database)
        testDatabase.driver.close()

        assertFails {
            store.persistIncoming(
                PEER,
                message("storage-failure-0001", "not accepted", 100L),
                receivedAtMillis = 150L,
                isRead = false,
            )
        }
    }

    private fun message(messageId: String, body: String, sentAtMillis: Long): ValidatedDeviceMessage =
        prepareDeviceMessage(body, messageId, sentAtMillis, sentAtMillis)

    private companion object {
        const val PEER = "peer-device-1"
    }
}

private data class MessageTestDatabase(
    val database: FolderSpanDatabase,
    val driver: JdbcSqliteDriver,
)

private fun createInMemoryDatabase(): MessageTestDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    FolderSpanDatabase.Schema.create(driver)
    DatabaseReady.markReady()
    return MessageTestDatabase(createDatabase(driver), driver)
}

private fun createDatabase(driver: JdbcSqliteDriver): FolderSpanDatabase = FolderSpanDatabase(
    driver = driver,
    DeviceAdapter = Device.Adapter(typeAdapter = messageDeviceTypeAdapter),
    DeviceConnectAdapter = DeviceConnect.Adapter(
        connectionTypeAdapter = messageDeviceConnectTypeAdapter,
        categoryAdapter = messageDeviceCategoryAdapter,
    ),
    FileBookmarkAdapter = FileBookmark.Adapter(
        typeAdapter = messageDrawerBookmarkTypeAdapter,
        protocolAdapter = messageFileProtocolAdapter,
    ),
    FileFavoriteAdapter = FileFavorite.Adapter(protocolAdapter = messageFileProtocolAdapter),
    FileRecentAdapter = FileRecent.Adapter(protocolAdapter = messageFileProtocolAdapter),
    FileFilterAdapter = FileFilter.Adapter(
        typeAdapter = messageFileFilterTypeAdapter,
        extensionsAdapter = messageListOfStringsAdapter,
    ),
    DeviceReceiveShareAdapter = DeviceReceiveShare.Adapter(
        connectionTypeAdapter = messageDeviceConnectTypeAdapter,
    ),
    FilePathPreferenceAdapter = FilePathPreference.Adapter(
        protocolAdapter = messageFileProtocolAdapter,
        sortAdapter = messageFileFilterSortAdapter,
        ignoreFilesAdapter = messageListOfStringsAdapter,
    ),
)

private val messageDeviceTypeAdapter = enumAdapter<DeviceType>()
private val messageDeviceConnectTypeAdapter = enumAdapter<DeviceConnectType>()
private val messageDeviceCategoryAdapter = enumAdapter<DeviceCategory>()
private val messageDrawerBookmarkTypeAdapter = enumAdapter<DrawerBookmarkType>()
private val messageFileProtocolAdapter = enumAdapter<FileProtocol>()
private val messageFileFilterTypeAdapter = enumAdapter<FileFilterType>()
private val messageFileFilterSortAdapter = enumAdapter<FileFilterSort>()

private inline fun <reified T : Enum<T>> enumAdapter(): ColumnAdapter<T, String> =
    object : ColumnAdapter<T, String> {
        override fun decode(databaseValue: String): T = enumValueOf(databaseValue)
        override fun encode(value: T): String = value.name
    }

private val messageListOfStringsAdapter = object : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String): List<String> =
        databaseValue.split(',').filter(String::isNotEmpty)

    override fun encode(value: List<String>): String = value.joinToString(",")
}
