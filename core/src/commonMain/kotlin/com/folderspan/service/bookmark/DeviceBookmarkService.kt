package com.folderspan.service.bookmark

import com.folderspan.exception.AuthorityException
import com.folderspan.service.data.CreateBookmarkRequest
import com.folderspan.service.data.DeleteBookmarkRequest
import com.folderspan.service.data.ReorderBookmarksRequest
import com.folderspan.service.data.UpdateBookmarkRequest
import com.folderspan.ui.state.device.DeviceCertificateState
import com.folderspan.ui.state.file.DrawerBookmark
import com.folderspan.utils.BookmarkManager
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.LogKit
import strings.AppStrings

class DeviceBookmarkService(
    private val deviceCertificateState: DeviceCertificateState,
    private val onBookmarksChanged: (suspend () -> Unit)? = null,
) {
    suspend fun getBookmarks(authToken: String): Result<List<DrawerBookmark>> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) {
            return Result.failure(authResult.exceptionOrNull() ?: AuthorityException(AppStrings.ui_auth_token_invalid))
        }
        val denied = deviceCertificateState.checkPermission(
            FileAccessPermission.Allowed,
            authToken,
            "bookmark",
            "read",
        )
        if (denied) {
            return Result.failure(AuthorityException(AppStrings.ui_no_access_rights))
        }
        return runCatching { BookmarkManager.getBookmarks() }.onFailure { error ->
            LogKit.e(AppStrings.ui_get_bookmark_failed_arg0.format(arg0 = (error.message).toString()), error)
        }
    }

    suspend fun createBookmark(authToken: String, request: CreateBookmarkRequest): Result<Boolean> {
        return executeMutation(authToken, "write", AppStrings.ui_no_permission_to_create_bookmarks) {
            BookmarkManager.createBookmark(
                request.name,
                request.path,
                request.iconType,
                request.iconPath
            )
        }
    }

    suspend fun updateBookmark(authToken: String, request: UpdateBookmarkRequest): Result<Boolean> {
        return executeMutation(authToken, "rename", AppStrings.ui_no_permission_to_update_bookmarks) {
            BookmarkManager.updateBookmark(
                request.id,
                request.name,
                request.path,
                request.iconType,
                request.iconPath
            )
        }
    }

    suspend fun deleteBookmark(authToken: String, request: DeleteBookmarkRequest): Result<Boolean> {
        return executeMutation(authToken, "write", AppStrings.ui_no_permission_to_delete_bookmarks) {
            BookmarkManager.deleteBookmark(request.id)
        }
    }

    suspend fun reorderBookmarks(authToken: String, request: ReorderBookmarksRequest): Result<Boolean> {
        return executeMutation(authToken, "write", AppStrings.ui_no_permission_to_adjust_the_bookmark_order) {
            val currentBookmarks = BookmarkManager.getBookmarks()
            val currentById = currentBookmarks.associateBy { bookmark -> bookmark.id }
            val orderedIds = request.orderedIds
            if (
                orderedIds.size != currentBookmarks.size ||
                orderedIds.distinct().size != orderedIds.size ||
                orderedIds.toSet() != currentById.keys
            ) {
                return@executeMutation Result.failure(
                    IllegalArgumentException("orderedIds must contain every bookmark id exactly once"),
                )
            }
            BookmarkManager.updateSort(orderedIds.map { id -> currentById.getValue(id) })
        }
    }

    private suspend fun executeMutation(
        authToken: String,
        permission: String,
        forbiddenMessage: String,
        block: () -> Result<Boolean>,
    ): Result<Boolean> {
        val authResult = ensureAuthorized(authToken)
        if (authResult.isFailure) {
            return Result.failure(authResult.exceptionOrNull() ?: AuthorityException(AppStrings.ui_auth_token_invalid))
        }
        val denied = deviceCertificateState.checkPermission(
            FileAccessPermission.Allowed,
            authToken,
            "bookmark",
            permission,
        )
        if (denied) {
            return Result.failure(AuthorityException(forbiddenMessage))
        }

        val result = runCatching { block() }.getOrElse { error ->
            LogKit.e(AppStrings.ui_bookmarking_failed_permission_arg0_msg_arg1.format(arg0 = (permission), arg1 = (error.message).toString()), error)
            return Result.failure(error)
        }
        if (result.isSuccess) {
            onBookmarksChanged?.invoke()
        }
        return result
    }

    private fun ensureAuthorized(authToken: String): Result<Unit> {
        if (authToken.isBlank()) {
            return Result.failure(AuthorityException(AppStrings.ui_auth_token_invalid))
        }
        if (!deviceCertificateState.isTokenValid(authToken)) {
            return Result.failure(AuthorityException(AppStrings.ui_auth_token_invalid))
        }
        return Result.success(Unit)
    }
}
