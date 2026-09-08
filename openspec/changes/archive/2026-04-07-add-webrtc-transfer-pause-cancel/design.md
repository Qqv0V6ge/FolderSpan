## Context
当前仓库里有两类 WebRTC 文件传输：

1. 远端复制 RPC：通过 `copyPath/controlCopy` 驱动，已经有 `Pause/Resume/Cancel` 控制。
2. payload-channel 文件流：通过 `uploadLocalFileToPeer` / `downloadPeerFileToLocal` 直接传输文件内容，用于大文件直传和部分本机中转路径。

用户现在遇到的问题集中在第二类。任务 UI 虽然允许暂停或取消，但大文件 WebRTC 文件流不会响应这些信号，因为控制器内部只有 `InProgress/Completed/Failed` 语义，也没有把单个传输暴露成可控制对象。结果是任务被标成暂停或取消后，底层传输仍可能继续读文件、继续发 payload，直到完成、断线或超时。

## Goals / Non-Goals

**Goals:**
- 为 payload-channel 文件流建立单传输级别的 `pause/resume/cancel` 控制。
- 让任务系统对 WebRTC 大文件传输的暂停/取消真正生效。
- 保持现有 peer session、信令与 payload channel 复用策略不变，暂停后可以在同一会话内继续。
- 让取消收尾明确区分“用户取消”和“普通失败”，并清理未完成目标文件。

**Non-Goals:**
- 不改造已支持控制的 `copyPath/controlCopy` RPC 复制路径。
- 不为设置页测试传输新增独立 UI 按钮。
- 不引入断点续传到新会话或跨重连恢复。
- 不改变现有房间管理、连接审批、RPC 路由模型。

## Decisions
- 为 payload-channel 文件流引入显式控制消息。
  - 在 `WebRtcFileStreamControlType` 中新增暂停、继续、取消及其结果消息。
  - 原因：下载场景下，暂停动作必须通知远端发送侧；仅在本地挂起协程无法阻止对端继续发包。
  - 备选方案：只在本地 sender loop 里检查暂停状态。放弃原因：无法覆盖“本地是接收端”的下载场景，也无法给对端明确的取消清理信号。

- 为单个文件流建立可寻址的控制句柄。
  - 调用方在启动 WebRTC 文件流时传入稳定的请求 ID；控制器内部把请求 ID 绑定到实际 `transferId`、方向和 peer。
  - 原因：任务系统需要在传输进行中异步发送暂停/继续/取消，但当前 `transferId` 是控制器内部随机生成的，调用方无法可靠引用。
  - 备选方案：让 `uploadLocalFileToPeer`/`downloadPeerFileToLocal` 返回可变句柄对象。放弃原因：会扩大 API 变更面，并让现有调用方重构成本更高。

- 暂停语义采用“协作式停发”，不是“立即冻结所有字节”。
  - sender 在收到并确认暂停后停止读取新 chunk、停止排队新 payload；已经进入 data channel 缓冲区的字节允许自然排空并继续计入进度。
  - 原因：WebRTC data channel 没有可靠的“撤回已入队 payload”能力，强行立即截断只会制造更多协议不一致。

- 取消语义优先保证一致清理，而不是复用失败路径。
  - 取消时双方都结束该传输，待接收目标文件执行清理，并向调用方返回取消类错误。
  - 原因：用户取消不是异常故障；如果沿用普通失败语义，任务层会误记为传输错误，且临时文件清理不稳定。

- 任务系统只接入 payload-channel 文件流调用点。
  - `Device.kt` 和 `FileState.kt` 中现有的大文件 WebRTC 上传/下载入口将增加控制监控，把 `TaskState` 的 pause/resume/cancel 转成控制器命令。
  - 原因：这正是当前能力缺口；RPC 复制已具备控制，重复接线只会增加复杂度。

## Risks / Trade-offs
- [暂停并非瞬时归零] 已入队 payload 仍可能继续到达，暂停后的进度可能再前进少量字节。
  - Mitigation: 明确以“停止继续排队新 payload”为暂停完成条件，并在测试里覆盖缓冲排空场景。

- [控制状态泄漏] 传输在断线、重连或异常返回时可能留下挂起的 pause/result waiter。
  - Mitigation: 与现有 `pendingFileStream*`、`clearSessionReceiveState` 一起统一清理控制句柄。

- [调用点分散] WebRTC 大文件直传入口分布在 `Device.kt` 与 `FileState.kt`，遗漏任一入口都会让能力不完整。
  - Mitigation: 用请求 ID 帮助统一控制 API，并增加面向任务调用链的共享测试。

- [协议面变宽] 文件流控制消息种类增加，后续要维护更多状态分支。
  - Mitigation: 仅覆盖 pause/resume/cancel 三类用户控制，不顺带引入新的恢复或调度语义。

## Migration Plan
- 先落地协议枚举、控制器状态和控制 API。
- 再把任务调用点改为传入稳定请求 ID，并接上暂停/取消监控。
- 最后补共享测试与 OpenSpec 校验。
- 若回滚，只需移除新增控制消息和调用点接线，旧的传输流程仍可直接运行。

## Open Questions
- 本次是否需要同步把新的暂停/取消状态暴露到 `webRtcTransfers` UI 模型中，还是先以任务链路可控为准。当前提案默认后者优先。
