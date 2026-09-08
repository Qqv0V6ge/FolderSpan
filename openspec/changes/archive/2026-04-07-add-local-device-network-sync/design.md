## Context
需要在现有文件管理体系中新增“同步”能力，让 `Local`、`Device`、`Network` 可以互相同步。
本需求明确仅新增两个页面（新增同步、同步管理），并以任务化方式执行。

## Goals / Non-Goals
- Goals:
  - 支持三端互相同步（6 个跨类型方向 + `Network -> Network`）
  - 支持手动/定时触发、冲突策略、失败重试、运行可观测
  - 页面控制面收敛到两个页面：新增同步 + 同步管理
- Non-Goals:
  - 单任务双向同步（用两条任务表示双向）
  - 目标端镜像删除（删除目标多余文件）
  - 零中转的 Device 与 Network 直连加速

## Decisions
- 同步任务采用单向模型：`source -> target`。
- 任务执行语义为增量复制，不删除源端。
- 对 `Device <-> Network` 与 `Network -> Network` 使用本机中转：
  - 从来源端读取到本机临时目录
  - 再写入目标端
  - 中转文件生命周期由运行上下文管理并在结束后清理
- 当来源与目标为同一端点时，禁止把目标路径配置在来源目录内部，避免递归复制与目录套娃。
- 冲突策略统一为：`REPLACE`、`SKIP`、`RENAME`。
- 同一任务在任意时刻仅允许一个活动运行实例（任务级互斥锁）。

## Domain Model
- SyncTask
  - id, name, enabled
  - sourceType/sourceRef/sourcePath
  - targetType/targetRef/targetPath
  - includeSubdirectories, includeEmptyDirectories, filters
  - conflictPolicy, retryLimit, timeoutSeconds, concurrency
  - scheduleType(schedule interval/cron), createdAt, updatedAt
- SyncRun
  - runId, taskId, triggerType(manual/schedule), status
  - startAt, endAt, totalCount, successCount, failCount
  - bytesTotal, bytesDone, errorSummary
- SyncRunItem
  - runId, relativePath, action(copy/mkdir/skip/conflict)
  - result(success/fail/canceled), errorCode, errorMessage
  - bytes, durationMs

## Execution Flow
1. 调度器触发（手动/定时）。
2. 执行前检查（连接、权限、凭据、路径合法性）。
3. Planner 扫描并生成待执行清单（目录+文件）。
4. Executor 按并发配置执行，逐项写入运行明细。
5. 运行结束汇总统计并发布完成事件/通知。

## Error Handling
- 单文件失败不终止整任务；最终状态可为 `PARTIAL_SUCCESS`。
- 可取消任务：停止新项调度，执行中项尽量安全收尾并标记。
- 失败项支持按运行记录重试。

## Security
- 凭据仅引用既有安全存储，不在同步任务中冗余保存。
- 日志与错误信息需脱敏（密码/token/header secret 不可明文输出）。

## Risks / Trade-offs
- 本机中转实现简单但会增加 IO 与磁盘占用。
- 跨协议时间戳/元数据一致性存在平台差异，MVP 以内容正确为优先。
- 大目录扫描耗时需通过分页/批处理与进度提示缓解。

## Open Questions
- 定时表达式是否统一采用 cron，或保留“固定间隔 + cron”双模式。
- 失败项重试粒度是“仅失败文件”还是“从失败目录重新扫描”。
