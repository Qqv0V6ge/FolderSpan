# Change: 为文件任务增加失败项重试

## Why
当前删除、复制、移动任务在部分条目失败后只能停留在失败状态，用户无法基于已有任务直接补执行失败的数据，只能重新发起整批任务。

## What Changes
- 为文件任务新增结构化失败项记录模型，保存可重试的源/目标/阶段信息。
- 为失败任务增加“重试失败项”入口，复用原任务对象重新执行失败条目。
- 覆盖复制、删除、移动三类任务，并区分移动任务中的“复制失败”和“删除源失败”阶段。

## Impact
- Affected specs: `manage-file-operation-tasks`
- Affected code:
  - `shared/src/commonMain/kotlin/com/folderspan/ui/state/main/TaskState.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/ui/state/file/FileState.kt`
  - `composeApp/src/commonMain/kotlin/com/folderspan/ui/components/dialog/TaskDialog.kt`
  - `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/task/TaskResultScreen.kt`
