package com.folderspan.service.http.tls

internal object TlsIdentityStorageKeyMaterial {
    const val CONSTANT_SALT = "FolderSpan.DeviceTlsIdentity.FileStore.v1"
    const val ENTROPY_SALT_FILE = "storage.salt"

    fun build(
        deviceId: String,
        directoryPath: String,
        entropySaltHex: String,
    ): String {
        require(entropySaltHex.isNotEmpty()) { "TLS identity storage salt must not be empty" }
        return listOf(CONSTANT_SALT, deviceId, directoryPath, entropySaltHex).joinToString("|")
    }
}
