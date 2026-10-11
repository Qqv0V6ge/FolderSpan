package com.folderspan.service.network

import com.folderspan.exception.AuthorityException
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileDirectoryQueryableInformation
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.paths.PathResolver
import com.hierynomus.smbj.share.Directory
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import java.util.EnumSet
import strings.AppStrings

// 复用原会话的 TreeConnect，由 SMBClient 统一关闭；不跟随库的链接重定向。
internal class NoFollowSmbDiskShare(original: DiskShare) :
    DiskShare(original.smbPath, original.treeConnect, PathResolver.LOCAL) {

    fun checkedPath(path: String, allowMissingLeaf: Boolean = false): String {
        val normalized = path.replace('/', '\\').trim('\\')
        requireSafeNetworkWritePath(normalized)
        val components = normalized.split('\\').filter { it.isNotEmpty() }
        val prefixes = listOf("") + components.indices.map { index -> components.take(index + 1).joinToString("\\") }
        for ((index, prefix) in prefixes.withIndex()) {
            val isLeaf = index == prefixes.lastIndex
            val attributes = try {
                super.open(prefix, setOf(AccessMask.FILE_READ_ATTRIBUTES), null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN, setOf(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)).use { entry ->
                    entry.getFileInformation(FileBasicInformation::class.java).fileAttributes
                }
            } catch (error: SMBApiException) {
                if (error.status == NtStatus.STATUS_STOPPED_ON_SYMLINK) throw AuthorityException(AppStrings.ui_remote_path_invalid)
                if (allowMissingLeaf && isLeaf && error.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND) return normalized
                throw error
            }
            if (attributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L ||
                !isLeaf && attributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value == 0L) {
                throw AuthorityException(AppStrings.ui_remote_path_invalid)
            }
        }
        return normalized
    }

    override fun open(
        path: String,
        accessMask: Set<AccessMask>,
        attributes: Set<FileAttributes>?,
        shareAccesses: Set<SMB2ShareAccess>,
        createDisposition: SMB2CreateDisposition,
        createOptions: Set<SMB2CreateOptions>?,
    ): DiskEntry = super.open(
        checkedPath(path, allowsMissingLeaf(createDisposition)), accessMask, attributes, shareAccesses,
        createDisposition, createOptions.orEmpty() + SMB2CreateOptions.FILE_OPEN_REPARSE_POINT,
    )

    // 支持目录租约时，smbj 的 openDirectory 不经过 open，必须单独覆盖。
    override fun openDirectory(
        path: String,
        accessMask: Set<AccessMask>,
        attributes: Set<FileAttributes>?,
        shareAccesses: Set<SMB2ShareAccess>,
        createDisposition: SMB2CreateDisposition,
        createOptions: Set<SMB2CreateOptions>?,
    ): Directory = super.openDirectory(
        checkedPath(path, allowsMissingLeaf(createDisposition)), accessMask, attributes, shareAccesses,
        createDisposition, createOptions.orEmpty() + SMB2CreateOptions.FILE_OPEN_REPARSE_POINT,
    )

    // 所有列举重载最终到这里；缓存命中可能不打开新句柄，也必须先检查请求路径。
    override fun <I : FileDirectoryQueryableInformation> list(
        path: String,
        informationClass: Class<I>,
        searchPattern: String?,
        accessMask: EnumSet<AccessMask>?,
    ): List<I> = super.list(checkedPath(path), informationClass, searchPattern, accessMask)

    private fun allowsMissingLeaf(disposition: SMB2CreateDisposition): Boolean = disposition in setOf(
        SMB2CreateDisposition.FILE_CREATE, SMB2CreateDisposition.FILE_OPEN_IF,
        SMB2CreateDisposition.FILE_OVERWRITE_IF, SMB2CreateDisposition.FILE_SUPERSEDE,
    )
}
