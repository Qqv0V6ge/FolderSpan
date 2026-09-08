## Why

App 之间「分享到其他设备」现在走独立 HTTPS 分享协议（heartbeat / connect / `/api/share/read-bytes` / archive-download）。同一对设备在文件管理器里做「设备 → 本地」却走 Session（LAN）或 WebRTC（WAN）。两套管子导致小文件优化、断点、进度、权限都要做两遍，分享路径还用不上已经换完的设备会话。分享到设备的字节传输应复用设备复制栈。

## What Changes

- 原生 App「分享到其他设备」在对方批准之后，文件字节走与「设备 → 本地」相同的复制管线（LAN Session / WAN WebRTC），不再用 `/api/share/read-bytes`、`/api/share/stream-file`、`/api/share/archive-download` 拉数据。
- Save / Auto-Save：接收端把发送端当作已连接设备，按分享清单把文件复制到保存路径，调用现有 `Device.files.copyTo` / runtime copy queue。
- View：接收端以设备桌面打开发送端（可限定为分享清单中的路径），浏览与拷贝同样走设备栈，不再挂 `FileProtocol.Share` HTTP 盘。
- 批准信令可继续用现有 `/api/share/heartbeat` 短轮询与通知（Save / View / 拒绝）；批准完成后升级为设备 Session 或 WebRTC 连接。
- 链接分享（浏览器打开分享页、系统分享）保持 HTTP / ZIP，不纳入本次。

## Capabilities

### New Capabilities

- `share-to-device-device-copy`: App 互发「分享到设备」在批准后建立设备连接，并用设备复制管线传输文件

### Modified Capabilities

- `handle-device-share-requests`: Save / Auto-Save / View 的传输与挂载从分享 HTTP 改为设备复制；完成后的断连语义保留
- `http-file-transfer-status`: 设备分享 Save 不再要求 `/api/share/read-bytes` 分片；调谐改走设备 Session credit 或 WebRTC 传输计划

## Impact

- 发送：`DeviceState.share` 批准后允许对端以设备身份连接（Session/WebRTC），而不只发分享 token
- 接收：`DeviceState.connectShare` / `HttpShareRouteClientManager.share` 的 Save/View 不再作为文件字节通道；改为 `DeviceState.connect` + `copyTo`
- 复制：复用 `FileStateCopyCoordinator` DeviceRoute、`FileRuntimeTaskExecutor`、Session 流 / WebRTC payload
- 不改：`LinkShareRawHttpServer`、浏览器分享页、系统分享、网络盘
- 后续小文件归档（FSAR over Session/WebRTC）会自动覆盖这条路径，本提案不实现归档本身
