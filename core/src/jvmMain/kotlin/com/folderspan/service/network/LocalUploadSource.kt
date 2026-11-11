package com.folderspan.service.network

import com.folderspan.utils.NoFollowFileChannels
import com.folderspan.utils.nativeSensitivePathDeniedException
import com.folderspan.ui.state.main.LocalTaskArtifacts
import com.folderspan.ui.state.main.TaskUploadSourceIdentity
import kotlinx.coroutines.currentCoroutineContext
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Paths

internal suspend fun openLocalSourceNoFollow(path: String): FileChannel {
    return openLocalNoFollow(path, writing = false)
}

internal suspend fun openLocalSinkNoFollow(path: String): FileChannel {
    return openLocalNoFollow(path, writing = true)
}

private suspend fun openLocalNoFollow(
    path: String,
    writing: Boolean,
): FileChannel {
    val stage = currentCoroutineContext()[TaskUploadSourceIdentity]?.takeIf {
        it.privateStagePath == path && it.privateStageIdentity.isNotEmpty()
    }
    check(!writing || stage == null || stage.privateStageWritable) { "Private upload source is read-only" }
    suspend fun validate(target: String) {
        if (stage == null) nativeSensitivePathDeniedException(target)?.let { throw it }
        else {
            val identity = stage.privateStageTaskKey?.let { (stage.privateStageOperations ?: StorageContentPlatform).stageIdentity(it, target).getOrThrow() }
                ?: LocalTaskArtifacts.describe(target, cacheArtifact = true)?.identity
            check(identity == stage.privateStageIdentity) { "Private staging ownership changed" }
        }
    }
    validate(path)
    val channel = when {
        !writing -> NoFollowFileChannels.openRead(path)
        stage != null -> NoFollowFileChannels.openReadWrite(path, create = false)
        else -> NoFollowFileChannels.openWriteTruncate(path, create = true)
    }
    try {
        val realPath = Paths.get(path).toRealPath(LinkOption.NOFOLLOW_LINKS).toString()
        validate(realPath)
        if (writing && stage != null) channel.truncate(0)
        return channel
    } catch (error: Throwable) {
        runCatching { channel.close() }
        throw error
    }
}
