@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.folderspan.service.http.tls

import strings.AppStrings

import com.folderspan.createSettings
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SettingsUtils
import com.folderspan.utils.protectIosPrivatePath
import kotlinx.cinterop.*
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.CoreCrypto.*
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.posix.O_RDONLY
import platform.posix.arc4random_buf
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.open
import platform.posix.size_tVar
import platform.posix.strerror

internal object DeviceTlsIdentityFileStore {
    private val fileSystem = FileSystem.SYSTEM
    private val fileManager = NSFileManager.defaultManager

    fun readIdentityPayload(): ByteArray? {
        return defaultStore().readIdentityPayload()
    }

    fun writeIdentityPayload(payload: ByteArray) {
        defaultStore().writeIdentityPayload(payload)
    }

    private fun defaultStore(): IosTlsIdentityFileStore {
        val directory = PathUtils.getAppPath().trimEnd('/') + "/Library/Application Support/FolderSpan/tls-identity"
        val deviceId = createSettings().getString(SettingsUtils.KEY_DEVICE_ID, "")
        return openIosTlsIdentityFileStore(directory, deviceId)
    }

    internal fun ensureDirectory(path: String) {
        if (fileManager.fileExistsAtPath(path)) {
            if (!fileSystem.metadata(path.toPath()).isDirectory) {
                error("${AppStrings.ui_tls_identity_store_path_not_directory}: $path")
            }
            protectIosPrivatePath(path)
            return
        }
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val created = fileManager.createDirectoryAtPath(
                path,
                true,
                attributes = null,
                error = error.ptr
            )
            if (!created) {
                error("${AppStrings.ui_tls_identity_directory_creation_failed}: ${error.value?.localizedDescription}")
            }
        }
        if (fileSystem.metadataOrNull(path.toPath())?.isDirectory != true) {
            error("${AppStrings.ui_tls_identity_directory_post_creation_verification_failed}: $path")
        }
        protectIosPrivatePath(path)
    }

    internal fun fileExists(path: String): Boolean {
        return fileManager.fileExistsAtPath(path)
    }

    internal fun readFile(path: String): ByteArray {
        return fileSystem.source(path.toPath()).buffer().use { source ->
            source.readByteArray()
        }
    }

    internal fun writeNewFile(path: String, bytes: ByteArray) {
        fileSystem.openReadWrite(
            file = path.toPath(),
            mustCreate = true,
            mustExist = false,
        ).use { handle ->
            handle.write(
                fileOffset = 0L,
                array = bytes,
                arrayOffset = 0,
                byteCount = bytes.size,
            )
            handle.flush()
        }
    }

    internal fun replaceFileAtomically(source: String, destination: String) {
        fileSystem.atomicMove(source.toPath(), destination.toPath())
    }

    internal fun syncFile(path: String) {
        val descriptor = open(path, O_RDONLY)
        if (descriptor < 0) {
            val errorCode = errno
            error("${AppStrings.ui_tls_identity_temp_file_open_for_sync_failed}: ${posixErrorMessage(errorCode)}")
        }
        val syncResult = fsync(descriptor)
        val syncErrorCode = if (syncResult == 0) 0 else errno
        val closeResult = close(descriptor)
        val closeErrorCode = if (closeResult == 0) 0 else errno
        if (syncResult != 0) {
            val closeFailure = if (closeResult == 0) {
                ""
            } else {
                AppStrings.ui_close_file_descriptor_also_failed_arg0.format(arg0 = posixErrorMessage(closeErrorCode))
            }
            error("${AppStrings.ui_tls_identity_temp_file_sync_failed}: ${posixErrorMessage(syncErrorCode)}$closeFailure")
        }
        if (closeResult != 0) {
            error(
                "${AppStrings.ui_tls_identity_temp_file_descriptor_close_failed}: " +
                    posixErrorMessage(closeErrorCode)
            )
        }
    }

    internal fun deleteFileIfExists(path: String) {
        fileSystem.delete(path.toPath(), mustExist = false)
    }

    private fun posixErrorMessage(errorCode: Int): String {
        return strerror(errorCode)?.toKString() ?: "errno=$errorCode"
    }
}

internal fun openIosTlsIdentityFileStore(
    directory: String,
    deviceId: String,
    random: TlsIdentityStorageRandom = SecureTlsIdentityStorageRandom(),
    fileOperations: IosTlsIdentityFileOperations = SystemIosTlsIdentityFileOperations,
): IosTlsIdentityFileStore {
    fileOperations.ensureDirectory(directory)
    val saltPath = directory.trimEnd('/') + "/" + TlsIdentityStorageKeyMaterial.ENTROPY_SALT_FILE
    val saltHex = if (fileOperations.fileExists(saltPath)) {
        val salt = fileOperations.readFile(saltPath)
        require(salt.isNotEmpty()) { "TLS identity storage salt must not be empty" }
        salt.toHex()
    } else {
        val salt = random.nextBytes(32)
        require(salt.isNotEmpty()) { "TLS identity storage salt must not be empty" }
        fileOperations.writeNewFile(saltPath, salt)
        fileOperations.syncFile(saltPath)
        salt.toHex()
    }
    return IosTlsIdentityFileStore(
        directory = directory,
        keyMaterial = TlsIdentityStorageKeyMaterial.build(
            deviceId = deviceId,
            directoryPath = directory,
            entropySaltHex = saltHex,
        ),
        random = random,
        fileOperations = fileOperations,
    )
}

internal interface IosTlsIdentityFileOperations {
    fun ensureDirectory(path: String)
    fun fileExists(path: String): Boolean
    fun readFile(path: String): ByteArray
    fun writeNewFile(path: String, bytes: ByteArray)
    fun syncFile(path: String)
    fun replaceFileAtomically(source: String, destination: String)
    fun deleteFileIfExists(path: String)
}

private object SystemIosTlsIdentityFileOperations : IosTlsIdentityFileOperations {
    override fun ensureDirectory(path: String) = DeviceTlsIdentityFileStore.ensureDirectory(path)

    override fun fileExists(path: String): Boolean = DeviceTlsIdentityFileStore.fileExists(path)

    override fun readFile(path: String): ByteArray = DeviceTlsIdentityFileStore.readFile(path)

    override fun writeNewFile(path: String, bytes: ByteArray) {
        DeviceTlsIdentityFileStore.writeNewFile(path, bytes)
    }

    override fun syncFile(path: String) {
        DeviceTlsIdentityFileStore.syncFile(path)
    }

    override fun replaceFileAtomically(source: String, destination: String) {
        DeviceTlsIdentityFileStore.replaceFileAtomically(source, destination)
    }

    override fun deleteFileIfExists(path: String) {
        DeviceTlsIdentityFileStore.deleteFileIfExists(path)
    }
}

internal class IosTlsIdentityFileStore(
    private val directory: String,
    keyMaterial: String,
    private val random: TlsIdentityStorageRandom = SecureTlsIdentityStorageRandom(),
    private val fileOperations: IosTlsIdentityFileOperations = SystemIosTlsIdentityFileOperations,
) {
    private val cipher = AesCbcHmacTlsIdentitySegmentCipher(keyMaterial.encodeToByteArray())

    fun readIdentityPayload(): ByteArray? {
        val path = identityFilePath()
        if (!fileOperations.fileExists(path)) return null
        return SegmentedTlsIdentityContainer.decodeIdentityPayloadOrNull(
            fileOperations.readFile(path),
            cipher,
        ) ?: throw IllegalStateException(AppStrings.ui_tls_identity_file_invalid)
    }

    fun writeIdentityPayload(payload: ByteArray) {
        fileOperations.ensureDirectory(directory)
        val encoded = SegmentedTlsIdentityContainer.encodeIdentityPayload(payload, cipher, random)
        val destinationPath = identityFilePath()
        val temporaryPath = temporaryIdentityFilePath()
        try {
            fileOperations.writeNewFile(temporaryPath, encoded)
            fileOperations.syncFile(temporaryPath)
            verifyTemporaryPayload(temporaryPath, payload)
            fileOperations.replaceFileAtomically(temporaryPath, destinationPath)
        } catch (error: Throwable) {
            rethrowAfterTemporaryFileCleanup(temporaryPath, error)
        }
    }

    internal fun identityFileName(): String {
        return FILE_NAME_CONTEXT.encodeToByteArray().sha256().toHex() + ".fmi"
    }

    private fun identityFilePath(): String {
        return directory.trimEnd('/') + "/" + identityFileName()
    }

    private fun temporaryIdentityFilePath(): String {
        val randomSuffix = random.nextBytes(TEMPORARY_FILE_RANDOM_BYTES).toHex()
        return directory.trimEnd('/') + "/." + identityFileName() + ".$randomSuffix.tmp"
    }

    private fun verifyTemporaryPayload(path: String, expectedPayload: ByteArray) {
        val actualPayload = SegmentedTlsIdentityContainer.decodeIdentityPayloadOrNull(
            fileOperations.readFile(path),
            cipher,
        ) ?: throw IllegalStateException(AppStrings.ui_tls_identity_file_post_write_verification_failed)
        if (!expectedPayload.contentEquals(actualPayload)) {
            throw IllegalStateException(AppStrings.ui_tls_identity_post_write_content_mismatch)
        }
    }

    private fun rethrowAfterTemporaryFileCleanup(path: String, writeError: Throwable): Nothing {
        try {
            fileOperations.deleteFileIfExists(path)
        } catch (cleanupError: Throwable) {
            throw IllegalStateException(
                "${AppStrings.ui_tls_identity_temp_file_cleanup_after_write_failure_failed}: ${cleanupError.message}",
                writeError,
            )
        }
        throw writeError
    }
}

private class AesCbcHmacTlsIdentitySegmentCipher(keyMaterial: ByteArray) : TlsIdentitySegmentCipher {
    override val ivSize: Int = 16
    private val encryptionKey = (byteArrayOf(0x01) + keyMaterial).sha256()
    private val authenticationKey = (byteArrayOf(0x02) + keyMaterial).sha256()

    override fun encrypt(plain: ByteArray, iv: ByteArray, associatedData: ByteArray): ByteArray {
        val encrypted = crypt(plain, iv, kCCEncrypt)
        return encrypted + hmacSha256(associatedData + iv + encrypted)
    }

    override fun decrypt(encrypted: ByteArray, iv: ByteArray, associatedData: ByteArray): ByteArray {
        require(encrypted.size > HMAC_BYTES) {
            AppStrings.ui_tls_identity_segment_authentication_tag_missing
        }
        val ciphertext = encrypted.copyOfRange(0, encrypted.size - HMAC_BYTES)
        val actualTag = encrypted.copyOfRange(encrypted.size - HMAC_BYTES, encrypted.size)
        val expectedTag = hmacSha256(associatedData + iv + ciphertext)
        require(expectedTag.constantTimeEquals(actualTag)) {
            AppStrings.ui_tls_identity_segment_authentication_failed
        }
        return crypt(ciphertext, iv, kCCDecrypt)
    }

    private fun crypt(data: ByteArray, iv: ByteArray, operation: CCOperation): ByteArray = memScoped {
        require(iv.size == ivSize) { AppStrings.ui_tls_identity_segment_iv_size_invalid }
        val output = ByteArray(data.size + kCCBlockSizeAES128.toInt())
        val outputLength = alloc<size_tVar>()
        val input = if (data.isEmpty()) ByteArray(1) else data
        val status = input.usePinned { dataPinned ->
            encryptionKey.usePinned { keyPinned ->
                iv.usePinned { ivPinned ->
                    output.usePinned { outPinned ->
                        CCCrypt(
                            operation,
                            kCCAlgorithmAES,
                            kCCOptionPKCS7Padding,
                            keyPinned.addressOf(0),
                            encryptionKey.size.convert(),
                            ivPinned.addressOf(0),
                            dataPinned.addressOf(0),
                            data.size.convert(),
                            outPinned.addressOf(0),
                            output.size.convert(),
                            outputLength.ptr,
                        )
                    }
                }
            }
        }
        if (status != kCCSuccess) {
            throw IllegalStateException("TLS identity segment crypto failed: $status")
        }
        output.copyOf(outputLength.value.toInt())
    }

    private fun hmacSha256(data: ByteArray): ByteArray {
        val output = ByteArray(HMAC_BYTES)
        authenticationKey.usePinned { keyPinned ->
            data.usePinned { dataPinned ->
                output.usePinned { outputPinned ->
                    CCHmac(
                        kCCHmacAlgSHA256,
                        keyPinned.addressOf(0),
                        authenticationKey.size.convert(),
                        dataPinned.addressOf(0),
                        data.size.convert(),
                        outputPinned.addressOf(0),
                    )
                }
            }
        }
        return output
    }

    private fun ByteArray.constantTimeEquals(other: ByteArray): Boolean {
        var difference = size xor other.size
        val maxSize = maxOf(size, other.size)
        repeat(maxSize) { index ->
            val left = getOrNull(index)?.toInt() ?: 0
            val right = other.getOrNull(index)?.toInt() ?: 0
            difference = difference or (left xor right)
        }
        return difference == 0
    }

    private companion object {
        const val HMAC_BYTES = 32
    }
}

private class SecureTlsIdentityStorageRandom : TlsIdentityStorageRandom {
    override fun nextInt(until: Int): Int {
        require(until > 0) { "Upper bound must be positive" }
        return (arc4randomUInt() % until.toUInt()).toInt()
    }

    override fun nextBytes(size: Int): ByteArray {
        require(size >= 0) { "Size must be non-negative" }
        return ByteArray(size).also { bytes ->
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    arc4random_buf(pinned.addressOf(0), bytes.size.convert())
                }
            }
        }
    }

    private fun arc4randomUInt(): UInt {
        val bytes = nextBytes(4)
        return ((bytes[0].toUInt() and 0xFFu) shl 24) or
            ((bytes[1].toUInt() and 0xFFu) shl 16) or
            ((bytes[2].toUInt() and 0xFFu) shl 8) or
            (bytes[3].toUInt() and 0xFFu)
    }
}

private fun ByteArray.sha256(): ByteArray {
    val digest = ByteArray(32)
    if (isEmpty()) return digest
    usePinned { inputPinned ->
        digest.usePinned { digestPinned ->
            CC_SHA256(
                inputPinned.addressOf(0),
                size.convert(),
                digestPinned.addressOf(0).reinterpret()
            )
        }
    }
    return digest
}

private fun ByteArray.toHex(): String {
    return joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
    }
}

private const val FILE_NAME_CONTEXT = "FolderSpan.DeviceTlsIdentity.ActiveIdentityFile.v2"
private const val TEMPORARY_FILE_RANDOM_BYTES = 16
