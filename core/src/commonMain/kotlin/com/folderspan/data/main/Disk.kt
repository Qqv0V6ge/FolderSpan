package com.folderspan.data.main

import com.folderspan.utils.PathUtils
import strings.AppStrings

data class DiskMenuPermission(
    val read: Boolean = false,
    val write: Boolean = false,
    val paste: Boolean = false,
    val copy: Boolean = false,
    val move: Boolean = false,
    val delete: Boolean = false,
    val rename: Boolean = false,
    val setting: Boolean = false,
    val favorite: Boolean = false,
    val share: Boolean = false,
    val info: Boolean = false,
) {
    fun hasAnyPermission(): Boolean {
        return read || write || paste || copy || move || delete || rename || setting || favorite || share || info
    }
}

abstract class DiskBase {
    abstract val name: String
    abstract val pathSeparator: String
    open val menuPermission: DiskMenuPermission? = null
}

data class Local(
    override val name: String = AppStrings.ui_local,
    override val pathSeparator: String = PathUtils.getPathSeparator(),
) : DiskBase() {
    override val menuPermission: DiskMenuPermission = DiskMenuPermission(
        read = true,
        write = true,
        paste = true,
        copy = true,
        move = true,
        delete = true,
        rename = true,
        setting = true,
        favorite = true,
        share = true,
        info = true,
    )

    // Equality is based solely on name
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Local) return false
        return name == other.name
    }

    override fun hashCode(): Int = name.hashCode()
}
