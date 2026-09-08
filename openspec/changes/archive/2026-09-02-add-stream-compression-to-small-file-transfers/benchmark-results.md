# FSAR2 小文件传输基准

> 2026-09-01：开发期协议已收紧为仅接受当前 FSAR2；FSAR1、版本协商和缺失能力回退均已删除。下列基准原本即使用 FSAR2，测量数据与策略结论不受影响。

## 入口与口径

可重复入口位于 `core/src/jvmTest/kotlin/com/folderspan/service/http/archive/ArchiveStreamBenchmarkTest.kt`。常规测试会快速跳过；显式运行命令：

```bash
FOLDERSPAN_RUN_ARCHIVE_BENCHMARK=1 ./gradlew :core:jvmTest \
  --tests 'com.folderspan.service.http.archive.ArchiveStreamBenchmarkTest'
```

fixture 包含 10,000 个普通 `.js` 文件，分布在 100 个目录，总 payload 5,113,480 bytes，平均 511 bytes。文件内容可重复且偏文本型，用来代表大量相似源码/元数据小文件。每个场景先预热，随后记录：

- 墙钟：场景整体耗时；
- CPU：JVM 进程 CPU 时间；
- 峰值内存：采样到的 JVM 已用堆绝对值；
- 网络字节：Session 场景统计双向内存传输层写入字节，其余适配场景统计 FSAR body 字节，不含 TLS/DTLS/IP 外层。

Session 两组使用真实 `DeviceSessionPeer`、OPEN/TRAILER、credit window 与 8 并发逐文件流。WebRTC/WAN 与链接分享 HTTP 组测公共 FSAR pipeline 的对应 codec 策略；relay 额外经过容量为 2 的有界 byte channel。这是单机验收基准，不替代两台物理设备在实际 RTT、磁盘和无线链路下的发布前测量。

## 2026-08-31 结果

环境：Linux 7.1.10 x86_64、Intel Core Ultra 9 285H（16 cores）、Temurin JDK 21.0.10。

| 场景 | codec | 墙钟 ms | CPU ms | 峰值堆 bytes | 网络/body bytes |
|---|---:|---:|---:|---:|---:|
| Session/LAN 逐文件流 | 无 | 1,465 | 3,620 | 193,615,776 | 6,719,966 |
| Session/LAN FSAR2 | NONE | 1,449 | 3,840 | 196,976,576 | 6,286,605 |
| WebRTC/WAN FSAR2 | ZSTD level 1 | 1,344 | 1,760 | 196,471,600 | 108,348 |
| 跨设备 relay FSAR2 | ZSTD level 1 | 1,391 | 1,460 | 196,539,272 | 108,348 |
| 链接分享 HTTP FSAR2 | ZSTD level 1 | 1,522 | 1,560 | 225,892,856 | 108,348 |

## 结论与默认策略

- Session/LAN 的 `NONE` 在零 RTT 内存链路上已经减少约 6.4% 双向协议字节，并在 10,000 文件写盘场景保持略快墙钟；无需为 LAN 默认支付压缩策略复杂度。因此维持 LAN=`NONE`。
- 文本型 WAN fixture 的 zstd body 是未压缩 payload 的约 2.1%，墙钟与 CPU 没有出现无界增长。因此维持 WebRTC/WAN=`ZSTD level 1`，但不把该压缩率当作二进制文件承诺。
- relay 与 WebRTC 场景的峰值堆相差不足 0.1 MiB，符合容量 2 的中继不会按完整批次积累内存的预期。
- HTTP 场景峰值堆比 Session 基线高约 31 MiB，仍没有超过单批次硬上限的量级；发布前应继续用真实 HTTP socket 与大批次采样确认平台 allocator 行为。
- 当前批次策略保持 256 条目标、512 条硬上限、32 MiB payload 上限；本轮测量没有依据放大这些边界。
- JS/Wasm 继续只用 `NONE` 或逐文件回退；iOS 的 zstd 性能需要在 macOS/iOS 真机补测，不从 JVM 数据外推。

回滚开关是停止广告本地 FSAR2 capability（或关闭协调器批次选择），设备复制会自动回到现有逐文件流；不会恢复设备 HTTP archive 路由，也不需要数据迁移。

## 跨平台验证

- `:core:jvmTest`、`:app:shared:jvmTest`、`:core:compileAndroidMain`、`:core:compileKotlinJs` 与 `:core:compileKotlinWasmJs` 已通过。
- zstd 中间 source set 的 `compileZstdMainKotlinMetadata` 已通过，JS/Wasm 构建没有解析原生 zstd artifact，并使用 `NONE` fallback。
- iOS 相关 metadata（含 `fileStagingMain`、`zstdMain`）已通过。为使共享 metadata 可解析，将 `FileSystem.SYSTEM` 保持在 Android/JVM/iOS 叶子 source set 并注入共享 staging 实现，运行时行为不变。Linux 主机上的 `:core:compileKotlinIosSimulatorArm64` 按 Gradle 平台规则跳过，因此实际 iOS klib 仍需在 macOS/Xcode 验证。
