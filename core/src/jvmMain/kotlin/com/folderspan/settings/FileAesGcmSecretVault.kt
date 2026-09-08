package com.folderspan.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal const val DESKTOP_SECURE_SETTINGS_DIRECTORY = "secure-settings"

internal class FileAesGcmSecretVault(
    private val directory: Path,
    private val random: SecureRandom = SecureRandom(),
) : StringSecretVault {
    private val lock = Any()
    private val json = Json
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val masterKey: SecretKey = synchronized(lock) { loadOrCreateMasterKey() }
    private var values: MutableMap<String, String> = synchronized(lock) { loadValues() }

    override fun get(key: String): String? = synchronized(lock) { values[key] }

    override fun put(key: String, value: String) {
        synchronized(lock) {
            values[key] = value
            persistLocked()
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            if (values.remove(key) != null) {
                persistLocked()
            }
        }
    }

    override fun contains(key: String): Boolean = synchronized(lock) { key in values }

    override fun keys(): Set<String> = synchronized(lock) { values.keys.toSet() }

    override fun clear() {
        synchronized(lock) {
            values.clear()
            persistLocked()
        }
    }

    private fun loadOrCreateMasterKey(): SecretKey {
        Files.createDirectories(directory)
        restrictOwnerOnly(directory, directory = true)
        val keyFile = masterKeyPath()
        if (Files.isRegularFile(keyFile)) {
            restrictOwnerOnly(keyFile, directory = false)
            val bytes = Files.readAllBytes(keyFile)
            require(bytes.size == MASTER_KEY_BYTES) { "Desktop secure-settings master key is invalid" }
            return SecretKeySpec(bytes, "AES")
        }
        val bytes = ByteArray(MASTER_KEY_BYTES).also(random::nextBytes)
        atomicWrite(keyFile, bytes)
        restrictOwnerOnly(keyFile, directory = false)
        return SecretKeySpec(bytes, "AES")
    }

    private fun loadValues(): MutableMap<String, String> {
        val valuesFile = valuesPath()
        if (!Files.isRegularFile(valuesFile)) {
            return mutableMapOf()
        }
        val decrypted = decrypt(Files.readAllBytes(valuesFile))
        return json.decodeFromString(mapSerializer, decrypted.decodeToString()).toMutableMap()
    }

    private fun persistLocked() {
        Files.createDirectories(directory)
        restrictOwnerOnly(directory, directory = true)
        val encoded = json.encodeToString(mapSerializer, values).encodeToByteArray()
        val valuesFile = valuesPath()
        atomicWrite(valuesFile, encrypt(encoded))
        restrictOwnerOnly(valuesFile, directory = false)
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > GCM_IV_BYTES) { "Desktop secure-settings payload is invalid" }
        val iv = blob.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(ciphertext)
    }

    private fun atomicWrite(path: Path, bytes: ByteArray) {
        val parent = path.parent ?: error("Secure settings path must have a parent directory")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${path.fileName}.", ".tmp")
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun masterKeyPath(): Path = directory.resolve(MASTER_KEY_FILE)

    private fun valuesPath(): Path = directory.resolve(VALUES_FILE)
}

internal fun restrictOwnerOnly(path: Path, directory: Boolean) {
    try {
        val permissions = if (directory) {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        } else {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )
        }
        Files.setPosixFilePermissions(path, permissions)
    } catch (_: UnsupportedOperationException) {
        val file = path.toFile()
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        if (directory) {
            file.setExecutable(true, true)
        }
    }
}

private const val MASTER_KEY_FILE = "master.key"
private const val VALUES_FILE = "values.v1"
private const val MASTER_KEY_BYTES = 32
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private val AAD = "FolderSpan.SecureSettings.v1".encodeToByteArray()
