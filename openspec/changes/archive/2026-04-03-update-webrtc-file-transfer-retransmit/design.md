## Context
`MultiPeerWebRtcTestController` 已经具备多流 striped 传输、控制通道、`ReceivedByteRangeTracker` 和最终 `TransferResult` 回执，但仍缺少应用层的缺块协商。现有上传任务进度由发送字节数推导块数，无法表达“接收端实际已确认完成的块数”。

## Goals / Non-Goals
- Goals:
  - 为文件流增加接收端确认和缺块重传。
  - 将设备上传/下载任务进度改为按“已确认块”展示。
  - 保持默认可靠无序流通道与现有准备/完成控制消息兼容。
- Non-Goals:
  - 不实现跨会话断点续传。
  - 不新增设置开关或用户可调重传参数。
  - 不改动设备 RPC 与目录复制控制协议。

## Decisions
- 扩展 `WebRtcFileStreamControlEnvelope`，新增：
  - `ChunkAck`：接收端上报已确认字节数与已确认块数。
  - `MissingRanges`：接收端返回缺失字节范围，请求发送端选择性补传。
  - `TransferCompleteCandidate`：发送端声明一轮发送结束，请接收端做最终核验。
- 仅在“带落盘目标”的文件流上启用 ACK/NACK 协议；测试页里纯内存接收仍保留现状。
- 发送端使用已有文件路径按需重读缺失范围，不缓存整文件到内存。
- 接收端继续复用 `ReceivedByteRangeTracker` 追踪唯一字节范围，并基于缺块范围计算“已确认块数”。
- 发送端在每轮数据 drain 后发送 `TransferCompleteCandidate`；若收到 `MissingRanges` 则补传并进入下一轮，直到收到 `TransferResult` 或达到重传上限。

## Risks / Trade-offs
- 应用层确认会增加控制通道消息量，但只对带落盘目标的生产文件流启用。
- 重传仍依赖同一会话与控制通道存活；若控制通道断开则直接失败。
- 进度从“发送量”改为“确认量”后，上传侧曲线会更保守，但语义更准确。

## Migration Plan
- 先扩展文件流控制模型和 helper。
- 再改造 `MultiPeerWebRtcTestController` 的发送/接收状态机。
- 最后调整 `WebRtcDeviceFileClient`、`Device.kt`、`FileState.kt` 的回调与进度文案，并补测试。

## Open Questions
- None.
