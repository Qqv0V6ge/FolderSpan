package com.folderspan.data.file

import com.folderspan.data.main.device.DeviceType
import com.folderspan.ui.state.file.FileShareStatus

/**
 * 表示文件分享历史记录的数据类
 * 包含文件信息、设备信息和传输状态
 */
data class FileShareHistory(
    // 历史记录基本信息
    val id: Long,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val isDirectory: Boolean,
    val isOutgoing: Boolean,
    val timestamp: Long,
    val status: FileShareStatus,
    val errorMessage: String?,
    val savePath: String?,

    // 源设备信息
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val sourceDeviceType: DeviceType,

    // 目标设备信息
    val targetDeviceId: String,
    val targetDeviceName: String,
    val targetDeviceType: DeviceType
)
