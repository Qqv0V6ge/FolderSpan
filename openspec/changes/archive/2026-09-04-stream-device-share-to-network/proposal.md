## Why

Device→Network 与 Share→Network 复制目前必须先把完整文件落到本地 `cache/sync-stage`，再按 Local→Network 上传。根因是 `NetworkClient.upload` 只吃 `localPath`，Device/Share 的分片读无法喂给网络盘。大文件因此双倍耗时、占满本机缓存就会失败，即使用户真正的目标是网络盘。

## What Changes

- 网络盘上传增加流式入口：按分片/回调消费源字节，不再要求源已是本地路径。
- `CopyRoute.DeviceToNetwork` 与 `CopyRoute.ShareToNetwork` 改为边读边上传，不再把完整文件写入 `sync-stage`。
- 目录仍按文件逐个流式上传；空目录走 `createFolder`。
- 系统分享（`SYSTEM_SHARE_DESK_ID`）源已在本机或 content URI，可继续从本地读后流式上传，不走远程 Share 会话。
- Local→Network 已是本地源，保持现有 `upload(localPath)` 路径。
- Share→Network / Device→Network 仍不纳入小文件归档与分片续传。
- 不把 Share 注册成 Device；不改系统分享为 Device 会话；不改浏览器链接分享。

## Capabilities

### New Capabilities

- `stream-copy-to-network`: Device 与远程 Share 复制到网络盘时流式上传，不把完整文件落到本地临时目录

### Modified Capabilities

- `access-network-drives`: 粘贴到 FTP/SFTP/SMB 时，源为 Device 或远程 Share 的条目 SHALL 流式上传，而不是先落地再 `upload(localPath)`
- `webdav-network-io`: WebDAV 上传 SHALL 接受流式字节源，而不仅是本地路径

## Impact

- 协议：`NetworkClient` / `NetworkAccess` 增加流式上传；WebDAV、SFTP、SMB、FTP、S3 客户端实现该入口
- 复制：`FileStateCopyCoordinator.copyDeviceToNetwork` / `copyShareToNetwork` 去掉 `copyViaLocalTemp` 与 `stageDeviceFileToLocalForNetworkUpload` 的整文件落地
- 源读取：Device 用现有分片读（Session / WebRTC）；远程 Share 继续借用 Device 读引擎，不注册 Device
- 任务：进度仍按现有 Copy/Move 任务模型更新；Network 目标不开启归档批次与 chunk recovery
- 不改：Local→Network、Network→Network 同端点、浏览器链接分享、系统分享会话模型、Share 只读目标
