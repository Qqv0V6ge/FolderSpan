# Change: add file I/O wake lock

## Why
长时间文件读取、写入、复制、上传和下载过程中，设备或桌面系统可能因进入睡眠而中断任务。当前仓库只在 Android 的后台分享服务里持有局部唤醒锁，普通文件任务缺少统一防休眠能力。

## What Changes
- 新增跨平台 `FileIoWakeLock` 抽象，在活跃文件内容读写期间自动请求防休眠。
- 将防休眠接入本地 `FileUtils` 读写入口，以及本地/设备/共享/网络盘/链接分享的长时复制传输链路。
- 平台不支持时采用 best-effort 降级，只记录日志，不中断任务。

## Impact
- Affected specs: `manage-file-io-wake-lock`
- Affected code: `shared/src/*/kotlin/com/folderspan/utils/FileIoWakeLock*`、`shared/src/*/kotlin/com/folderspan/utils/FileUtils*`、`shared/src/commonMain/kotlin/com/folderspan/data/file/FileInfo.kt`、`shared/src/commonMain/kotlin/com/folderspan/service/http/client/*`
