package com.folderspan.service.network

import kotlinx.serialization.Serializable

@Serializable
enum class StorageAuthorizationPermission { SHARING }

@Serializable
data class StorageAuthorizationRequest(
    val protocol: String,
    val server: String = "",
    val replaceAccountId: String? = null,
    val permissions: Set<StorageAuthorizationPermission> = emptySet(),
) {
    fun validatePermissions() {
        require(permissions.isEmpty() || protocol in setOf("GoogleDrive", "Dropbox") && !replaceAccountId.isNullOrBlank()) {
            "Additional storage permissions require reauthorization of an existing OAuth account"
        }
    }

    fun hasGrantedPermissions(scopes: Set<String>): Boolean = permissions.all {
        when (protocol) {
            "GoogleDrive" -> scopes.any { scope -> scope in setOf("https://www.googleapis.com/auth/drive", "https://www.googleapis.com/auth/drive.file") }
            "Dropbox" -> scopes.containsAll(setOf("sharing.read", "sharing.write"))
            else -> false
        }
    }
}
@Serializable
data class StorageAuthorizationStart(
    val handle: String,
    val loginUrl: String,
    val userCode: String = "",
    val expiresAt: Long,
    val acceptedPermissions: Set<StorageAuthorizationPermission> = emptySet(),
)
@Serializable
data class StorageAuthorizationPoll(val status: String, val account: StorageAccount? = null)
@Serializable
data class StorageAuthorizationHandle(val handle: String)
@Serializable
data class StorageAccountHandle(val id: String)
@Serializable
data class SeafileAuthorizationRequest(val server: String, val token: String, val repoToken: Boolean = false, val libraryId: String = "", val replaceAccountId: String? = null) {
    override fun toString() = "SeafileAuthorizationRequest(repoToken=" + repoToken + ")"
}
@Serializable
data class StorageLibrary(val id: String, val name: String, val readOnly: Boolean = false)

expect object StorageAccountPlatform {
    suspend fun accounts(): List<StorageAccount>
    suspend fun begin(request: StorageAuthorizationRequest): StorageAuthorizationStart
    suspend fun poll(handle: String): StorageAuthorizationPoll
    suspend fun cancel(handle: String)
    suspend fun remove(id: String)
    suspend fun installSeafile(request: SeafileAuthorizationRequest): StorageAccount
    suspend fun libraries(id: String): List<StorageLibrary>
}
