package com.folderspan.service.network

import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory

class StorageContentEncryptionIosTest {
    private val fs = FileSystem.SYSTEM
    private val key = ByteArray(32) { it.toByte() }
    private val scope = "reference-runtime/reference-connection/library/folder"
    private val anchor = "00010203-0405-4607-8809-0a0b0c0d0e0f"

    private fun directory(): Path = (NSTemporaryDirectory() + "folderspan-content-" + Uuid.random()).toPath().also {
        fs.createDirectories(it)
        StorageContentFileProtection.restrictOwnerOnly(it, true)
    }

    private fun encrypt(plain: ByteArray): ByteArray = Buffer().also {
        StorageContentEncryptionV1.encrypt(Buffer().write(plain), it, key, scope)
    }.readByteArray()

    @Test
    fun independentNodeVectorsAuthenticateAndRecoverAsciiUnicodeAndManifest() {
        val vector = Json.parseToJsonElement(IOS_CONTENT_V1_REFERENCE).jsonObject
        fun value(name: String) = vector.getValue(name).jsonPrimitive.content
        fun bytes(name: String) = value(name).hexBytes()
        val directory = directory()
        try {
            assertContentEquals(key, StorageContentEncryptionV1.importRecovery(bytes("recoveryHex"), scope, value("recoveryPassword").toCharArray()))
            assertContentEquals(key, StorageContentEncryptionV1.importRecovery(bytes("recoveryUnicodeHex"), scope, value("recoveryUnicodePassword").toCharArray()))
            val destination = directory / "reference-plain"
            StorageContentEncryptionV1.decryptAndPublish(Buffer().write(bytes("ciphertextHex")), destination, key, scope)
            assertContentEquals(bytes("plaintextHex"), fs.read(destination) { readByteArray() })
            val classification = Json.parseToJsonElement(IOS_CONTENT_V2_REFERENCE).jsonObject
            val recovered = StorageContentRecoveryV2.importRecovery(
                classification.getValue("recoveryV2Hex").jsonPrimitive.content.hexBytes(),
                scope, classification.getValue("recoveryPassword").jsonPrimitive.content.toCharArray())
            try {
                assertContentEquals(key, recovered.key)
                assertEquals(anchor, recovered.anchor)
                val catalog = StorageContentManifestAuthentication.decode(
                    classification.getValue("manifestEnvelope").jsonPrimitive.content.encodeToByteArray(),
                    recovered.key, scope, recovered.anchor)
                assertEquals(2L, catalog.generation)
                assertEquals("native-object-1", catalog.records.single().objectId)
            } finally { recovered.key.fill(0) }
        } finally { fs.deleteRecursively(directory) }
    }

    @Test
    fun emptyAndMultipleChunksRoundTripWithFreshFileIdentities() {
        val directory = directory()
        try {
            for (size in listOf(0, 17, 2 * StorageContentEncryptionV1.CHUNK_BYTES + 13)) {
                val plain = ByteArray(size) { (it % 251).toByte() }
                val ciphertext = encrypt(plain)
                val destination = directory / "plain"
                StorageContentEncryptionV1.decryptAndPublish(Buffer().write(ciphertext), destination, key, scope)
                assertContentEquals(plain, fs.read(destination) { readByteArray() })
                val another = encrypt(plain)
                assertFalse(ciphertext.copyOfRange(16, 32).contentEquals(another.copyOfRange(16, 32)))
                assertFalse(ciphertext.copyOfRange(64, 76).contentEquals(another.copyOfRange(64, 76)))
            }
        } finally { fs.deleteRecursively(directory) }
    }

    @Test
    fun damageTrailingBytesWrongKeyAndCancellationPreserveExistingDestination() {
        val directory = directory()
        val destination = directory / "existing"
        val previous = "previous authenticated content".encodeToByteArray()
        val cipher = encrypt(ByteArray(StorageContentEncryptionV1.CHUNK_BYTES + 17) { (it % 251).toByte() })
        try {
            fs.write(destination) { write(previous) }
            val invalid = listOf(
                cipher.copyOf(0), cipher.copyOf(123), cipher.copyOf(cipher.size - 1), cipher + byteArrayOf(1),
                cipher.copyOf().also { it[11] = 2 },
                cipher.copyOf().also { it[130] = (it[130].toInt() xor 1).toByte() },
                cipher.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
            )
            for (bytes in invalid) {
                assertFails { StorageContentEncryptionV1.decryptAndPublish(Buffer().write(bytes), destination, key, scope) }
                assertContentEquals(previous, fs.read(destination) { readByteArray() })
            }
            assertFails { StorageContentEncryptionV1.decryptAndPublish(Buffer().write(cipher), destination, ByteArray(32), scope) }
            assertFails { StorageContentEncryptionV1.decryptAndPublish(Buffer().write(cipher), destination, key, "other-scope") }
            var calls = 0
            assertFailsWith<CancellationException> {
                StorageContentEncryptionV1.decryptAndPublish(Buffer().write(cipher), destination, key, scope) {
                    if (++calls == 2) throw CancellationException("cancel native content download")
                }
            }
            assertContentEquals(previous, fs.read(destination) { readByteArray() })
            assertEquals(listOf(destination), fs.list(directory))
        } finally { fs.deleteRecursively(directory) }
    }

    @Test
    fun recoveryRejectsWrongPasswordScopeAnchorAndUnboundedDerivation() {
        val password = "恢复材料🔐跨平台口令pass123".toCharArray()
        val backup = StorageContentRecoveryV2.exportRecovery(key, scope, anchor, password)
        val recovered = StorageContentRecoveryV2.importRecovery(backup, scope, password)
        try { assertContentEquals(key, recovered.key); assertEquals(anchor, recovered.anchor) }
        finally { recovered.key.fill(0) }
        assertFails { StorageContentRecoveryV2.importRecovery(backup, scope, "wrong-password-123".toCharArray()) }
        assertFails { StorageContentRecoveryV2.importRecovery(backup, "other-scope", password) }
        for (offset in listOf(8, 12, 156, 172, 203)) {
            assertFails { StorageContentRecoveryV2.importRecovery(backup.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }, scope, password) }
        }
        val v1 = StorageContentEncryptionV1.exportRecovery(key, scope, password)
        assertFails { StorageContentEncryptionV1.importRecovery(v1.copyOf().also { it[15] = 1 }, scope, password) }
        assertFails { StorageContentEncryptionV1.importRecovery(v1 + byteArrayOf(0), scope, password) }
        assertFails { StorageContentEncryptionV1.exportRecovery(key, scope, CharArray(1025) { 'x' }) }
    }

    @Test
    fun exportPublicNativeFixturesForIndependentJvmAndNodeVerification() {
        val directory = directory()
        val plain = "Native iOS → JVM/Node format v1, 中文与🔐".encodeToByteArray()
        val ascii = "native reference recovery password".toCharArray()
        val unicode = "恢复材料🔐跨平台口令pass123".toCharArray()
        val manifest = StorageContentManifest(scope = scope, anchor = anchor, generation = 2)
        val fixtures = linkedMapOf(
            "ciphertextHex" to encrypt(plain).hexText(),
            "recoveryHex" to StorageContentEncryptionV1.exportRecovery(key, scope, ascii).hexText(),
            "recoveryUnicodeHex" to StorageContentEncryptionV1.exportRecovery(key, scope, unicode).hexText(),
            "recoveryV2Hex" to StorageContentRecoveryV2.exportRecovery(key, scope, anchor, unicode).hexText(),
            "manifestEnvelope" to StorageContentManifestAuthentication.encode(manifest, key).decodeToString(),
        )
        val document = buildJsonObject {
            put("scope", scope); put("anchor", anchor); put("locationKeyHex", key.hexText())
            put("plaintextHex", plain.hexText()); put("recoveryPassword", ascii.concatToString())
            put("recoveryUnicodePassword", unicode.concatToString())
            fixtures.forEach { (name, value) -> put(name, value) }
        }
        fs.write(directory / "native-vectors.json") { writeUtf8(document.toString()) }
        println("STORAGE_CONTENT_IOS_VECTOR_DIR=$directory")
        ascii.fill('\u0000'); unicode.fill('\u0000')
    }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hexText(): String = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
}

private const val IOS_CONTENT_V1_REFERENCE = "{\n  \"version\": 1,\n  \"scope\": \"reference-runtime/reference-connection/library/folder\",\n  \"locationKeyHex\": \"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f\",\n  \"plaintextHex\": \"466f6c6465725370616e20763120766563746f72\",\n  \"ciphertextHex\": \"465350414e454e430000000100100000101112131415161718191a1b1c1d1e1f7e7462f58bdc8347bbfcc3c79cc829ac6dba5f6ee3ba7d06daae22075d40d4b8404142434445464748494a4bc2988c000219a124e5ed3d1db7493d7449f7606f830039144f7e74deab2ed3e40f5436cd9fd47c09bb7cd2915e96c7d100000014a5418d3df12d3729a937c434579f79050f4018744c94c010cfe4717da408f6fd595f997e00000000000000000000001400000000000000017eaedc3856da80225ffc7983b0bfbac5\",\n  \"recoveryPassword\": \"reference recovery password\",\n  \"recoveryHex\": \"465350414e4b455900000001000927c0606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f7e7462f58bdc8347bbfcc3c79cc829ac6dba5f6ee3ba7d06daae22075d40d4b8808182838485868788898a8b773579d780cc608fce4992439c76d2e0fb6512207626f324b178934033fd784eb7ecf6255bd0a74f8bc60e9fe930c6bf\",\n  \"recoveryUnicodePassword\": \"恢复材料安全口令☀pass123\",\n  \"recoveryUnicodeHex\": \"465350414e4b455900000001000927c0606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f7e7462f58bdc8347bbfcc3c79cc829ac6dba5f6ee3ba7d06daae22075d40d4b8808182838485868788898a8b9dd965668bfed2c8aa60dde0675eb9d3b0ce42446ae5ece5fbc695086597e3327808bbdeda99ac174332b7f224b9dd3b\"\n}\n"

private const val IOS_CONTENT_V2_REFERENCE = "{\n  \"scope\": \"reference-runtime/reference-connection/library/folder\",\n  \"anchor\": \"00010203-0405-4607-8809-0a0b0c0d0e0f\",\n  \"locationKeyHex\": \"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f\",\n  \"recoveryPassword\": \"reference recovery password\",\n  \"recoveryV2Hex\": \"465350414e434154000000020000008c465350414e4b455900000001000927c0606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f7e7462f58bdc8347bbfcc3c79cc829ac6dba5f6ee3ba7d06daae22075d40d4b8808182838485868788898a8b773579d780cc608fce4992439c76d2e0fb6512207626f324b178934033fd784eb7ecf6255bd0a74f8bc60e9fe930c6bf000102030405460788090a0b0c0d0e0fed2b3275d9fdfc749f4c2c99ac08f4c01f0983f56cb5366e5d8b1fe453179e16\",\n  \"manifestEnvelope\": \"{\\\"version\\\":1,\\\"payload\\\":\\\"eyJ2ZXJzaW9uIjoxLCJzY29wZSI6InJlZmVyZW5jZS1ydW50aW1lL3JlZmVyZW5jZS1jb25uZWN0aW9uL2xpYnJhcnkvZm9sZGVyIiwiYW5jaG9yIjoiMDAwMTAyMDMtMDQwNS00NjA3LTg4MDktMGEwYjBjMGQwZTBmIiwiZ2VuZXJhdGlvbiI6MiwicmVjb3JkcyI6W3sib3BlcmF0aW9uSWQiOiIwMTAyMDMwNC0wNTA2LTQ3MDgtODkwYS0wYjBjMGQwZTBmMTAiLCJwYXRoIjoiL3JlZmVyZW5jZS1maWxlIiwib2JqZWN0SWQiOiJuYXRpdmUtb2JqZWN0LTEiLCJjaXBoZXJ0ZXh0RGlnZXN0IjoiYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYSIsInByZXZpb3VzUGxhaW50ZXh0RGlnZXN0IjpudWxsLCJjb21taXR0ZWQiOnRydWV9XX0=\\\",\\\"authentication\\\":\\\"D+3rkIj1EbG2uA52MGn+9QBS4Lem5jqso5oRa81vTJ8=\\\"}\"\n}\n"
