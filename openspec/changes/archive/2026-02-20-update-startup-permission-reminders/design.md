## Context
当前权限模型已统一到 `PlatformPermissionProvider` 与权限设置页，但历史启动逻辑仍会在多个平台直接请求权限。
本次变更目标是把“启动即弹框”改为“启动提醒 + 用户主动授权”，并复用现有通知中心与权限设置能力。

## Goals / Non-Goals
- Goals:
  - 启动阶段不直接触发系统权限弹窗。
  - 启动后对缺失权限生成提醒通知，并引导至权限设置页。
  - 权限请求保留为用户主动动作。
- Non-Goals:
  - 不新增新的权限类型。
  - 不重构权限设置页整体 UI。
  - 不改变非权限类通知的业务逻辑。

## Decisions
- Decision: 以 `PlatformPermissionProvider.permissions()` + `status()` 作为缺失权限判断来源，避免平台分叉判断。
- Decision: 权限提醒通知采用“应用内通知为主”，系统通知为可选（仅在平台能力允许时发送）。
- Decision: 提醒通知写入 `NotificationState`，并通过 metadata 标记为 `permission_reminder`，点击后统一跳转权限设置页。
- Decision: 提醒通知在单次应用会话内按“缺失权限集合”去重，避免重复刷屏。

## Flow
1. App 完成基础初始化后，执行权限状态扫描（仅检查，不请求）。
2. 若存在缺失权限，构建权限提醒通知（包含缺失权限摘要与跳转 metadata）。
3. 将提醒通知写入通知中心；平台允许时同步发系统通知。
4. 用户点击提醒后，进入权限设置页并可手动触发授权。

## Platform Notes
- Android:
  - `MainActivity` 启动阶段不再直接调用权限请求。
  - 保留 `PermissionController` 供用户主动操作路径复用。
- iOS/JS/Wasm:
  - `LocalNotifier.initialize()` 改为不在启动时申请通知权限。
  - 仅在用户操作或业务确需时触发通知权限请求。
- JVM:
  - 无权限请求行为变更，仅保持兼容。

## Risks / Trade-offs
- 改为提醒后，部分功能首次使用前可能缺权限，需要在功能入口给出明确失败提示或二次引导。
- 未授权通知权限时无法依赖系统通知，因此必须保证应用内通知可见且可达。

## Open Questions
- 权限提醒是否需要“延迟触发”（如启动后 N 秒）以降低首屏干扰；本提案先采用“启动后立即写入通知中心，不弹系统权限框”。
