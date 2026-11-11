package com.folderspan.service.network

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.folderspan.AndroidContextHolder
import com.folderspan.createSettings
import com.folderspan.security.TinkEncryption
import com.folderspan.security.enforceKeystoreProtectedKeyset
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadConfig2026
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import com.google.crypto.tink.subtle.Hex
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.CloudDriveExtras
import com.folderspan.data.main.network.NetworkDriveExtras
import java.nio.file.Files
import java.nio.file.Paths
import okio.Path.Companion.toPath
import java.io.ByteArrayInputStream
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageContentPlatformAndroidTest {
    @Test
    fun keystoreBackedSettingsRequireBackupAndKeepOAuthSeparate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidContextHolder.initialize(context)
        val config = config()
        val scope = storageContentScope(config)
        val keyName = keyName(scope)
        val oauthName = "auth.storage.account.android-content-test-" + UUID.randomUUID()
        val settings = createSettings()
        TinkEncryption.requireContentKeyProtection(context)
        val password = "android verified recovery password".toCharArray()
        var expectedKey: ByteArray? = null
        try {
            val masterKey = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .getKey("tink_master_key", null)
            assertNull(masterKey.encoded, "Android Keystore master key must remain non-exportable")
            StorageContentKeyStore(settings).use { keys ->
                keys.create(scope)
                keys.markManifestInitialized(scope) // KeyStore unit precondition; host test initializes the real CAS manifest.
                assertEquals(false to false, keys.status(scope))
                assertFails { keys.setEnabled(scope, true) }
                val backup = keys.exportRecovery(scope, password)
                assertFails { keys.verifyBackupAndEnable(scope, backup, "wrong recovery password".toCharArray()) }
                keys.verifyBackupAndEnable(scope, backup, password)
                expectedKey = keys.keyForOperation(scope)
                val recovered = StorageContentRecoveryV2.importRecovery(backup, scope, password)
                try {
                    assertContentEquals(expectedKey, recovered.key)
                    assertEquals(keys.manifestAnchor(scope), recovered.anchor)
                } finally { recovered.key.fill(0) }
                val storedRecord = settings.getString(keyName, "")
                val persisted = Files.readAllBytes(context.filesDir.toPath()
                    .resolve("datastore/secure_settings.preferences_pb")).decodeToString()
                assertFalse(persisted.contains(storedRecord), "Content key record must not be stored as plaintext")
                keys.lock(scope)
                assertFails { keys.keyForOperation(scope) }
            }
            val reopenedSettings = createSettings()
            StorageContentKeyStore(reopenedSettings).use { keys ->
                assertEquals(true to false, keys.status(scope))
                assertFails { keys.keyForOperation(scope) }
                reopenedSettings.putString(oauthName, "disposable-oauth-test-value")
                reopenedSettings.remove(oauthName)
                keys.unlock(scope)
                val actual = keys.keyForOperation(scope)
                try { assertContentEquals(expectedKey, actual) } finally { actual.fill(0) }
                keys.setEnabled(scope, false)
                keys.unlock(scope)
                assertFails { keys.keyForOperation(scope) }
                val readKey = keys.keyForReadOperation(scope)
                try { assertContentEquals(expectedKey, readKey) } finally { readKey.fill(0) }
            }
        } finally {
            expectedKey?.fill(0)
            password.fill('\u0000')
            settings.remove(keyName)
            settings.remove(oauthName)
        }
    }

    @Test
    fun sharedHostEncryptsAndAuthenticatesBeforePublishingOnAndroid() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidContextHolder.initialize(context)
        val config = config()
        assertTrue(StorageContentPlatform.status(config).getOrThrow().available)
        val settings = createSettings()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "content-android-")
        val provider = AndroidContentManifestProvider()
        val password = "android host recovery password".toCharArray()
        val plain = ByteArray(StorageContentEncryptionV1.CHUNK_BYTES + 19) { (it % 251).toByte() }
        val source = FileSimpleInfo.nullFileSimpleInfo().copy(
            path = "/generated/source", name = "source", size = plain.size.toLong(),
            updatedDate = 100, protocol = FileProtocol.Local,
        )
        try {
            StorageContentHostContext(StorageContentKeyStore(settings), settings, root.toString().toPath(), { provider }).use { host ->
                host.create(config).getOrThrow()
                val backup = host.exportRecovery(config, password).getOrThrow()
                host.verifyBackupAndEnable(config, backup, password).getOrThrow()
                val input = Paths.get(host.createStage(7, "entry").getOrThrow())
                Files.write(input, plain)
                assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(input))
                val upload = host.prepareUpload(config, 7, "entry", source, input.toString()).getOrThrow()!!
                val ciphertext = Files.readAllBytes(Paths.get(upload.path))
                assertContentEquals("FSPANENC".toByteArray(Charsets.US_ASCII), ciphertext.copyOf(8))
                assertFalse(Files.exists(input))
                assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(Paths.get(upload.path)))
                assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(root.resolve("7")))
                val destination = root.resolve("existing")
                val previous = byteArrayOf(99)
                Files.write(destination, previous)
                val damaged = ciphertext.copyOf().also {
                    it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                }
                var stage = Paths.get(host.createStage(7, "download").getOrThrow())
                Files.write(stage, damaged)
                assertTrue(host.publishDownload(config, 7, source, stage.toString(), destination.toString()).isFailure)
                assertContentEquals(previous, Files.readAllBytes(destination))
                assertFalse(Files.exists(stage))
                stage = Paths.get(host.createStage(7, "download").getOrThrow())
                Files.write(stage, ciphertext)
                assertTrue(host.publishDownload(config, 7, source, stage.toString(), destination.toString()).getOrThrow())
                assertContentEquals(plain, Files.readAllBytes(destination))
                host.cleanup(7).getOrThrow()
            }
        } finally {
            password.fill('\u0000')
            settings.remove(keyName(storageContentScope(config)))
            root.toFile().deleteRecursively()
        }
    }


    @Test
    fun legacyTestKeysetIsRewrappedAndVerifiedWithoutRotatingSettingsKeys() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "content-keyset-migration-" + UUID.randomUUID()
        val alias = "folderspan.content.test." + UUID.randomUUID()
        val uri = "android-keystore://" + alias
        val keysetName = "disposable-keyset"
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val challenge = "disposable migration test".toByteArray()
        val aad = byteArrayOf(1, 2, 3)
        AeadConfig.register()
        try {
            // Disposable legacy fixture only. No default settings keyset or user content key is changed.
            val legacy = AndroidKeysetManager.Builder().withSharedPref(context, keysetName, preferencesName)
                .withKeyTemplate(KeyTemplate.createFrom(PredefinedAeadParameters.AES256_GCM)).build()
            assertFalse(legacy.isUsingKeystore)
            val originalHandle = legacy.keysetHandle
            val ciphertext = legacy.keysetHandle.getPrimitive(AeadConfig2026.get(), Aead::class.java)
                .encrypt(challenge, aad)
            val original = Hex.decode(preferences.getString(keysetName, null)!!)
            assertFalse(keyStore.containsAlias(alias))
            val protected = enforceKeystoreProtectedKeyset(context, keysetName, preferencesName, uri)
            assertTrue(keyStore.containsAlias(alias))
            assertTrue(protected.isUsingKeystore)
            val persisted = Hex.decode(preferences.getString(keysetName, null)!!)
            try {
                assertFalse(original.contentEquals(persisted))
                val readBack = TinkProtoKeysetFormat.parseEncryptedKeyset(persisted,
                    AndroidKeystoreKmsClient().getAead(uri), byteArrayOf(), AeadConfig2026.get())
                assertTrue(readBack.equalsKeyset(originalHandle))
                assertContentEquals(challenge,
                    readBack.getPrimitive(AeadConfig2026.get(), Aead::class.java).decrypt(ciphertext, aad))
                assertNull(keyStore.getKey(alias, null).encoded)
                val masterProof = AndroidKeystoreKmsClient().getAead(uri).encrypt(challenge, aad)
                try {
                    // A second call must reuse the existing master, including material encrypted before it.
                    enforceKeystoreProtectedKeyset(context, keysetName, preferencesName, uri)
                    val verified = AndroidKeystoreKmsClient().getAead(uri).decrypt(masterProof, aad)
                    try { assertContentEquals(challenge, verified) } finally { verified.fill(0) }
                } finally { masterProof.fill(0) }
                val valid = Hex.decode(preferences.getString(keysetName, null)!!)
                val damaged = valid.copyOf().also { it[0] = 0 } // Protobuf tag zero is always invalid.
                try {
                    val damagedRecord = Hex.encode(damaged)
                    assertTrue(preferences.edit().putString(keysetName, damagedRecord).commit())
                    assertFails { enforceKeystoreProtectedKeyset(context, keysetName, preferencesName, uri) }
                    assertEquals(damagedRecord, preferences.getString(keysetName, null),
                        "An unreadable existing keyset must never be replaced or written back")
                } finally { valid.fill(0); damaged.fill(0) }
            } finally { original.fill(0); persisted.fill(0); ciphertext.fill(0) }
        } finally {
            challenge.fill(0)
            context.deleteSharedPreferences(preferencesName)
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    @Test
    fun independentV1VectorWorksWithAndroidCryptoProvider() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "content-android-vector-")
        // Independent Node reference vector; also verified by JVM and browser tests.
        fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val scope = "reference-runtime/reference-connection/library/folder"
        val expected = bytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val asciiKey = StorageContentEncryptionV1.importRecovery(
            bytes("465350414e4b455900000001000927c0606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f7e7462f58bdc8347bbfcc3c79cc829ac6dba5f6ee3ba7d06daae22075d40d4b8808182838485868788898a8b773579d780cc608fce4992439c76d2e0fb6512207626f324b178934033fd784eb7ecf6255bd0a74f8bc60e9fe930c6bf"), scope, "reference recovery password".toCharArray())
        val unicodeKey = StorageContentEncryptionV1.importRecovery(
            bytes("465350414e4b455900000001000927c0606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f7e7462f58bdc8347bbfcc3c79cc829ac6dba5f6ee3ba7d06daae22075d40d4b8808182838485868788898a8b9dd965668bfed2c8aa60dde0675eb9d3b0ce42446ae5ece5fbc695086597e3327808bbdeda99ac174332b7f224b9dd3b"), scope, "恢复材料安全口令☀pass123".toCharArray())
        try {
            assertContentEquals(expected, asciiKey)
            assertContentEquals(expected, unicodeKey)
            val output = directory.resolve("plain")
            StorageContentEncryptionV1.decryptAndPublish(ByteArrayInputStream(bytes("465350414e454e430000000100100000101112131415161718191a1b1c1d1e1f7e7462f58bdc8347bbfcc3c79cc829ac6dba5f6ee3ba7d06daae22075d40d4b8404142434445464748494a4bc2988c000219a124e5ed3d1db7493d7449f7606f830039144f7e74deab2ed3e40f5436cd9fd47c09bb7cd2915e96c7d100000014a5418d3df12d3729a937c434579f79050f4018744c94c010cfe4717da408f6fd595f997e00000000000000000000001400000000000000017eaedc3856da80225ffc7983b0bfbac5")), output, asciiKey, scope)
            assertContentEquals(bytes("466f6c6465725370616e20763120766563746f72"), Files.readAllBytes(output))
        } finally {
            expected.fill(0)
            asciiKey.fill(0)
            unicodeKey.fill(0)
            directory.toFile().deleteRecursively()
        }
    }

    private fun config() = NetworkConnectionConfig(
        "GoogleDrive", "fixture.invalid", "", "",
        extras = NetworkDriveExtras(cloud = CloudDriveExtras(
            accountId = "android-content-test", connectionId = UUID.randomUUID().toString(), rootId = "root",
        )),
    )

    private fun keyName(scope: String): String = "auth.storage.content." +
        MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}


/** Explicit CAS fixture; the shared host still authenticates and updates the actual catalog format. */
private class AndroidContentManifestProvider : StorageProvider by UnsupportedNetworkClient("android-content-fixture") {
    override val capabilities = StorageCapabilities.ReadWrite
    override val contentManifestConditionalWrites = true
    private val manifests = mutableMapOf<String, StorageContentManifestSnapshot>()
    private var revision = 0L

    override suspend fun readContentManifest(anchor: String): Result<StorageContentManifestSnapshot?> = synchronized(this) {
        Result.success(manifests[anchor]?.let { it.copy(bytes = it.bytes.copyOf()) })
    }

    override suspend fun compareAndSetContentManifest(
        anchor: String, expectedRevision: String?, bytes: ByteArray,
    ): Result<StorageContentManifestSnapshot> = synchronized(this) {
        val current = manifests[anchor]
        if (expectedRevision == null && current != null || expectedRevision != null && current?.revision != expectedRevision)
            Result.failure(StorageContentManifestConflictException())
        else Result.success(StorageContentManifestSnapshot((++revision).toString(), bytes.copyOf())
            .also { manifests[anchor] = it })
    }

    override suspend fun contentObjectIdentity(path: StoragePath): Result<String?> = Result.success(path.reference?.id)
}
