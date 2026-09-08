# Change: Update Task Runtime Metrics Display

## Why
文件操作任务当前只展示基础进度和若干 result 条目，运行中会混入排队 item，用户难以判断当前实际执行项、速度、剩余时间和并行数量。

## What Changes
- 运行中的复制/移动任务显示字节级速度和预估剩余时间。
- 删除任务显示 item 级速度和预估剩余时间。
- 任务 UI 显示当前正在执行的 item 数量。
- 任务 result 在运行态只显示正在执行的 item，不再显示固定 5 个排队/历史条目。
- 不迁移或兼容旧 runtime 指标数据；缺少新指标时显示空值或占位。

## Impact
- Affected specs: `manage-file-operation-tasks`
- Affected code: `TaskState`, file operation execution paths, task dialog/list/result UI, task runtime tests
