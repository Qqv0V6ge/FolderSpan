package com.folderspan.routes

import com.folderspan.exception.AuthorityException
import com.folderspan.exception.EmptyDataException
import com.folderspan.service.data.*
import strings.AppStrings

internal suspend fun RawHttpApiDispatcher.handlePath(request: RawHttpRequest): RawHttpResponse {
    requireProtobuf(request)?.let { return it }
    requireAuth(request)?.let { return it }
    val token = request.authToken().orEmpty()
    return when (request.path) {
        "/api/paths/list" -> {
            withTransferStatus {
                val body = request.decodeProtobuf<ListRequest>()
                val result = pathService.list(token, body)
                val statusHeaders = transferStatusHeaders()
                if (result.isSuccess) {
                    protobuf(result.toSerializableResult(), extraHeaders = statusHeaders)
                } else {
                    pathFailure(result, statusHeaders)
                }
            }
        }

        "/api/paths/rootPaths" -> {
            request.decodeProtobuf<EmptyRequest>()
            val result = pathService.rootPaths(token)
            if (result.isSuccess) {
                protobuf(result.toSerializableResult())
            } else {
                protobufFailure(401, result.exceptionOrNull() ?: AuthorityException(AppStrings.ui_auth_token_invalid))
            }
        }

        "/api/paths/exists" -> booleanPath(pathService.exists(token, request.decodeProtobuf<PathExistsRequest>()))
        "/api/paths/create-directory" -> booleanPath(pathService.createDirectory(token, request.decodeProtobuf<CreateDirectoryRequest>()))
        "/api/paths/delete-directory" -> booleanPath(pathService.deleteDirectory(token, request.decodeProtobuf<DeleteDirectoryRequest>()))
        else -> protobufFailure(404, EmptyDataException())
    }
}

internal fun RawHttpApiDispatcher.booleanPath(result: Result<Boolean>): RawHttpResponse {
    return if (result.isSuccess) {
        protobuf(result.toSerializableResult())
    } else {
        pathFailure(result)
    }
}

internal fun pathFailure(
    result: Result<*>,
    extraHeaders: Map<String, String> = emptyMap(),
): RawHttpResponse {
    return when (val error = result.exceptionOrNull()) {
        is AuthorityException -> protobufFailure(403, error, extraHeaders)
        is IllegalArgumentException -> protobufFailure(400, error, extraHeaders)
        else -> protobufFailure(500, error ?: Exception(AppStrings.ui_path_operation_failed), extraHeaders)
    }
}

internal suspend fun RawHttpApiDispatcher.handleBookmark(request: RawHttpRequest): RawHttpResponse {
    requireProtobuf(request)?.let { return it }
    requireAuth(request)?.let { return it }
    val token = request.authToken().orEmpty()
    return when (request.path) {
        "/api/bookmarks/list" -> {
            request.decodeProtobuf<EmptyRequest>()
            val result = bookmarkService.getBookmarks(token)
            if (result.isSuccess) protobuf(result.getOrDefault(emptyList())) else bookmarkFailure(result)
        }

        "/api/bookmarks" -> {
            val result = bookmarkService.createBookmark(token, request.decodeProtobuf<CreateBookmarkRequest>())
            bookmarkMutation(result)
        }

        "/api/bookmarks/update" -> {
            val result = bookmarkService.updateBookmark(token, request.decodeProtobuf<UpdateBookmarkRequest>())
            bookmarkMutation(result)
        }

        "/api/bookmarks/delete" -> {
            val result = bookmarkService.deleteBookmark(token, request.decodeProtobuf<DeleteBookmarkRequest>())
            bookmarkMutation(result)
        }

        "/api/bookmarks/reorder" -> {
            val result = bookmarkService.reorderBookmarks(token, request.decodeProtobuf<ReorderBookmarksRequest>())
            bookmarkMutation(result)
        }

        else -> protobufFailure(404, EmptyDataException())
    }
}

internal fun bookmarkMutation(result: Result<Boolean>): RawHttpResponse {
    return if (result.isSuccess) protobuf(result.toSerializableResult()) else bookmarkFailure(result)
}

internal fun bookmarkFailure(result: Result<*>): RawHttpResponse {
    return when (val error = result.exceptionOrNull()) {
        is AuthorityException -> protobufFailure(403, error)
        else -> protobufFailure(500, error ?: Exception(AppStrings.ui_bookmark_operation_failed))
    }
}
