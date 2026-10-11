package com.folderspan.ui.state.file

import strings.AppStrings

import androidx.compose.runtime.mutableStateListOf
import com.folderspan.data.file.FileSimpleInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 文件操作类型枚举类，表示不同的文件操作方式。
 */
enum class FileOperationType {
    /**
     * 表示文件操作类型中的替换操作。
     *
     * 此枚举值用于标识需要将文件内容替换为其他内容的情形。
     */
    Replace,
    /**
     * 表示一种跳转操作的枚举常量，通常用于文件操作类型相关的逻辑中。
     *
     * Jump 类型通常指代在文件中的某种跳转行为，可以结合具体业务逻辑应用于对应场景。
     */
    Jump,
    /**
     * 枚举类 FileOperationType 中的一个枚举常量，表示 AppStrings.ui_reserve 这一操作类型。
     * 通常用于表示无需修改或替换的文件操作，保持原样不变。
     */
    Reserve
}

data class FileOperation(
    val isConflict: Boolean = false,
    val src: FileSimpleInfo,
    val dest: FileSimpleInfo,
    var isUseAll: Boolean = false,
    var type: FileOperationType = FileOperationType.Replace,
) {
    fun withCopy(
        isConflict: Boolean = this.isConflict,
        src: FileSimpleInfo = this.src,
        dest: FileSimpleInfo = this.dest,
        isUseAll: Boolean = this.isUseAll,
        type: FileOperationType = this.type,
    ) = copy(
        isConflict = isConflict,
        src = src,
        dest = dest,
        isUseAll = isUseAll,
        type = type
    )
}

class FileOperationState {
    // 弹框标题
    var title: String = ""

    // 警告状态
    private val _isWarningOperationDialog: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isWarningOperationDialog: StateFlow<Boolean> = _isWarningOperationDialog
    fun updateWarningOperationDialog(value: Boolean) {
        _isWarningOperationDialog.value = value
    }

    val files = mutableStateListOf<FileOperation>()
}