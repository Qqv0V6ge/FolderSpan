## Context
文件任务原先的失败恢复能力只覆盖“失败项重试”。目录复制/删除在正常执行时会进行遍历，但遍历结果只存在内存里，因此无法跨进程、跨重启恢复“未开始执行”的剩余条目。

## Goals
- 为复制、移动、删除任务持久化完整待执行清单。
- 在任务执行中持续持久化当前游标。
- 让用户在失败或中断后对同一任务执行“继续任务”，不重新遍历。
- 对达到 `MAX_LENGTH * 30` 阈值的本地/设备大文件复制持久化分片进度，继续任务时从已确认分片恢复。
- 保留现有失败项重试能力。

## Non-Goals
- 不覆盖小于 `MAX_LENGTH * 30` 的文件级断点续传。
- 不覆盖网络盘、Share 或临时中转复制路径的文件级断点续传。
- 不重构现有 WebRTC 传输协议。
- 不引入用户可配置的运行时存储位置。

## Design
### 持久化模型
- `task snapshot`：按任务 key 保存 `Task` 实体，支持重启后恢复任务列表。
- `manifest`：记录任务类型、根来源/目标信息以及顺序化执行条目。
- `checkpoint`：记录当前阶段、当前阶段的下一个执行索引、当前条目标识/路径和更新时间。
- `transfer checkpoint`：按任务与 manifest entry 记录大文件已完成连续分片数、目标路径、文件大小和分片大小。

### 执行阶段
- 复制任务的 manifest 记录所有待创建目录与待复制文件。
- 移动任务的 manifest 先记录复制阶段，再记录源删除阶段。
- 删除任务的 manifest 记录后序删除顺序，保证目录晚于子项删除。
- 执行器按 manifest 顺序驱动单条目执行，条目级失败仍写入现有失败项存储；checkpoint 只负责“未开始的剩余条目”恢复。
- 本地/设备大文件复制条目在 `file.size >= MAX_LENGTH * 30` 时按顺序分片执行，每个分片写入成功后刷新 transfer checkpoint；小文件仍按原文件级执行。
- transfer checkpoint 与目标文件大小不匹配或目标缺失时，当前条目回退从 0 开始复制并覆盖旧 checkpoint。

### 生命周期
- 新任务开始时先构建 manifest，并创建初始 checkpoint。
- 每执行一条 manifest entry 后推进 checkpoint。
- 每个大文件 entry 执行中持续保存 transfer checkpoint；entry 成功 ack 后删除对应 transfer checkpoint。
- 成功完成：删除 manifest/checkpoint。
- 用户取消：立即删除 manifest/checkpoint。
- 失败或应用异常退出：保留 manifest/checkpoint 与任务快照。
- 重启后：将 `LOADING`/`PAUSE` 任务恢复为可继续的 `FAILURE` 状态，不自动重跑。

### UI
- 任务列表卡片、任务弹窗、任务结果页在存在可继续 checkpoint 时显示“继续任务”。
- 若任务曾经从 checkpoint 恢复执行，详情中显示“已从断点恢复继续执行”提示。
