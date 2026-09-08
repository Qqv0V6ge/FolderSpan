# Change: Optimize Share HTTP Transfer

## Why
当前共享复制链路需要同时满足吞吐和内存约束。上一版把默认路径切到 `/stream-file` 后，又叠加了 4MB 流式分段和重排缓冲，实际把请求调度和排队开销放大了，速度反而更慢也更不稳。默认复制路径应回到更直接的 `/read-bytes` 并发直读，使用更大的块和更少的调度层。

## What Changes
- 共享复制主链路回到 `/api/share/read-bytes` 并发直读，使用 `4MB~8MB` 自适应块、全局有界并发和单文件句柄偏移写入，减少流式调度与重排成本。
- `8MB` 以内的小文件继续使用单次 `read-bytes` 读取，避免启动额外 worker 管线。
- 并发 range 直接按文件 offset 写入本地，不再依赖 stream 重排缓冲。
- 本地写入改为单文件句柄批量偏移写入，JVM/Android 本地路径使用 `FileChannel` 定点 IO。
- 进度和日志更新节流到约 1 秒，避免每个数据块都刷新任务状态。
- 服务端为已验证共享路径增加短 TTL 解析缓存，避免大文件每个 range 重复执行 token/share/path/hidden 校验；热路径成功 debug 日志下线。
- Android `content://` 共享源的 range chunks 改为一次打开并连续读取，避免每个子块重新打开和跳过。
- 匿名复制读取绕过 `HttpClientRequestRegistry` 自动注册，父协程取消仍负责中断复制任务。

## Impact
- Affected specs: `http-file-transfer-status`, `handle-device-share-requests`
- Affected code: `ShareRoutes.kt`, `HttpShareRouteClientManager.kt`
