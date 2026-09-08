## Context
仓库覆盖 Android、iOS、JVM Desktop、JS、WasmJs，多数长时间文件任务最终会落到 `FileUtils` 或集中式复制入口。需求明确为“仅在活跃文件内容读写期间禁止设备睡眠”，不覆盖删除、遍历、重命名等元数据操作。

## Goals
- 在所有已支持平台上提供统一的防休眠入口。
- 让实际文件读写与长传输任务自动持有防休眠，不要求调用方显式管理。
- 多个并发读写共享同一平台锁，最后一个任务结束后再释放。

## Non-Goals
- 不提供用户设置开关。
- 不要求平台能力不足时向用户弹窗提示。
- 不尝试把删除或纯遍历操作纳入防休眠。

## Decisions
- 公共层新增 `FileIoWakeLock.acquire(reason)`、`FileIoWakeLockHandle.release()`、`withFileIoWakeLock*` 包装器。
- 底层 `FileUtils.readFile/readFileRange/readFileChunks/writeBytes/createFile/appendToFile` 自动持锁。
- 长时复制入口额外在任务边界持锁：本地复制、设备复制、共享复制、网络盘复制、链接分享复制。
- Android 使用 `PowerManager.PARTIAL_WAKE_LOCK`；iOS 使用 `UIApplication.idleTimerDisabled`；JVM Desktop 通过平台命令维持 inhibitor 进程；Web 使用浏览器 `Wake Lock API`。
- JVM 和浏览器实现都允许降级：命令缺失、非安全上下文或 API 不支持时只记日志。

## Risks
- JVM Desktop 平台命令受操作系统环境影响，需保证缺失命令时不抛出致命错误。
- Web Wake Lock 可能被浏览器自动释放，因此需要在页面重新可见时尝试重新请求。
- 包装器必须在异常、取消和提前结束 `Flow` collect 时正确释放，避免引用计数泄漏。
