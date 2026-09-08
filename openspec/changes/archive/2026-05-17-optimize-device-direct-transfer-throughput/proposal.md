# Change: Optimize Device Direct Transfer Throughput

## Why
设备直连复制当前在高速局域网内没有充分填满链路，默认并发、in-flight 内存预算和客户端读缓冲偏保守，导致路由器利用率约停留在 50%。目标是在明确内存上限和忙碌回退下，将 HTTP/Raw TLS 直连路径吞吐提升约 40%。

## What Changes
- 设备直连本地↔设备、设备↔设备复制继续使用现有 HTTP/Raw TLS byte routes；设备↔本地下载新增长 range stream 快路径，旧 byte routes 作为回退。
- 传输管线支持读 worker、写 worker 和队列深度独立配置，让读侧可以预取更多块，写侧按目标能力消费。
- 设备直连 HTTP 热路径将 in-flight 上限从 64MB 提高到 128MB，并把设备直连 byte-route 单块协议上限提高到 16MB；客户端大文件调度按约 10MB 实际分片填满 12 路请求，避免首批 16MB range 完成过慢。
- 客户端 raw byte 读取缓冲提升并统一设备与 Share 读取策略，减少每个 range 的读取循环与小块复制成本。
- Raw TLS 服务端支持 HTTP/1.1 keep-alive，让连续 range 请求复用同一个 TLS 连接，降低每个 range 的连接重建成本。
- `X-FolderSpan-Transfer-*` 推荐策略在低压力时推荐更高并发，busy 回退仍保留但更晚触发。
- 设备下载到本地优先走 range streaming 写入，避免每个 8MB range 再合并成额外 `ByteArray`。
- 传输恢复/中转等跨实现管线同步采用设备直连 16MB 上限，避免推荐值在 UI 层被重新压回 8MB。
- 本地上传调度改为 worker queue，去掉分块调度中的轮询空窗；单个大文件设备↔设备复制使用源/目标独立请求额度，提升全双工利用率。
- 设备下载到本地改为按受限 range 聚合后一次性写入本地块：绕过 `channelFlow<Pair<Long, ByteArray>>`，同时避免 HTTP/TLS 小片段触发频繁随机文件写。
- Path copy 大文件直连调度使用 10MB 目标分片，在 128MB in-flight 预算内把客户端并发从 8 路提升到 12 路，加快启动期链路填充。
- 设备下载到本地的大文件优先使用 `/api/files/stream-file`，服务端允许 64MB 长 range，客户端默认按 32MB range、最多 12 路并行流式写入本地，失败时只用旧 `/api/files/read-bytes` 补齐未完成范围。

## Impact
- Affected specs: `http-file-transfer-status`
- Affected code: `DeviceTransportPipeline.kt`, `PathRouteClient.kt`, `FileRouteClient.kt`, `DeviceFileService.kt`, `HttpRequests.kt`, `HttpFileTransferStatus.kt`, `FileRoutes.kt`, `RawTlsHttpServer.*.kt`, `RawHttpApiDispatcher.kt`, `FileState.kt`, `Device.kt`, platform `FileUtils` range writers, Android range file helpers, transfer tests
