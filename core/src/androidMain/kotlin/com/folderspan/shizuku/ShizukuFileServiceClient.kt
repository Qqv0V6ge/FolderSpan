package com.folderspan.shizuku

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.privileged.PrivilegedFileClient
import kotlinx.serialization.json.Json
import strings.AppStrings

/**
 * 轻量级 Binder 客户端，将高层文件操作转发到远端 Shizuku 服务，并把 Parcel 载荷还原为类型安全的 [Result]。
 */
class ShizukuFileServiceClient(
    private val binder: IBinder,
    override val backendName: String = "shizuku"
) : PrivilegedFileClient {

    private val json = Json { ignoreUnknownKeys = true }

    override fun list(path: String): Result<List<FileSimpleInfo>> {
        return transact(
            code = ShizukuTransactions.TRANSACTION_LIST_CHILDREN,
            prepare = { writeString(path) },
            read = {
                val payload = readString().orEmpty()
                Result.success(json.decodeFromString(payload))
            }
        )
    }

    override fun getFile(path: String): Result<FileSimpleInfo> = transact(
        code = ShizukuTransactions.TRANSACTION_GET_FILE,
        prepare = { writeString(path) },
        read = {
            val payload = readString().orEmpty()
            Result.success(json.decodeFromString(payload))
        }
    )

    override fun getFile(directory: String, fileName: String): Result<FileSimpleInfo> = transact(
        code = ShizukuTransactions.TRANSACTION_GET_FILE_IN_DIRECTORY,
        prepare = {
            writeString(directory)
            writeString(fileName)
        },
        read = {
            val payload = readString().orEmpty()
            Result.success(json.decodeFromString(payload))
        }
    )

    override fun getFileInfo(path: String): Result<FileInfo> = transact(
        code = ShizukuTransactions.TRANSACTION_GET_FILE_INFO,
        prepare = { writeString(path) },
        read = {
            val payload = readString().orEmpty()
            Result.success(json.decodeFromString(payload))
        }
    )

    override fun delete(path: String): Result<Boolean> = transact(
        code = ShizukuTransactions.TRANSACTION_DELETE_PATH,
        prepare = { writeString(path) },
        read = { Result.success(readInt() != 0) }
    )

    override fun createDirectory(path: String): Result<Boolean> = transact(
        code = ShizukuTransactions.TRANSACTION_CREATE_DIRECTORY,
        prepare = { writeString(path) },
        read = { Result.success(readInt() != 0) }
    )

    override fun createFile(path: String): Result<Boolean> = transact(
        code = ShizukuTransactions.TRANSACTION_CREATE_FILE,
        prepare = { writeString(path) },
        read = { Result.success(readInt() != 0) }
    )

    override fun rename(path: String, oldName: String, newName: String): Result<Boolean> = transact(
        code = ShizukuTransactions.TRANSACTION_RENAME,
        prepare = {
            writeString(path)
            writeString(oldName)
            writeString(newName)
        },
        read = { Result.success(readInt() != 0) }
    )

    override fun totalSpace(path: String): Result<Long> = transact(
        code = ShizukuTransactions.TRANSACTION_TOTAL_SPACE,
        prepare = { writeString(path) },
        read = { Result.success(readLong()) }
    )

    override fun freeSpace(path: String): Result<Long> = transact(
        code = ShizukuTransactions.TRANSACTION_FREE_SPACE,
        prepare = { writeString(path) },
        read = { Result.success(readLong()) }
    )

    override fun exists(path: String): Result<Boolean> = transact(
        code = ShizukuTransactions.TRANSACTION_EXISTS,
        prepare = { writeString(path) },
        read = { Result.success(readInt() != 0) }
    )

    override fun deleteDirectory(path: String): Result<Boolean> = transact(
        code = ShizukuTransactions.TRANSACTION_DELETE_DIRECTORY,
        prepare = { writeString(path) },
        read = { Result.success(readInt() != 0) }
    )

    override fun openFile(path: String, mode: Int): Result<ParcelFileDescriptor> = transact(
        code = ShizukuTransactions.TRANSACTION_OPEN_FILE_DESCRIPTOR,
        prepare = {
            writeString(path)
            writeInt(mode)
        },
        read = {
            @Suppress("DEPRECATION")
            val descriptor = readParcelable<ParcelFileDescriptor>(ParcelFileDescriptor::class.java.classLoader)
            if (descriptor != null) {
                Result.success(descriptor)
            } else {
                Result.failure(IllegalStateException(AppStrings.ui_file_descriptor_not_available))
            }
        }
    )

    /**
     * 封装与远端进程交互时重复的 Parcel 模板代码，并保证远端异常依旧通过 [Result] 反馈给调用方。
     */
    private inline fun <T> transact(
        code: Int,
        prepare: Parcel.() -> Unit,
        read: Parcel.() -> Result<T>
    ): Result<T> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuTransactions.DESCRIPTOR)
            data.prepare()
            binder.transact(code, data, reply, 0)
            reply.readException()
            read(reply)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
