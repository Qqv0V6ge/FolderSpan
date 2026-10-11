package com.folderspan.runtime

import com.folderspan.cleanup.resolveDesktopApplicationDataDirectory
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class FolderSpanInstanceLock private constructor(
    val path: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    @Volatile
    private var released = false

    override fun close() = release()

    fun release() {
        if (released) return
        released = true
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        const val FILE_NAME: String = "instance.lock"

        fun tryAcquire(
            directory: Path = resolveDesktopApplicationDataDirectory(),
        ): FolderSpanInstanceLock? {
            Files.createDirectories(directory)
            val path = directory.resolve(FILE_NAME)
            val channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                runCatching { channel.close() }
                return null
            }
            if (lock == null) {
                runCatching { channel.close() }
                return null
            }
            return FolderSpanInstanceLock(path, channel, lock)
        }
    }
}
