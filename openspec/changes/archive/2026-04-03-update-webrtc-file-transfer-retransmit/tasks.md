## 1. Implementation
- [x] 1.1 扩展 WebRTC 文件流控制模型，支持 ACK / 缺块范围 / 发送轮次。
- [x] 1.2 新增缺块范围与确认块数 helper，并补单元测试。
- [x] 1.3 改造 `MultiPeerWebRtcTestController` 发送端循环，支持 `TransferCompleteCandidate` 与选择性重传。
- [x] 1.4 改造接收端确认逻辑，按确认块数上报进度并返回缺块范围。
- [x] 1.5 更新 `WebRtcDeviceFileClient`、`Device.kt`、`FileState.kt` 的进度回调结构。
- [x] 1.6 运行 `openspec validate update-webrtc-file-transfer-retransmit --strict` 与相关 Gradle 测试。
