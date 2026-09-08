## Why

海量小文件传输的主要瓶颈是每个文件各自建流、打开和关闭所产生的固定成本，现有并发单文件流无法消除这部分开销。项目已有 FSAR 小文件帧和分批器，但它们仍绑定旧 HTTP 路径；需要把“边遍历边装帧”提升为 Session、WebRTC 与链接分享 HTTP 共用的复制能力，并把压缩降为可协商的可选传输层。

## What Changes

- 引入与管道无关的流式小文件归档能力：同一套 FSAR 帧可承载于 Session、WebRTC DataChannel 或链接分享 HTTP body，发送端边读取边装帧，接收端边解帧边落盘。
- 将开发期 FSAR 收敛为唯一的自描述 FSAR2 流格式；整段条目帧可选择 `none` 或快速 zstd 压缩，压缩算法不按文件重复初始化。
- 让 Device→Local、Local→Device 与跨设备 Device→Device 复用统一的归档读写接口；Device→Device 在本机只做有界字节转发，不生成临时归档或临时文件树。
- 原生 Share→Local / Share→Device 在批准后继承设备复制管线；链接分享 Share→Local 保留 `/api/share/archive-download`，但输出相同的自描述 FSAR 流。
- 复用现有小文件批次选择；大文件、单文件以及失败批次的剩余条目继续走现有单文件路径；不支持当前归档协议的旧端直接报告协议错误。
- 保持条目级任务语义：进度按未压缩文件字节计算，暂停/取消终止活动流，失败时保留已完成条目并只回退未完成条目。
- 继续拒绝不安全相对路径、重复文件条目和符号链接，并对解压后的条目与批次字节数执行既有限额。
- 不恢复设备端口 `/api/files/archive-download` 或 `/api/files/archive-upload`，不生成完整 ZIP，也不改变浏览器 ZIP 导出功能。

**BREAKING**: 删除 FSAR1、旧链接分享请求和旧设备 peer 的兼容处理；所有开发期客户端与服务端必须使用当前 FSAR2 协议。

## Capabilities

### New Capabilities

- `streaming-small-file-archive-transfer`: 规定跨 Session、WebRTC 与链接分享 HTTP 的统一 FSAR 小文件装帧、可选整流压缩、跨设备中继、安全校验、任务进度和失败回退行为。

### Modified Capabilities

无。

## Impact

- 归档协议与流式 I/O：`FolderSpanArchiveCodec`、`ArchiveClientIo`、`RawHttpArchiveRoutes`。
- 设备传输抽象与协议：`DeviceFileClient`、Session stream-open/control 协议及 WebRTC 设备文件 RPC。
- 复制规划与执行：`selectSmallFileArchiveBatches()`、`FileRuntimeTaskExecutor`、`Device.files.copyTo` / `copyViaTransportClients`。
- 链接分享：`HttpShareRouteClientManager` 与 `/api/share/archive-download`；设备 HTTP archive 路由保持删除状态。
- 依赖：原生/JVM 目标增加支持流式 zstd 的 Kotlin Multiplatform 依赖；JS/Wasm 不支持时协商 `none` 或回退单文件。
- 测试与基准：当前协议拒绝旧格式、路径与解压边界、暂停取消、批次失败回退，以及五类用户路径上的真实小文件树性能对比。
