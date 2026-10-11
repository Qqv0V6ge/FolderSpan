package com.folderspan.service.http.tls

import strings.AppStrings

import com.folderspan.androidContext
import com.folderspan.createSettings
import com.folderspan.utils.SettingsUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object DeviceTlsIdentityFileStore {
    fun readIdentityPayload(): ByteArray? {
        return defaultStore().readIdentityPayload()
    }

    fun writeIdentityPayload(payload: ByteArray) {
        defaultStore().writeIdentityPayload(payload)
    }

    private fun defaultStore(): AndroidTlsIdentityFileStore {
        val directory = File(androidContext().filesDir, "tls-identity")
        val deviceId = createSettings().getString(SettingsUtils.KEY_DEVICE_ID, "")
        return openAndroidTlsIdentityFileStore(directory, deviceId)
    }
}

internal fun openAndroidTlsIdentityFileStore(
    directory: File,
    deviceId: String,
    random: TlsIdentityStorageRandom = SecureTlsIdentityStorageRandom(),
): AndroidTlsIdentityFileStore {
    if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
        throw IOException(AppStrings.ui_tls_identity_store_directory_creation_failed)
    }
    val saltFile = File(directory, TlsIdentityStorageKeyMaterial.ENTROPY_SALT_FILE)
    val saltHex = if (saltFile.isFile) {
        saltFile.readBytes().toHex()
    } else {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        writeOwnerOnlyFile(saltFile, salt)
        salt.toHex()
    }
    return createAndroidTlsIdentityFileStore(directory, deviceId, saltHex, random)
}

private fun createAndroidTlsIdentityFileStore(
    directory: File,
    deviceId: String,
    entropySaltHex: String,
    random: TlsIdentityStorageRandom,
): AndroidTlsIdentityFileStore {
    return AndroidTlsIdentityFileStore(
        directory = directory,
        keyMaterial = TlsIdentityStorageKeyMaterial.build(
            deviceId = deviceId,
            directoryPath = directory.absolutePath,
            entropySaltHex = entropySaltHex,
        ),
        random = random,
    )
}

private fun writeOwnerOnlyFile(file: File, bytes: ByteArray) {
    file.outputStream().use { output ->
        output.write(bytes)
        output.fd.sync()
    }
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
}

internal class AndroidTlsIdentityFileStore(
    private val directory: File,
    keyMaterial: String,
    private val random: TlsIdentityStorageRandom = SecureTlsIdentityStorageRandom()
) {
    private val cipher = AesGcmTlsIdentitySegmentCipher(keyMaterial.encodeToByteArray())

    fun readIdentityPayload(): ByteArray? {
        val file = identityFile()
        if (!file.isFile) return null
        return SegmentedTlsIdentityContainer.decodeIdentityPayloadOrNull(file.readBytes(), cipher)
            ?: throw IOException(AppStrings.ui_tls_identity_file_invalid)
    }

    fun writeIdentityPayload(payload: ByteArray) {
        ensureDirectoryExists()
        val file = identityFile()
        val encoded = SegmentedTlsIdentityContainer.encodeIdentityPayload(payload, cipher, random)
        val temporaryFile = File.createTempFile(".${file.name}.", TEMPORARY_FILE_SUFFIX, directory)

        try {
            FileOutputStream(temporaryFile).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            verifyEncodedPayload(temporaryFile, payload)
            replaceAtomically(temporaryFile, file)
        } finally {
            if (temporaryFile.exists() && !temporaryFile.delete()) {
                temporaryFile.deleteOnExit()
            }
        }
    }

    internal fun identityFileName(): String {
        return FILE_NAME_CONTEXT.encodeToByteArray().sha256().toHex() + ".fmi"
    }

    private fun identityFile(): File = File(directory, identityFileName())

    private fun ensureDirectoryExists() {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException(AppStrings.ui_tls_identity_store_directory_creation_failed)
        }
        if (!directory.isDirectory) {
            throw IOException(AppStrings.ui_tls_identity_store_path_not_directory)
        }
    }

    private fun verifyEncodedPayload(file: File, expectedPayload: ByteArray) {
        val actualPayload = SegmentedTlsIdentityContainer.decodeIdentityPayloadOrNull(file.readBytes(), cipher)
            ?: throw IOException(AppStrings.ui_tls_identity_file_post_write_verification_failed)
        if (!MessageDigest.isEqual(expectedPayload, actualPayload)) {
            throw IOException(AppStrings.ui_tls_identity_post_write_content_mismatch)
        }
    }

    private fun replaceAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}

private class AesGcmTlsIdentitySegmentCipher(keyMaterial: ByteArray) : TlsIdentitySegmentCipher {
    override val ivSize: Int = 12
    private val key = SecretKeySpec(keyMaterial.sha256(), "AES")

    override fun encrypt(plain: ByteArray, iv: ByteArray, associatedData: ByteArray): ByteArray {
        return crypt(Cipher.ENCRYPT_MODE, plain, iv, associatedData)
    }

    override fun decrypt(encrypted: ByteArray, iv: ByteArray, associatedData: ByteArray): ByteArray {
        return crypt(Cipher.DECRYPT_MODE, encrypted, iv, associatedData)
    }

    private fun crypt(mode: Int, data: ByteArray, iv: ByteArray, associatedData: ByteArray): ByteArray {
        require(iv.size == ivSize) { AppStrings.ui_tls_identity_segment_nonce_size_invalid }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(data)
    }

    private companion object {
        const val GCM_TAG_BITS = 128
    }
}

private class SecureTlsIdentityStorageRandom : TlsIdentityStorageRandom {
    private val delegate = SecureRandom()

    override fun nextInt(until: Int): Int {
        require(until > 0) { "Upper bound must be positive" }
        return delegate.nextInt(until)
    }

    override fun nextBytes(size: Int): ByteArray {
        require(size >= 0) { "Size must be non-negative" }
        return ByteArray(size).also(delegate::nextBytes)
    }
}

private fun ByteArray.sha256(): ByteArray {
    return MessageDigest.getInstance("SHA-256").digest(this)
}

private fun ByteArray.toHex(): String {
    return joinToString("") { byte -> "%02X".format(byte) }
}

private const val FILE_NAME_CONTEXT = "FolderSpan.DeviceTlsIdentity.ActiveIdentityFile.v2"
private const val TEMPORARY_FILE_SUFFIX = ".tmp"
