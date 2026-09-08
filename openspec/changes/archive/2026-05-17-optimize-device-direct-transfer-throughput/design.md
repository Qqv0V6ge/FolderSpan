## Context
设备直连文件复制已经完成 raw byte 与 Raw TLS 改造，热路径主要由 `PathRouteClient`、`FileRouteClient`、`HttpFileTransferStatusProvider` 和通用 `DeviceTransportPipeline` 承担。当前瓶颈更像是客户端调度、in-flight 上限和读写耦合，而不是 wire format。

## Goals
- 在不改变现有 byte-route 与 TLS 信任语义的前提下提升设备直连复制吞吐。
- 通过更深流水线、较大的读缓冲和更积极的 status 推荐值提高链路利用率。
- 保留明确内存预算、busy + retry backoff、暂停/取消和断连检查。

## Non-Goals
- 不改变 `/api/files/read-bytes`、`/api/files/write-bytes`、`/api/share/read-bytes` 的路径、请求体或响应体协议。
- 不把新增设备直连长流端点用于 Share 或 WebRTC/RPC。
- 不调整 WebRTC/RPC data channel 传输策略。
- 不放大 Share、WebRTC/RPC 或通用 HTTP/Share 分片上限；设备直连 HTTP byte-route 可在同一协议字段下使用更大的专用上限。

## Approach
- `DeviceTransportPipelineConfig` 从单一 `pipelineDepth` 扩展为 `readParallelism`、`writeParallelism` 和 `queueDepth`，并保留 `pipelineDepth` 作为兼容构造参数。
- HTTP 设备复制使用 128MB in-flight 预算，客户端默认并发上限收敛为 12 路。设备直连 byte-route 协议上限仍为 16MB，但 Path copy 大文件实际调度按 `128MB / 12` 向下按 1MB 对齐为 10MB 分片，让首批请求能跑满 12 路并发，而不是被 16MB 分片压到 8 路。
- `HttpFileTransferStatusProvider` 在低压力时推荐 `MAX_CONCURRENT_FILE_CHUNKS=64`，压力达到最大并发附近才逐步降级并设置 busy/retry。
- raw byte 读取缓冲保持在 2MB；服务端文件响应块保持 1MB，避免上一轮 4MB 对齐带来的等待更久和内存峰值增大。
- Raw TLS 服务端从单请求连接改为 HTTP/1.1 keep-alive：每个 accepted TLS socket 在同一连接上循环解析后续请求，并按请求 `Connection` 语义决定是否关闭。
- keep-alive 只改变连接生命周期，不改变 `/api/files/read-bytes`、`/api/files/write-bytes` 或 `/api/share/read-bytes` 的请求/响应协议。
- `/api/files/read-bytes` 与 `/api/files/write-bytes` 服务端使用设备直连专用 16MB 校验和 status header；Share 路由仍默认发布 8MB，避免 Share 客户端被错误推荐到更大的 range。
- 写入偏移继续优先使用显式 `blockStartOffset`。旧客户端缺少该字段时，服务端仍按历史 8MB `blockIndex` 解释偏移，保证设备直连服务端接受 16MB 后不破坏老版本 8MB 分片写入。
- `FileState` 与 `Device` 中的跨实现传输管线同步使用设备直连 16MB 上限；只有 WebRTC/RPC 继续走较小的专用 data channel 配置，避免 UI/恢复流程把 HTTP 直连推荐值压回 8MB。
- 大文件下载使用 `readBytesRangeChunks + writeByteRanges` 流式落盘；8MB 以内的小文件保留单 range 快路径，避免为很小的省拷贝收益引入额外 flow 调度。
- 设备到设备的大文件复制使用 `DeviceTransportPipeline`，让源设备读取和目标设备写入在有界队列内重叠，避免每个 chunk 必须完整读完再开始写。
- 本地上传的大文件分片调度改为固定 worker queue，避免原先“达到并发上限后每 10ms 轮询 completed job”的调度空窗。
- 单个大文件设备到设备复制使用源读、目标写独立 semaphore，充分利用两条 HTTP 连接；目录复制仍使用共享任务级 semaphore，避免多文件并发时请求数失控。
- 设备下载到本地使用有界 range 聚合写：每个 `/api/files/read-bytes` response 直接填充一个不超过设备直连单块上限的 `ByteArray`，再通过平台 `writeBytes` 一次写入目标 offset，避免上一版 `writeByteStream` 按 HTTP/TLS 小片段频繁随机写文件导致吞吐回退。
- `FileUtils.writeByteStream` 作为平台级连续流写能力保留，但不作为设备直连下载热路径默认策略；热路径优先减少系统调用和进度锁竞争，而不是只追求少一次内存拷贝。
- 启动期显示慢的根因是首批 16MB range 必须完整下载并写入后才更新进度；10MB 分片把首批完成时间缩短，同时把并发从 8 路提升到 12 路，用同样内存预算更快填满链路。
- 为了继续突破 10MB range 的请求轮次墙，设备到本地大文件下载新增 `/api/files/stream-file` 长流快路径。该端点复用相同授权、路径校验和 transfer status header，服务端允许单次 64MB range；客户端默认用 32MB 目标 range，并且服务端和客户端都用 4MB 固定缓冲顺序流式读写，不在客户端构造 32MB/64MB `ByteArray`。
- `PathRouteClient` 对大文件设备下载优先调度最多 12 路 32MB stream range。这样保留 byte-route 同级连接宽度来填满高速局域网链路，同时把每 GiB 的 HTTP 请求数从约 103 次 10MB range 降到 32 次 32MB range，显著降低 protobuf、header、TLS record 调度、协程分发和本地文件打开次数。
- 上一版 6 路 64MB stream 的实际反馈是“稳定但变慢”：它减少了请求轮次，但把连接宽度从 12 路砍到 6 路，单连接吞吐不足时会降低总速率。因此默认策略改为“宽长流”，优先保证链路填充，再减少请求数。
- 长流快路径失败时不整体重来：客户端按每个 stream range 已写入的连续字节数计算剩余范围，只用旧 `/api/files/read-bytes` 10MB 分片补齐尾部，保证旧版本 peer 或中途失败仍能回退，并避免重复计入进度。
