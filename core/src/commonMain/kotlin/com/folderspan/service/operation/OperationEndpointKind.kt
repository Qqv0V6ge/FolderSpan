package com.folderspan.service.operation

/**
 * 文件任务并发策略使用的端点分类。
 *
 * Local、Share、Network 只使用本机运行状态做动态并发限制；Device 还会叠加远端设备状态。
 */
internal enum class TraversalEndpointKind {
    Local,
    Device,
    Share,
    Network,
}
