package com.folderspan.service.http.client

import com.folderspan.service.bookmark.DeviceBookmarkClient
import com.folderspan.service.data.*
import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.ui.state.file.DrawerBookmarkType
import com.folderspan.utils.LogKit
import com.folderspan.utils.ProtoBufCodec
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import strings.AppStrings

class BookmarkRouteClient(
    private val httpClient: HttpClient,
    private val manager: HttpRouteClientManager
) : DeviceBookmarkClient {

    override suspend fun getBookmarks(): Result<List<DrawerBookmark>> {
        return getBookmarks(requestId = null, batchId = null)
    }

    suspend fun getBookmarks(
        requestId: String? = null,
        batchId: String? = null,
    ): Result<List<DrawerBookmark>> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_request_to_retrieve_the_bookmark)
            val response = httpClient.post("/api/bookmarks/list") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(EmptyRequest()), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_get_bookmark_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val list = ProtoBufCodec.decode<List<DrawerBookmark>>(bytes)
            LogKit.d(AppStrings.ui_get_bookmarked_count_arg0.format(arg0 = (list.size).toString()))
            Result.success(list)
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_get_bookmark_error_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    override suspend fun createBookmark(
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String,
    ): Result<Boolean> {
        return createBookmark(
            name = name,
            path = path,
            iconType = iconType,
            iconPath = iconPath,
            requestId = null,
            batchId = null,
        )
    }

    suspend fun createBookmark(
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String = "",
        requestId: String? = null,
        batchId: String? = null,
    ): Result<Boolean> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_create_bookmark_name_arg0_path_arg1.format(arg0 = (name), arg1 = (path)))
            val request = CreateBookmarkRequest(name, path, iconType, iconPath)
            val response = httpClient.post("/api/bookmarks") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(request), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_create_bookmark_failure_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val result = ProtoBufCodec.decode<SerializableResult>(bytes).toResult<Boolean>()

            if (result.isSuccess) {
                LogKit.d(AppStrings.ui_create_a_bookmark_successfully)
                Result.success(true)
            } else {
                LogKit.w(AppStrings.ui_create_a_bookmark_failure_arg0.format(arg0 = (result.exceptionOrNull()?.message).toString()))
                Result.failure(result.exceptionOrNull()!!)
            }
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_create_a_bookmark_error_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    override suspend fun updateBookmark(
        id: Long,
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String,
    ): Result<Boolean> {
        return updateBookmark(
            id = id,
            name = name,
            path = path,
            iconType = iconType,
            iconPath = iconPath,
            requestId = null,
            batchId = null,
        )
    }

    suspend fun updateBookmark(
        id: Long,
        name: String,
        path: String,
        iconType: DrawerBookmarkType,
        iconPath: String = "",
        requestId: String? = null,
        batchId: String? = null,
    ): Result<Boolean> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_request_to_update_bookmark_id_arg0_name_arg1_path_arg2.format(arg0 = (id).toString(), arg1 = (name), arg2 = (path)))
            val request = UpdateBookmarkRequest(id, name, path, iconType, iconPath)
            val response = httpClient.post("/api/bookmarks/update") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(request), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_update_bookmark_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val result = ProtoBufCodec.decode<SerializableResult>(bytes).toResult<Boolean>()

            if (result.isSuccess) {
                LogKit.d(AppStrings.ui_update_bookmark_successfully_id_arg0.format(arg0 = (id).toString()))
                Result.success(result.getOrThrow())
            } else {
                LogKit.w(AppStrings.ui_update_bookmark_failed_arg0.format(arg0 = (result.exceptionOrNull()?.message).toString()))
                Result.failure(result.exceptionOrNull()!!)
            }
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_update_bookmark_error_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    override suspend fun deleteBookmark(id: Long): Result<Boolean> {
        return deleteBookmark(id = id, requestId = null, batchId = null)
    }

    suspend fun deleteBookmark(
        id: Long,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<Boolean> {
        return manager.requestRegistry.trackResult(requestId, batchId) {
            try {
            LogKit.i(AppStrings.ui_request_to_delete_bookmark_id_arg0.format(arg0 = (id).toString()))
            val response = httpClient.post("/api/bookmarks/delete") {
                setFolderSpanRequestBody(ProtoBufCodec.encode(DeleteBookmarkRequest(id)), manager.encryptedHttpTransport)
            }

            if (!response.status.isSuccess()) {
                val error = readHttpResponseException(response)
                LogKit.w(AppStrings.ui_remove_bookmark_failure_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                throw error
            }

            val bytes = response.folderSpanBodyBytes()
            val result = ProtoBufCodec.decode<SerializableResult>(bytes).toResult<Boolean>()

            if (result.isSuccess) {
                LogKit.d(AppStrings.ui_deleted_bookmarks_successfully_id_arg0.format(arg0 = (id).toString()))
                Result.success(result.getOrThrow())
            } else {
                LogKit.w(AppStrings.ui_remove_bookmark_failure_arg0.format(arg0 = (result.exceptionOrNull()?.message).toString()))
                Result.failure(result.exceptionOrNull()!!)
            }
            } catch (e: Exception) {
            LogKit.e(AppStrings.ui_remove_bookmark_error_arg0.format(arg0 = (e.message).toString()), e)
            Result.failure(e)
        }
        }
    }

    override suspend fun reorderBookmarks(orderedIds: List<Long>): Result<Boolean> {
        return manager.requestRegistry.trackResult(requestId = null, batchId = null) {
            try {
                LogKit.i(AppStrings.ui_request_to_adjust_the_bookmark_order_count_arg0.format(arg0 = (orderedIds.size).toString()))
                val response = httpClient.post("/api/bookmarks/reorder") {
                    setFolderSpanRequestBody(
                        ProtoBufCodec.encode(ReorderBookmarksRequest(orderedIds)),
                        manager.encryptedHttpTransport,
                    )
                }

                if (!response.status.isSuccess()) {
                    val error = readHttpResponseException(response)
                    LogKit.w(AppStrings.ui_adjusting_bookmark_order_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                    throw error
                }

                val result = ProtoBufCodec.decode<SerializableResult>(
                    response.folderSpanBodyBytes(),
                ).toResult<Boolean>()
                if (result.isSuccess) {
                    Result.success(result.getOrThrow())
                } else {
                    Result.failure(result.exceptionOrNull()!!)
                }
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_adjusting_bookmark_order_failed_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }
    }

}
