# Change: Add pause and cancel control for WebRTC stream transfers

## Why
当前 WebRTC 传输链路里，基于 `copyPath/controlCopy` 的远端复制已经支持暂停/继续/取消，但大文件直传使用的 payload-channel 文件流仍然只能“跑到结束或失败”。
这会导致任务面板上的暂停/取消对部分 WebRTC 文件任务无效，用户只能等待传输完成、断开设备，或让任务以超时/断连的方式失败。

## What Changes
- 为生产 WebRTC 文件流传输增加协作式 `pause/resume/cancel` 控制，覆盖 `uploadLocalFileToPeer` 与 `downloadPeerFileToLocal` 这条 payload-channel 传输链路。
- 扩展 WebRTC 文件流控制协议与控制器状态，使两端可以在不重建 peer session 的前提下暂停、继续或取消单个活跃传输。
- 将任务系统里的暂停/继续/取消动作接入 WebRTC 文件流传输，而不是只更新本地 `TaskState`。
- 保持已有 `copyPath/controlCopy` 远端复制控制不变，不重复改造已经具备暂停/取消能力的 RPC 复制路径。
- 增加共享 WebRTC 测试，覆盖暂停后继续、取消清理和断连收尾行为。

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- `webrtc-device-file-transfer-performance`: 生产 WebRTC payload-channel 文件流需要支持暂停、继续与取消控制。
- `manage-file-operation-tasks`: 文件任务的暂停/继续/取消需要真正驱动 WebRTC 文件流传输，而不是停留在本地任务状态。

## Impact
- Affected code:
  - `shared/src/commonMain/kotlin/com/folderspan/service/webrtc/controller/**`
  - `shared/src/commonMain/kotlin/com/folderspan/service/webrtc/transfer/WebRtcFileStreamModels.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/service/webrtc/client/WebRtcDeviceFileClient.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/data/main/device/Device.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/ui/state/file/FileState.kt`
  - `shared/src/commonTest/kotlin/com/folderspan/service/webrtc/**`
- Affected specs: `webrtc-device-file-transfer-performance`, `manage-file-operation-tasks`
- Out of scope:
  - WebRTC 房间连接本身的暂停/取消语义
  - 已支持 `controlCopy` 的 RPC 复制路径
  - 设置页/测试页新增暂停按钮或独立传输管理 UI
