package com.folderspan.exception

import strings.AppStrings

/**
 * 表示设备端明确不支持在远程设备内直接复制路径。
 *
 * 仅此类失败可以触发下载到本机后再上传的回退逻辑；
 * 权限、连接和路径错误必须保持原失败。
 */
class DeviceCopyUnsupportedException(
    message: String = AppStrings.device_direct_copy_not_supported,
) : Exception(message)
