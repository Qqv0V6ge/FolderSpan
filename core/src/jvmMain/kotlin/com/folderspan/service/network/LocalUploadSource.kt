package com.folderspan.service.network

import com.folderspan.utils.NoFollowFileChannels
import com.folderspan.utils.SensitiveFileAccessPolicy
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Paths

internal fun openLocalSourceNoFollow(path: String): FileChannel {
    return openLocalNoFollow(path, NoFollowFileChannels::openRead)
}

internal fun openLocalSinkNoFollow(path: String): FileChannel {
    return openLocalNoFollow(path) { target -> NoFollowFileChannels.openWriteTruncate(target, create = true) }
}

private fun openLocalNoFollow(
    path: String,
    open: (String) -> FileChannel,
): FileChannel {
    SensitiveFileAccessPolicy.deniedException(path)?.let { error -> throw error }
    val channel = open(path)
    try {
        val realPath = Paths.get(path).toRealPath(LinkOption.NOFOLLOW_LINKS).toString()
        SensitiveFileAccessPolicy.deniedException(realPath)?.let { error -> throw error }
        return channel
    } catch (error: Throwable) {
        runCatching { channel.close() }
        throw error
    }
}
