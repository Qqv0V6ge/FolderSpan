package com.folderspan.ui.state.main

import com.folderspan.data.main.network.StorageContentLocationReference
import com.folderspan.service.network.StoragePath
import com.folderspan.data.main.network.NetworkDriveExtras
import com.folderspan.service.network.NetworkConnectionConfig
import com.folderspan.utils.ProtoBufCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnifiedStorageCompatibilityTest {
    @Test
    fun readsFixedLegacyConnectionPayloadsWithoutNewFields() {
        legacyConnections.forEach { (protocol, payload) ->
            val config = ProtoBufCodec.decode<NetworkConnectionConfig>(Base64.decode(payload))
            assertEquals(protocol, config.protocol)
            assertEquals("fixture.invalid", config.host)
            assertEquals("fixture", config.username)
            assertEquals("fixture-only", config.password)
            assertEquals("/", config.pathSeparator)
            assertEquals(NetworkDriveExtras(), config.extras)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun portableLocationReferenceRoundTripsWithoutLocalIdentityOrSecrets() {
        val fields = StorageContentLocationReference.serializer().descriptor
        assertEquals(listOf("formatVersion", "enabled", "root", "vaultId"),
            (0 until fields.elementsCount).map(fields::getElementName))
        legacyConnections.forEach { (_, payload) ->
            val ordinary = ProtoBufCodec.decode<NetworkConnectionConfig>(Base64.decode(payload))
            assertNull(ordinary.extras.contentEncryption)
            assertEquals(payload, Base64.encode(ProtoBufCodec.encode(ordinary)))
            val encrypted = ordinary.copy(extras = ordinary.extras.copy(
                contentEncryption = StorageContentLocationReference(
                    enabled = true, root = StoragePath("/backup"), vaultId = "vault-fixture")))
            val restored = ProtoBufCodec.decode<NetworkConnectionConfig>(ProtoBufCodec.encode(encrypted))
            assertEquals(encrypted, restored)
            assertEquals(ordinary, restored.copy(extras = restored.extras.copy(contentEncryption = null)))
        }
    }

    @Test
    fun readsFixedLegacySyncTaskWithOriginalDefaults() {
        val task = ProtoBufCodec.decode<SyncTask>(Base64.decode(LEGACY_SYNC_TASK))
        assertEquals(42L, task.id)
        assertEquals("legacy-sync", task.name)
        assertEquals(SyncEndpointType.Network, task.sourceType)
        assertEquals("legacy-network", task.sourceRef)
        assertEquals("/from", task.sourcePath)
        assertEquals(SyncEndpointType.Local, task.targetType)
        assertEquals("/to", task.targetPath)
        assertEquals(SyncConflictPolicy.Replace, task.conflictPolicy)
        assertEquals(SyncScheduleType.Manual, task.scheduleType)
        assertTrue(task.includeSubdirectories)
        assertTrue(task.includeEmptyDirectories)
        assertFalse(task.useIgnoreFiles)
        assertEquals(11L, task.createdAt)
        assertEquals(12L, task.updatedAt)
    }

    @Test
    fun readsFixedLegacyCheckpointWithOmittedVersion() {
        val checkpoint = legacyCheckpoint()
        checkpoint.requireSupportedFormat()
        assertEquals(3, checkpoint.schemaVersion)
        assertEquals(42L, checkpoint.taskKey)
        assertEquals("legacy-entry", checkpoint.entryId)
        assertEquals("/to/a", checkpoint.destPath)
        assertEquals(2048L, checkpoint.fileSize)
        assertEquals(1024, checkpoint.chunkSize)
        assertEquals(1, checkpoint.completedChunks)
        assertNull(checkpoint.uploadSession)
        assertEquals(LEGACY_CHECKPOINT, Base64.encode(ProtoBufCodec.encode(checkpoint)))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun sessionReferenceRoundTripsWithoutCredentials() {
        val checkpoint = legacyCheckpoint().copy(schemaVersion = 4, uploadSession = sessionReference())
        checkpoint.requireSupportedFormat()
        val payload = ProtoBufCodec.encode(checkpoint)
        val restored = ProtoBufCodec.decode<TaskRuntimeTransferCheckpoint>(payload)
        restored.requireSupportedFormat()
        assertEquals(checkpoint, restored)
        assertFalse(payload.decodeToString().contains("https://"))
        val fields = TaskRuntimeUploadSessionReference.serializer().descriptor
        assertEquals(
            listOf(
                "schemaVersion", "sessionId", "ownerRuntimeId", "connectionId", "source", "target",
                "sourceVersion", "confirmedOffset", "expiresAt",
            ),
            (0 until fields.elementsCount).map { fields.getElementName(it) },
        )
    }

    @Test
    fun unknownFormatsAndNonOpaqueSessionReferencesAreRejected() {
        val legacy = legacyCheckpoint()
        assertFailsWith<IllegalArgumentException> { legacy.copy(schemaVersion = 999).requireSupportedFormat() }
        assertFailsWith<IllegalArgumentException> {
            legacy.copy(uploadSession = sessionReference()).requireSupportedFormat()
        }
        listOf(
            sessionReference().copy(schemaVersion = 2),
            sessionReference().copy(sessionId = "https://upload.invalid/secret"),
            sessionReference().copy(ownerRuntimeId = ""),
            sessionReference().copy(connectionId = ""),
            sessionReference().copy(sourceVersion = ""),
            sessionReference().copy(confirmedOffset = 2049),
            sessionReference().copy(confirmedOffset = -1),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                legacy.copy(schemaVersion = 4, uploadSession = invalid).requireSupportedFormat()
            }
        }
    }

    private fun legacyCheckpoint() =
        ProtoBufCodec.decode<TaskRuntimeTransferCheckpoint>(Base64.decode(LEGACY_CHECKPOINT))

    private fun sessionReference() = TaskRuntimeUploadSessionReference(
        sessionId = "019a3bd0-0000-7000-8000-000000000001",
        ownerRuntimeId = "runtime-a",
        connectionId = "connection-a",
        source = TaskRuntimeEndpointRef(path = "/source/a"),
        target = TaskRuntimeEndpointRef(path = "/to/a"),
        sourceVersion = "sha256:fixture",
        confirmedOffset = 1024,
    )

    companion object {
        // Fixed protobuf fields from the MVP wire layout; no current serializer builds these fixtures.
        private val legacyConnections = listOf(
            "FTP" to "CgNGVFASD2ZpeHR1cmUuaW52YWxpZBoHZml4dHVyZSIMZml4dHVyZS1vbmx5",
            "SFTP" to "CgRTRlRQEg9maXh0dXJlLmludmFsaWQaB2ZpeHR1cmUiDGZpeHR1cmUtb25seQ==",
            "SMB" to "CgNTTUISD2ZpeHR1cmUuaW52YWxpZBoHZml4dHVyZSIMZml4dHVyZS1vbmx5",
            "WebDav" to "CgZXZWJEYXYSD2ZpeHR1cmUuaW52YWxpZBoHZml4dHVyZSIMZml4dHVyZS1vbmx5",
            "S3" to "CgJTMxIPZml4dHVyZS5pbnZhbGlkGgdmaXh0dXJlIgxmaXh0dXJlLW9ubHk=",
        )
        private const val LEGACY_SYNC_TASK = "CCoSC2xlZ2FjeS1zeW5jIAIqDmxlZ2FjeS1uZXR3b3JrMgUvZnJvbTgASgMvdG+wAQu4AQw="
        private const val LEGACY_CHECKPOINT = "ECoaDGxlZ2FjeS1lbnRyeSIFL3RvL2EogBAwgAg4AUALSAw="
    }
}
