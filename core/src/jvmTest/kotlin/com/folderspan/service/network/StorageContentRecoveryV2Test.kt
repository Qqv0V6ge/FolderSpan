package com.folderspan.service.network

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class StorageContentRecoveryV2Test {
    private val key = ByteArray(32) { it.toByte() }
    private val scope = "independent-runtime/connection/library/root"
    private val anchor = UUID.randomUUID().toString()
    private val password = "verified recovery password".toCharArray()

    @Test fun newRecoveryBindsKeyScopeAndClassificationAnchorWithoutChangingV1() {
        val backup = StorageContentRecoveryV2.exportRecovery(key, scope, anchor, password)
        assertEquals(204, backup.size)
        val recovered = StorageContentRecoveryV2.importRecovery(backup, scope, password)
        try { assertContentEquals(key, recovered.key); assertEquals(anchor, recovered.anchor) }
        finally { recovered.key.fill(0) }
        assertFails { StorageContentRecoveryV2.importRecovery(backup, "other-scope", password) }
        assertFails { StorageContentRecoveryV2.importRecovery(backup, scope, "wrong recovery password".toCharArray()) }
        for (offset in listOf(8, 12, 156, 172, 203)) {
            val damaged = backup.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertFails { StorageContentRecoveryV2.importRecovery(damaged, scope, password) }
        }
        val old = StorageContentEncryptionV1.exportRecovery(key, scope, password)
        assertEquals(140, old.size)
        assertContentEquals(key, StorageContentEncryptionV1.importRecovery(old, scope, password))
        assertFails { StorageContentRecoveryV2.importRecovery(old, scope, password) }
        assertFails { StorageContentEncryptionV1.importRecovery(backup, scope, password) }
    }

    @Test fun independentNodeVectorRecoversAnchorAndAuthenticatesTheCatalog() {
        val vector = javaClass.getResourceAsStream("/storage-content-classification-v2-vector.json")!!.use {
            Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
        }
        fun text(name: String) = vector.getValue(name).jsonPrimitive.content
        fun bytes(name: String) = text(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val recovered = StorageContentRecoveryV2.importRecovery(bytes("recoveryV2Hex"), text("scope"), text("recoveryPassword").toCharArray())
        try {
            assertContentEquals(bytes("locationKeyHex"), recovered.key)
            assertEquals(text("anchor"), recovered.anchor)
            val catalog = StorageContentManifestAuthentication.decode(text("manifestEnvelope").toByteArray(),
                recovered.key, text("scope"), recovered.anchor)
            assertEquals(2L, catalog.generation)
            assertEquals("native-object-1", catalog.records.single().objectId)
        } finally { recovered.key.fill(0) }
    }

    @Test fun uninitializedClassificationCannotBeExportedAndAnchorCannotBeReplaced() {
        val store = StorageContentKeyStore(com.russhwolf.settings.MapSettings())
        store.create(scope)
        assertFails { store.exportRecovery(scope, password) }
        store.markManifestInitialized(scope)
        val backup = store.exportRecovery(scope, password)
        store.verifyBackupAndEnable(scope, backup, password)
        val otherAnchor = StorageContentRecoveryV2.exportRecovery(store.keyForOperation(scope), scope, UUID.randomUUID().toString(), password)
        assertFails { store.importRecovery(scope, otherAnchor, password) }
        assertFails { store.verifyBackupAndEnable(scope, otherAnchor, password) }
        store.close()
    }
}
