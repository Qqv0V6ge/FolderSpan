## Context
共享复制在局域网内需要并发填满带宽，但不能为每个小分片支付完整 HTTP 请求成本。上一版默认切到 `/stream-file` 后，虽然减少了单次响应体暴露，但额外的流式分层、窗口重排和子缓冲把吞吐拖慢了。当前目标是回到更直接的 `/read-bytes` 并发直读，同时把每个请求的块大小和总并发都控制在明确上限内。

## Goals
- 使用有界并发的 `/read-bytes` 直读作为共享复制主路径，减少调度层同时保留网络并行度。
- 保持现有鉴权、隐藏文件校验和任务状态更新逻辑。
- 使用单文件句柄偏移写入，降低本地 IO 开销。
- 将目录复制并发收敛到文件级有限 worker，并用全局传输信号量限制总并发。

## Non-Goals
- 不移除 `Share.readBytes()` 这类随机读取接口。
- 不引入新的可配置项或复杂协商协议。

## Approach
- 客户端复制大文件时按 `4MB~8MB` 自适应连续范围调用 `/api/share/read-bytes`，并用有界 worker 队列限制并发请求数，避免过多小请求和重排开销。
- 客户端直接按 byte offset 交给 `FileUtils.writeByteRanges()` 写入，复用目标文件句柄，减少随机写盘和多次打开文件的开销。
- `8MB` 以内的小文件继续走单次 `/api/share/read-bytes` 读取，避免小文件创建 worker 管线。
- 客户端继续解析 `X-FolderSpan-Transfer-*` 状态头，按服务端压力调节并发请求数量，同时把峰值 in-flight bytes 控制在 `64MB` 内。
- 任务进度和日志更新按时间节流，避免每个数据块写任务状态。
- 复制读取没有 requestId/batchId 时绕过 `HttpClientRequestRegistry` 的自动注册，减少每个读取请求的 coroutine/mutex/map 开销。
- 服务端 `/api/share/read-bytes` 与 `/api/share/stream-file` 对 token + requestPath + client fingerprint 的已解析文件信息做短 TTL 缓存，命中时仍检查 token/fingerprint/share session 是否有效，但跳过重复路径解析和隐藏文件扫描。
- 服务端字节传输热路径只保留失败日志，不再为每个成功范围输出 debug 日志。
- `/api/share/stream-file` 请求范围上限提升到 `64MB`，范围内按 `1MB` 子块写出，并继续携带 `X-FolderSpan-Transfer-*` 状态头。
- JVM 与 Android 本地路径使用 `FileChannel` 定点读写，避免每个 range 重复创建 Okio source/sink；Android Shizuku 写入也使用完整写循环且不做强制刷盘。
- Android `content://` 源端范围分块读取复用同一个 descriptor/input stream，先定位到 range 起点后连续读完整个 range，避免每个 `8MB` 子块重新打开流和重复 skip。
