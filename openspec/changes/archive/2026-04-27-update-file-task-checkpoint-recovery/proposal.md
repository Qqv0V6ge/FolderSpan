# Change: 持久化文件任务清单与检查点

## Why
现有复制、移动、删除任务在目录场景下依赖运行时重新遍历。一旦任务执行中途异常退出、进程重启或用户希望稍后继续，系统只能重新扫描目录，已经完成的条目与尚未开始的条目之间没有稳定边界。

## What Changes
- 为文件任务增加运行时持久化：任务快照、manifest 和 checkpoint。
- 复制、移动、删除任务在执行前先生成 manifest，再按 manifest 顺序执行。
- 执行过程中持续写入 checkpoint，应用重启后保留失败/中断任务并提供“继续任务”入口。
- 对 `MAX_LENGTH * 30` 及以上的本地/设备大文件复制持久化分片 checkpoint，继续任务时从已完成分片之后恢复。
- 用户主动取消或任务成功完成时清理 manifest/checkpoint。
- 保留原有“重试失败项”能力，与“继续任务”并存。

## Impact
- Affected specs: `manage-file-operation-tasks`
- Affected code:
  - `shared/src/commonMain/kotlin/com/folderspan/ui/state/main/TaskState.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/ui/state/main/TaskRuntimePersistenceStore.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/ui/state/file/FileState.kt`
  - `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/task/TaskListScreen.kt`
  - `composeApp/src/commonMain/kotlin/com/folderspan/ui/components/dialog/TaskDialog.kt`
  - `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/task/TaskResultScreen.kt`
