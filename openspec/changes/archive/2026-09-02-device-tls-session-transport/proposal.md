## Why

局域网设备互传已经是直连，但传输仍是 HTTP/1.1 请求模型：每个读/写一次往返，小文件靠巨型归档摊薄，心跳和数据抢连接，低内存还要把分片砍到 64KB/1 路。开发阶段没有存量客户端，应把设备 API 从 HTTP 换成同一 TLS 端口上的多路会话协议，而不是继续调 HTTP 参数。

## What Changes

- **BREAKING** 原生三端设备 API 不再使用 HTTP/1.1。`12040` 只接受 TLS + ALPN `folderspan/1` 的多路会话；未协商到该 ALPN 的连接直接断开，不解析 HTTP。
- **BREAKING** 删除设备 HTTP 路由：`/ping`、`/api/devices/*`、`/api/files/*`、`/api/paths/*` 以及挂在同一设备端口上的浏览器 WebRTC HTTP 信令。业务改走会话 control RPC 与文件流。
- **BREAKING** `DeviceTransportType.Http` 从原生设备路径删除，改为 `Session | WebRtc`。
- **BREAKING** 局域网发现改为签名 UDP beacon（`12041`），删除 `/24` HTTPS ping。访客网隔离时只保留手动填 IP 连会话端口。
- 一条 TLS 连接上多路复用控制面与文件流，会话级 PING 不被数据流挤死；文件按 credit 窗口流式传输，帧 payload ≤64KiB。
- 目录复制默认走 manifest + 多文件流，不再把小文件打成 32MiB HTTP 归档。
- Web / LinkShare / 跨网 WebRTC / 网络盘协议不变。浏览器不连 `12040` 会话端口。

## Capabilities

### New Capabilities

- `device-tls-session-transport`: TLS 多路会话（ALPN、帧、credit、control RPC、文件流、目录 manifest）
- `lan-device-beacon`: 局域网 UDP 发现信标、签名校验与手动 IP 回退

### Modified Capabilities

- `http-socket-tls-transport`: 设备端口不再处理 HTTP/1.1；TLS 钉扎保留，应用协议改为会话
- `http-file-transfer-status`: 设备直连调谐从 HTTP 头改为会话接收窗口 / 并发流
- `device-heartbeat`: 心跳从 `/api/devices/heartbeat` 改为会话 PING，鉴权失败不再走 HTTP 401
- `refresh-device-scan`: 原生端改为运行期持续接收 UDP beacon，不再启动/恢复扫描或 HTTPS ping；Web 仍走现有 WebRTC 发现
- `account-device-lan-connectivity`: 同账号自动连改为会话 `Connect` + beacon，不再 HTTP ping/connect
- `connect-browser-device-webrtc`: 设备端口不再提供 `/api/webrtc/signaling/*`；浏览器不连 `12040` 会话端口，继续走已有跨网 / 房间 WebRTC

## Impact

- 服务端：`RawTlsHttpServer` 设备端口改为会话服务器；`RawHttpApiDispatcher` 的设备路由删除
- 客户端：`HttpRouteClientManager` 退出设备连接路径，由 `DeviceSessionClientManager` 取代
- 发现：原生端只保留持续 beacon 接收与手动 IP 会话握手；`DeviceState.scanner` 仅服务 WebRTC 浏览器发现
- 传输：`DeviceFileClient` 增加流式 API；`PathRouteTransferClient` 只走 Session
- 复用：`DeviceTlsIdentity` 钉扎/签名、`DeviceFileService` 权限与流式写入、内存采样
- 不改：`LinkShareRawHttpServer`、`WebRtcDeviceFileClient`、`NetworkClient`
