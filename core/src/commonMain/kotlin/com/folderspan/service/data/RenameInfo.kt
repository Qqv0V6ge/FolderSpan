package com.folderspan.service.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * 重命名文件或目录的信息数据类
 *
 * @property path 文件或目录的路径
 * @property oldName 原始名称
 * @property newName 新的名称
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RenameInfo(
    @ProtoNumber(1) val path: String,
    @ProtoNumber(2) val oldName: String,
    @ProtoNumber(3) val newName: String
) {
    /**
     * 校验 path、oldName、newName 是否任意为空
     *
     * @return 如果任意字段为空或空白字符串则返回 true，否则返回 false
     */
    fun hasEmptyField(): Boolean {
        return path.isBlank() || oldName.isBlank() || newName.isBlank()
    }

}
