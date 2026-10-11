package com.folderspan.shizuku

import android.content.Context
import android.os.*
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.folderspan.data.file.FileInfo
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.exception.AuthorityException
import com.folderspan.extensions.toFileSimpleInfo
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import strings.AppStrings

internal fun listFileChildrenForPrivilegedService(path: String): List<FileSimpleInfo> {
    val directory = File(path)
    if (!directory.exists() && !directory.isSymbolicLinkNoFollow()) {
        throw FileNotFoundException(AppStrings.ui_directory_does_not_exist_arg0.format(arg0 = (path).toString()))
    }
    if (directory.isSymbolicLinkNoFollow() || !directory.isDirectory) {
        throw IllegalArgumentException(AppStrings.ui_the_goal_is_not_the_directory_arg0.format(arg0 = (path).toString()))
    }
    val files = directory.listFiles() ?: throw AuthorityException(AppStrings.ui_cannot_access_directory_arg0.format(arg0 = (path).toString()))
    return files.mapNotNull { file ->
        file.toFileSimpleInfo().getOrNull()
    }
}

internal fun File.isSymbolicLinkNoFollow(): Boolean = runCatching {
    OsConstants.S_ISLNK(Os.lstat(absolutePath).st_mode)
}.getOrElse {
    runCatching { java.nio.file.Files.isSymbolicLink(toPath()) }.getOrDefault(false)
}

internal fun deleteFileTreeNoFollow(file: File): Boolean {
    val pending = ArrayDeque<Pair<File, Boolean>>()
    pending.add(file to false)
    while (pending.isNotEmpty()) {
        val (current, childrenQueued) = pending.removeLast()
        if (current.isSymbolicLinkNoFollow()) {
            if (!current.delete()) return false
            continue
        }
        if (!current.exists()) continue
        if (!current.isDirectory) {
            if (!current.delete()) return false
            continue
        }
        if (childrenQueued) {
            if (!current.delete()) return false
            continue
        }
        val children = current.listFiles() ?: return false
        pending.add(current to true)
        children.forEach { child -> pending.add(child to false) }
    }
    return true
}

/**
 * Binder 端实现，获得 Shizuku 授权后代表 UI 进程执行受权限保护的文件系统操作。
 */
class ShizukuFileService : Binder(), IInterface {

    private val json = Json { ignoreUnknownKeys = true }

    init {
        attachInterface(this, ShizukuTransactions.DESCRIPTOR)
    }

    override fun asBinder(): IBinder = this

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val out = reply ?: return super.onTransact(code, data, reply, flags)
        if (!isCallerAuthorized()) {
            if (code == ShizukuTransactions.TRANSACTION_DESTROY) {
                out.writeNoException()
                out.writeInt(0)
                return true
            }
            out.writeException(SecurityException("unauthorized caller"))
            return true
        }
        return when (code) {
            ShizukuTransactions.TRANSACTION_LIST_CHILDREN -> {
                data.enforceInterface(ShizukuTransactions.DESCRIPTOR)
                val path = data.readString().orEmpty()
                out.respond(
                    writer = { result ->
                        writeString(json.encodeToString(result))
                    }
                ) {
                    listChildren(path)
                }
            }

            ShizukuTransactions.TRANSACTION_GET_FILE -> {
                data.enforceInterface(ShizukuTransactions.DESCRIPTOR)
                val path = data.readString().orEmpty()
                out.respond(
                    writer = { result ->
                        writeString(json.encodeToString(result))
                    }
                ) {
                    getFileSimple(path)
                }
            }

            ShizukuTransactions.TRANSACTION_GET_FILE_INFO -> {
                data.enforceInterface(ShizukuTransactions.DESCRIPTOR)
                val path = data.readString().orEmpty()
                out.respond(
                    writer = { info ->
                        writeString(json.encodeToString(info))
                    }
                ) {
                    getFileInfo(path)
                }
            }

            ShizukuTransactions.TRANSACTION_GET_FILE_IN_DIRECTORY -> {
                data.enforceInterface(ShizukuTransactions.DESCRIPTOR)
                val directory = data.readString().orEmpty()
                val name = data.readString().orEmpty()
                out.respond(
                    writer = { result ->
                        writeString(json.encodeToString(result))
                    }
                ) {
                    getFileInDirectory(directory, name)
                }
            }

            ShizukuTransactions.TRANSACTION_DELETE_PATH -> {
                data.enforceInterface(ShizukuTransactions.DESCRIPTOR)
                val path = data.readString().orEmpty()
                out.respond(writer = { success -> writeInt(if (success) 1 else 0) }) {
                    deletePath(path)
                }
            }

            ShizukuTransactions.TRANSACTION_CREATE_DIRECTORY -> {
                data.enforceInterface(ShizukuTransactions.DESCRIPTOR)
                val path = data.readString().orEmpty()
                out.respond(writer = { success -> writeInt(if (success) 1 else 0) }) {
                    createDirectory(path)
                }
            }

            ShizukuTransactions.TRANSACTION_CREATE_FILE -> {
                data.enforceInterface(ShizukuTransactions.DESCRIPTOR)
                val path = data.readString().orEmpty()
                out.respond(writer = { success -> writeInt(if (success) 1 else 0) }) {
                    createFile(path)
                }
            }

            ShizukuTransactions.TRANSACTION_RENAME -> {
                data.enforceInterface(ShizukuTransactions.DESCRIPTOR)
                val path = data.readString().orEmpty()
                val oldName = data.readString().orEmpty()
                val newName = data.readString().orEmpty()
                out.respond(writer = { success -> writeInt(if (success) 1 else 0) }) {
                    rename(path, oldName, newName)
                }
            }

            ShizukuTransactions.TRANSACTION_TOTAL_SPACE -> {
                data.enforceInterface(ShizukuTransactions.DESCRIPTOR)
                val path = data.readString().orEmpty()
                out.respond(writer = { value -> writeLong(value) }) {
                    totalSpace(path)
                }
            }

            ShizukuTransactions.TRANSACTION_FREE_SPACE -> {
                data.enforceInterface(ShizukuTransactions.DESCRIPTOR)
                val path = data.readString().orEmpty()
                out.respond(writer = { value -> writeLong(value) }) {
                    freeSpace(path)
                }
            }

            ShizukuTransactions.TRANSACTION_EXISTS -> {
                data.enforceInterface(ShizukuTransactions.DESCRIPTOR)
                val path = data.readString().orEmpty()
                out.respond(writer = { success -> writeInt(if (success) 1 else 0) }) {
                    exists(path)
                }
            }

            ShizukuTransactions.TRANSACTION_DELETE_DIRECTORY -> {
                data.enforceInterface(ShizukuTransactions.DESCRIPTOR)
                val path = data.readString().orEmpty()
                out.respond(writer = { success -> writeInt(if (success) 1 else 0) }) {
                    deleteDirectory(path)
                }
            }

            ShizukuTransactions.TRANSACTION_OPEN_FILE_DESCRIPTOR -> {
                data.enforceInterface(ShizukuTransactions.DESCRIPTOR)
                val path = data.readString().orEmpty()
                val mode = data.readInt()
                out.respond(writer = { descriptor ->
                    writeParcelable(descriptor, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                }) {
                    openDescriptor(path, mode)
                }
            }

            ShizukuTransactions.TRANSACTION_DESTROY -> {
                out.writeNoException()
                out.writeInt(1)
                Process.killProcess(Process.myPid())
                true
            }

            else -> super.onTransact(code, data, reply, flags)
        }
    }

    /**
     * 将结果或异常写回远端客户端，避免 Binder 因未捕获的 Throwable 直接导致调用线程崩溃。
     */
    private inline fun <T> Parcel.respond(
        writer: Parcel.(T) -> Unit,
        block: () -> T
    ): Boolean {
        return try {
            val result = block()
            writeNoException()
            writer(result)
            true
        } catch (t: Throwable) {
            Log.e("ShizukuFileService", "Operation failed: ${t.message}", t)
            writeException(t.asRemoteException())
            true
        }
    }

    private fun Throwable.asRemoteException(): Exception {
        return when (this) {
            is Exception -> this
            else -> RuntimeException(message ?: "Unknown error", this)
        }
    }

    private fun isCallerAuthorized(): Boolean {
        val callingUid = getCallingUid()
        val context = resolveApplicationContext() ?: return false
        val packages = context.packageManager.getPackagesForUid(callingUid) ?: return false
        return packages.contains(context.packageName) || packages.contains(APP_PACKAGE_NAME)
    }

    private fun resolveApplicationContext(): Context? {
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull()
    }

    private companion object {
        const val APP_PACKAGE_NAME = "com.folderspan"
    }

    private fun listChildren(path: String): List<FileSimpleInfo> {
        return listFileChildrenForPrivilegedService(path)
    }

    private fun getFileSimple(path: String): FileSimpleInfo {
        val file = File(path)
        if (!file.exists() && !file.isSymbolicLinkNoFollow()) {
            throw FileNotFoundException(AppStrings.ui_file_not_found_arg0.format(arg0 = (path).toString()))
        }
        return file.toFileSimpleInfo().getOrElse { item ->  throw item }
    }

    private fun getFileInDirectory(directory: String, name: String): FileSimpleInfo {
        val target = File(directory, name)
        if (!target.exists() && !target.isSymbolicLinkNoFollow()) {
            throw FileNotFoundException(AppStrings.ui_file_not_found_arg0.format(arg0 = (target.absolutePath).toString()))
        }
        return target.toFileSimpleInfo().getOrElse { item ->  throw item }
    }

    private fun getFileInfo(path: String): FileInfo {
        val file = File(path)
        if (!file.exists() && !file.isSymbolicLinkNoFollow()) {
            throw FileNotFoundException(AppStrings.ui_file_not_found_arg0.format(arg0 = (path).toString()))
        }
        val simple = file.toFileSimpleInfo().getOrElse { item ->  throw item }
        var mode = 0
        var uid = ""
        var gid = ""
        try {
            val stat = Os.lstat(path)
            mode = stat.st_mode and 0x1FF
            uid = stat.st_uid.toString()
            gid = stat.st_gid.toString()
        } catch (_: Throwable) {
            // Ignore detailed mode failures
        }
        return FileInfo(
            name = simple.name,
            description = simple.description,
            isDirectory = simple.isDirectory,
            isHidden = simple.isHidden,
            path = simple.path,
            mineType = simple.mineType,
            size = simple.size,
            permissions = mode,
            user = uid,
            userGroup = gid,
            createdDate = simple.createdDate,
            updatedDate = simple.updatedDate,
            protocol = simple.protocol,
            protocolId = simple.protocolId
        )
    }

    private fun deletePath(path: String): Boolean {
        val file = File(path)
        if (!file.exists() && !file.isSymbolicLinkNoFollow()) {
            throw FileNotFoundException(AppStrings.ui_file_not_found_arg0.format(arg0 = (path).toString()))
        }
        return deleteFileTreeNoFollow(file)
    }

    private fun createDirectory(path: String): Boolean {
        val file = File(path)
        return !file.isSymbolicLinkNoFollow() && if (file.exists()) file.isDirectory else file.mkdirs()
    }

    private fun createFile(path: String): Boolean {
        val file = File(path)
        if (file.isSymbolicLinkNoFollow()) return false
        if (file.exists()) {
            return file.isFile
        }
        file.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }
        return file.createNewFile()
    }

    private fun rename(path: String, oldName: String, newName: String): Boolean {
        val oldFile = File(path, oldName)
        if (!oldFile.exists()) {
            throw FileNotFoundException(AppStrings.ui_file_not_found_arg0.format(arg0 = (oldFile.absolutePath).toString()))
        }
        val newFile = File(path, newName)
        if (newFile.exists()) {
            throw IllegalStateException(AppStrings.ui_target_already_exists_arg0.format(arg0 = (newFile.absolutePath).toString()))
        }
        return oldFile.renameTo(newFile)
    }

    private fun totalSpace(path: String): Long {
        return runCatching {
            StatFs(path).totalBytes
        }.getOrElse {
            File(path).totalSpace
        }
    }

    private fun freeSpace(path: String): Long {
        return runCatching {
            StatFs(path).availableBytes
        }.getOrElse {
            File(path).usableSpace
        }
    }

    private fun exists(path: String): Boolean {
        val file = File(path)
        return file.exists() || file.isSymbolicLinkNoFollow()
    }

    private fun deleteDirectory(path: String): Boolean {
        val file = File(path)
        if (!file.exists() && !file.isSymbolicLinkNoFollow()) return true
        if (!file.isSymbolicLinkNoFollow() && !file.isDirectory) {
            throw IllegalArgumentException(AppStrings.ui_the_goal_is_not_the_directory_arg0.format(arg0 = (path).toString()))
        }
        return deleteFileTreeNoFollow(file)
    }

    private fun openDescriptor(path: String, mode: Int): ParcelFileDescriptor {
        val file = File(path)
        if (file.isSymbolicLinkNoFollow()) {
            throw AuthorityException(AppStrings.ui_not_allowed_to_open_symbolic_links_arg0.format(arg0 = (path).toString()))
        }
        if (mode and ParcelFileDescriptor.MODE_CREATE != 0) {
            if (!file.exists()) {
                file.parentFile?.let { parent ->
                    if (!parent.exists()) {
                        parent.mkdirs()
                    }
                }
                file.createNewFile()
            }
        }
        return ParcelFileDescriptor.open(file, mode)
    }
}
