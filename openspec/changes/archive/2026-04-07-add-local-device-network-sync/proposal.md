# Change: Add Sync between Local, Device, and Network

## Why
当前文件管理支持复制/移动等单次操作，但缺少可复用、可调度、可观测的“同步任务”能力。
用户需要在 `Local`、`Device`、`Network` 三类端点之间进行相互同步，并通过统一入口完成新增与管理。

## What Changes
- 新增“同步（Sync）”能力，采用“单任务单向同步”模型：一个任务仅描述 `A -> B`。
- 新增两个页面：
  - 新增同步（创建/编辑）
  - 同步管理（列表、运行控制、详情、日志）
- 同步端点支持 `Local`、`Device`、`Network` 三类，覆盖 6 个跨类型方向，并新增 `Network -> Network`：
  - `Local -> Device`
  - `Device -> Local`
  - `Local -> Network`
  - `Network -> Local`
  - `Device -> Network`
  - `Network -> Device`
  - `Network -> Network`
- 执行语义为增量复制（不删除源端），支持冲突策略（覆盖/跳过/重命名）、失败重试、任务取消、运行日志。
- 支持手动触发与定时触发（固定间隔或 cron）。
- 对 `Device <-> Network` 与 `Network -> Network` 路径采用“本机中转”执行模式，确保跨协议可落地。

## Impact
- Affected specs: 新增 `specs/manage-sync-tasks/spec.md`。
- Affected code (expected):
  - `composeApp`：同步新增页、管理页、任务详情 UI
  - `shared`：同步任务模型、调度与执行编排、运行记录存储
  - `server`：无强制改动（如需扩展鉴权/传输能力另提变更）
- Data:
  - 新增同步任务配置与运行记录存储（任务、运行、运行明细）
  - 复用已有 Device/Network 凭据与连接配置，不新增明文凭据存储

## Out of Scope
- 单任务内双向实时同步与复杂冲突合并。
- 默认删除目标端多余文件（镜像模式后续单独提案）。
- 断点续传与跨端直接零中转传输优化。
