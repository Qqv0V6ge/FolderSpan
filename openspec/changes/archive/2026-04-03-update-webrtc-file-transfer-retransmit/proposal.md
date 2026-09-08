# Change: add application-level retransmit for WebRTC file transfer

## Why
当前设备间 WebRTC 文件流主要依赖 DataChannel/SCTP 的可靠传输。任务进度中的分块计数只是按已传输字节换算，不能准确反映真实已确认块数；在 `unreliable` 模式或底层实现出现缺块时，系统也缺少应用层补传能力。

## What Changes
- 为 WebRTC 文件流增加应用层确认、缺块检测和选择性重传。
- 将上传/下载任务中的 `doneBlocks/totalBlocks` 语义改为“已确认完成块数/总块数”。
- 在接收端完成最终核验前，发送端不得将文件流标记为成功。
- 保持现有 UI 入口和默认通道模式不变，不新增用户配置开关。

## Impact
- Affected specs: `webrtc-data-channel-file-transfer`
- Affected code: `shared/src/commonMain/kotlin/com/folderspan/service/webrtc/*`, `shared/src/commonMain/kotlin/com/folderspan/data/main/device/Device.kt`, `shared/src/commonMain/kotlin/com/folderspan/ui/state/file/FileState.kt`
