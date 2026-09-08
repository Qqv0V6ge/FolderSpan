package com.folderspan.utils

import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import kotlinx.coroutines.flow.Flow

/**
 * 文件工具类，用于处理文件操作的多平台实现。
 */
expect object FileUtils {
    /**
     * 根据指定的文件路径获取文件的基本信息。
     *
     * @param path 文件的完整路径。
     * @return 包含文件基本信息的 FileSimpleInfo 对象。
     */
    fun getFile(permission: FileAccessPermission, path: String): Result<FileSimpleInfo>

    /**
     * 根据指定路径和文件名获取文件的基本信息。
     *
     * @param path 文件所在的路径。
     * @param fileName 文件的名称。
     * @return 获取的文件基本信息，包含文件名、描述、是否为目录、是否隐藏、路径等信息。
     */
    fun getFile(permission: FileAccessPermission, path: String, fileName: String): Result<FileSimpleInfo>

    /**
     * 获取指定路径的完整文件信息（包含权限/用户/用户组等）。
     */
    fun getFileInfo(permission: FileAccessPermission, path: String): Result<FileInfo>

    /**
     * 打开指定路径的文件。
     *
     * @param file 指定需要打开的文件路径，以字符串形式传入。
     */
    fun openFile(permission: FileAccessPermission, file: String)

    /**
     * 删除指定路径的文件。
     *
     * @param path 文件的绝对路径。
     * @return 如果删除成功，返回Result封装的布尔值`true`；如果删除失败，返回Result封装的布尔值`false`以及相应的错误信息。
     */
    fun deleteFile(permission: FileAccessPermission, path: String): Result<Boolean>

    /**
     * 计算指定路径的总空间大小。
     *
     * @param path 要计算总空间的文件路径。
     * @return 指定路径的总空间大小，以字节为单位。
     */
    fun totalSpace(permission: FileAccessPermission, path: String): Long

    /**
     * 获取指定路径下的剩余存储空间大小。
     *
     * @param path 要查询的文件路径，表示需要获取剩余存储空间的目录或文件所在的路径。
     * @return 剩余存储空间的字节数，如果查询失败可能返回负值。
     */
    fun freeSpace(permission: FileAccessPermission, path: String): Long

    /**
     * 创建一个新的文件夹。
     *
     * @param path 指定新文件夹的路径。
     * @return 返回一个包含操作结果的Result对象。如果文件夹创建成功，返回Result.success(true)，
     * 如果创建失败，返回Result.failure或Result.success(false)。
     */
    fun createFolder(permission: FileAccessPermission, path: String): Result<Boolean>
    /**
     * 重命名指定路径下的文件或文件夹。
     *
     * @param path 要操作的目标路径。
     * @param oldName 需要重命名的文件或文件夹的当前名称。
     * @param newName 重命名后的新名称。
     * @return 包含操作结果的 [Result]，如果重命名成功返回 true，否则返回 false。
     */
    fun rename(permission: FileAccessPermission, path: String, oldName: String, newName: String): Result<Boolean>

    /**
     * 读取指定路径的文件内容。
     *
     * @param path 文件的路径。
     * @return 返回包含文件内容的结果，如果发生错误，则返回失败结果。
     */
    fun readFile(permission: FileAccessPermission, path: String): Result<ByteArray>

    /**
     * 从文件的指定范围读取数据。
     *
     * @param path 文件的路径。
     * @param start 要读取的起始位置（字节偏移量）。
     * @param end 要读取的结束位置（字节偏移量）。
     * @return 如果成功，返回包含所读取字节数据的结果；否则，返回错误信息的结果。
     */
    fun readFileRange(permission: FileAccessPermission, path: String, start: Long, end: Long): Result<ByteArray>


    /**
     * 按指定的块大小分块读取文件，返回一个 Flow，每次发射一个块的数据。
     *
     * @param path 要读取的文件路径。
     * @param chunkSize 每个块的大小（以字节为单位）。
     * @return Flow，每次发射一个 Result<Pair<块索引, 字节数组>>。
     */
    fun readFileChunks(
        permission: FileAccessPermission,
        path: String,
        chunkSize: Long,
    ): Flow<Result<Pair<Long, ByteArray>>>

    /**
     * 从指定字节范围内顺序读取子块。
     *
     * @return Flow，每次发射一个 Result<Pair<字节偏移量, 字节数组>>。
     */
    fun readFileRangeChunks(
        permission: FileAccessPermission,
        path: String,
        start: Long,
        end: Long,
        chunkSize: Long,
    ): Flow<Result<Pair<Long, ByteArray>>>

    /**
     * 将字节数据写入到指定路径的文件中。
     *
     * @param path 文件存储路径。
     * @param fileSize 文件的大小，用于验证存储是否合法。
     * @param data 要写入的字节数组。
     * @param offset 数据写入的偏移量。
     * @return 如果写入成功，返回包含 true 的 Result；否则返回包含 false 或错误信息的 Result。
     */
    suspend fun writeBytes(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        data: ByteArray,
        offset: Long,
    ): Result<Boolean>

    /**
     * 批量写入多个字节范围。平台实现应尽量在单次调用内复用同一个文件句柄。
     */
    suspend fun writeByteRanges(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        ranges: Flow<Pair<Long, ByteArray>>,
        onRangeWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean>

    /**
     * 将连续字节流直接写入文件的指定范围。读取方复用调用方提供的 buffer，避免为每个网络子块创建中间 ByteArray。
     */
    suspend fun writeByteStream(
        permission: FileAccessPermission,
        path: String,
        fileSize: Long,
        startOffset: Long,
        expectedBytes: Long,
        bufferSize: Int,
        readNext: suspend (buffer: ByteArray, length: Int) -> Int,
        onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
    ): Result<Boolean>

    /**
     * 创建一个指定路径的文件。
     *
     * @param path 文件的路径，表示需要创建文件的位置。
     * @return 如果文件创建成功返回 Result 包含 true，否则返回 Result 包含 false 并附带失败的错误信息。
     */
    fun createFile(permission: FileAccessPermission, path: String): Result<Boolean>

    /**
     * 读取文件的所有行。
     *
     * @param path 文件的路径。
     * @return 文件内容的行列表。
     */
    fun readFileLines(permission: FileAccessPermission, path: String): List<String>

    /**
     * 向文件追加内容。
     *
     * @param path 文件的路径。
     * @param content 要追加的内容。
     */
    fun appendToFile(permission: FileAccessPermission, path: String, content: String)
}
