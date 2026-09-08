package com.folderspan.pro.domain.model

enum class AppUpdateChannel(val token: String) {
    Release("release"),
    Beta("beta");

    companion object {
        fun fromToken(value: String?): AppUpdateChannel =
            entries.firstOrNull { channel ->
                channel.token.equals(value?.trim(), ignoreCase = true)
            } ?: Release
    }
}

data class AppUpdate(
    val id: Long,
    val title: String,
    val content: String,
    val version: String,
    val platform: String,
    val link: String,
    val publishedAtEpochMillis: Long,
    val channel: String = AppUpdateChannel.Release.token,
)

enum class AppUpdateStatus {
    Idle,
    Checking,
    UpToDate,
    Newer,
    Error,
}

data class AppUpdateSnapshot(
    val status: AppUpdateStatus = AppUpdateStatus.Idle,
    val update: AppUpdate? = null,
    val errorMessage: String? = null,
)

data class AppUpdatePage(
    val items: List<AppUpdate> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
)

data class AppUpdateHistorySnapshot(
    val items: List<AppUpdate> = emptyList(),
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val errorMessage: String? = null,
)
