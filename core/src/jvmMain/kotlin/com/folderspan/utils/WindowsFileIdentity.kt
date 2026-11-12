package com.folderspan.utils

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString

internal object WindowsFileIdentity {
    private val kernel32 by lazy { NativeLibrary.getInstance("kernel32") }

    fun read(path: java.nio.file.Path): String {
        // JDK's Windows provider can omit fileKey. FILE_ID_INFO combines the volume
        // serial number and a 128-bit file ID, including on ReFS:
        // https://learn.microsoft.com/windows/win32/api/winbase/ns-winbase-file_id_info
        // Match JDK 17 WindowsPath's normalization before adding a long-path prefix.
        val pathText = path.toString().let { original ->
            if (original.length <= 247 || original.startsWith("\\\\?\\")) original else {
                Memory(32768L * 2).use { buffer ->
                    val length = kernel32.getFunction("GetFullPathNameW", Function.ALT_CONVENTION)
                        .invokeInt(arrayOf(WString(original), 32768, buffer, null))
                    check(length in 1 until 32768) { "ownership_unverified" }
                    buffer.getWideString(0)
                }
            }
        }
        val nativePath = pathText.let {
            when {
                // Match JDK 17 WindowsPath: adding \\?\ to a short path would
                // change trailing-dot normalization relative to Java's file IO.
                it.length <= 247 -> it
                it.startsWith("\\\\?\\") -> it
                it.startsWith("\\\\") -> "\\\\?\\UNC\\" + it.drop(2)
                else -> "\\\\?\\$it"
            }
        }
        val handle = kernel32.getFunction("CreateFileW", Function.ALT_CONVENTION).invokePointer(
            arrayOf(WString(nativePath), 0x80, 0x7, null, 3, 0x02200000, null),
        )
        check(handle != null && Pointer.nativeValue(handle) != -1L) { "ownership_unverified" }
        try {
            // OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS and FILE_FLAG_OPEN_REPARSE_POINT
            // open files/directories without following a leaf reparse point.
            return Memory(24).use { info ->
                check(kernel32.getFunction("GetFileInformationByHandleEx", Function.ALT_CONVENTION)
                    .invokeInt(arrayOf(handle, 18, info, 24)) != 0) { "ownership_unverified" }
                val fileId = info.getByteArray(8, 16)
                check(fileId.any { it != 0.toByte() }) { "ownership_unverified" }
                "windows:${java.lang.Long.toUnsignedString(info.getLong(0), 16)}:" +
                    fileId.joinToString("") { "%02x".format(it) }
            }
        } finally {
            kernel32.getFunction("CloseHandle", Function.ALT_CONVENTION).invokeInt(arrayOf(handle))
        }
    }
}
