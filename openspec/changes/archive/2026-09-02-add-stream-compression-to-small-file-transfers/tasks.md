## 1. FSAR2 协议与压缩基础

- [x] 1.1 在版本目录和 `core` source set 中加入与现有 Okio 兼容的 `zstd-kmp-okio` 依赖，并让 JS/Wasm 构建不解析原生 zstd artifact
- [x] 1.2 定义稳定的 archive version、codec、capabilities、stream options、read/write request 与 `ArchiveTransferResult` protobuf/领域模型，固定字段编号并验证未知枚举处理
- [x] 1.3 扩展 `FolderSpanArchiveCodec` 以编码和解析唯一的 FSAR2 prelude 与条目帧
- [x] 1.4 实现 `ArchiveFrameEncoder`，以有界 chunk 懒读取多个文件并输出共享条目帧和 0 长度结束符
- [x] 1.5 实现 `ArchiveStreamEncoder` / `ArchiveStreamDecoder` 的 `NONE` 流式路径，不为完整条目或批次分配 `ByteArray`
- [x] 1.6 为 Android、JVM/Desktop 与 Kotlin/Native 接入 zstd level 1 流式 source/sink，并为 JS/Wasm 提供只广告 `NONE` 的实现
- [x] 1.7 在 decoder 中落实 prelude、entry、累计文件字节和累计帧字节硬上限，并拒绝未知版本/codec、截断、缺少结束符及尾随解压数据

## 2. 归档抽取与协议测试

- [x] 2.1 实现 `ArchiveEntryExtractor`，复用相对路径规范化和现有写入授权，在完整 flush/close 后才提交条目并返回完成路径
- [x] 2.2 处理当前条目写入失败或取消时的部分文件清理/截断，同时保留此前已提交条目
- [x] 2.3 添加 FSAR2/`NONE` 和 FSAR2/`ZSTD` golden byte fixtures，验证不同 adapter 解码出相同条目与结束语义，并拒绝 FSAR1
- [x] 2.4 添加流式 round-trip 与有界缓冲测试，覆盖跨 chunk 的 prelude、header、零字节文件、最大允许文件及多批次
- [x] 2.5 添加恶意输入测试，覆盖绝对路径、反斜杠、`.` / `..`、重复文件、伪造大小、解压超限、截断流和未知 codec/version

## 3. DeviceFileClient、Session 与 WebRTC

- [x] 3.1 为 `DeviceFileClient` 增加 `archiveCapabilities()`、`readArchiveStream(...)` 和 `writeArchiveStream(...)`，默认实现明确返回不支持而不退回整批 `readBytes` / `writeBytes`
- [x] 3.2 扩展 Session 连接响应以强制携带当前 FSAR codec 与归档限额，缺失能力的旧 peer 直接失败
- [x] 3.3 增加 Session `ArchiveRead` stream kind、open payload 与服务端 producer，使设备按请求清单边读边输出一个 FSAR 流
- [x] 3.4 增加 Session `ArchiveWrite` stream kind、目标根校验、服务端 extractor 与包含完成路径的 trailer
- [x] 3.5 为 Session archive read/write 添加成功、能力不支持、权限拒绝、部分提交、取消和 credit-window 反压测试
- [x] 3.6 扩展 WebRTC 设备文件 RPC 的能力响应、archive read/write 请求和分块 DataChannel 消息，禁止将完整归档聚合为单个 `ByteArray`
- [x] 3.7 为 WebRTC archive read/write 添加分块、乱序/截断拒绝、取消、缺失能力拒绝与峰值缓冲测试

## 4. 统一复制协调器与三类设备复制

- [x] 4.1 实现 `SmallFileArchiveTransferCoordinator`，复用 `selectSmallFileArchiveBatches()` 并只选择双方共同支持的 FSAR2 codec
- [x] 4.2 实现策略：Session/LAN 默认 `NONE`，WebRTC/WAN 在双方支持时默认 `ZSTD`，无共同 archive 能力时不创建归档流
- [x] 4.3 接入 Device→Local：从 `DeviceFileClient.readArchiveStream` 增量解码到本地，并保留大文件/单文件路径
- [x] 4.4 接入 Local→Device：从本地文件增量编码到 `DeviceFileClient.writeArchiveStream`，并保留大文件/单文件路径
- [x] 4.5 接入不同设备的 Device→Device：用有限 `Channel<ByteArray>` 结构化并发转发源 archive read 到目标 archive write，任一端失败即双向取消
- [x] 4.6 确保 Device→Device 中继只校验 FSAR2 prelude、不解压或本地落盘，并以目标 trailer 的完成路径作为提交结果
- [x] 4.7 保持同设备复制走 `copyPath`、空目录走 control RPC、符号链接跳过，添加混合目录树回归测试
- [x] 4.8 将 `FileRuntimeTaskExecutor.processRuntimeCopyArchiveBatches` 与 `copyViaTransportClients` 改为调用统一协调器，移除原生设备路径对 `FileRouteClient` archive API 的依赖

## 5. 链接分享与原生分享复用

- [x] 5.1 链接分享 archive download 请求强制声明可接受 codec，不携带当前能力的旧请求直接失败
- [x] 5.2 将 `/api/share/archive-download` 接到公共 `ArchiveStreamEncoder`，只输出 FSAR2/`NONE` 或 FSAR2/`ZSTD`，且不恢复设备 `/api/files/archive-*` 路由
- [x] 5.3 将 `HttpShareRouteClientManager` 接到公共 decoder/extractor，native 使用当前 FSAR2，JS/Wasm 使用 `NONE`
- [x] 5.4 添加链接分享当前请求、旧请求拒绝、加密 HTTP、响应截断和取消测试
- [x] 5.5 添加原生 Share Save 到本地及从 View 复制到另一设备的集成测试，证明它们分别继承 Device→Local 和 Device→Device 而没有独立分享字节协议

## 6. 任务进度、暂停与失败回退

- [x] 6.1 按未压缩文件 payload 更新 archive 进度和总量，并确保 D→D 只选择一个进度源、压缩比不会造成重复计数或越界
- [x] 6.2 将暂停/取消从任务协程传播到文件 producer、codec、Session/WebRTC/HTTP 请求和 extractor，并验证不序列化活动 zstd 上下文
- [x] 6.3 在 runtime metadata 中只保存批次计划和已提交相对路径，恢复时从首个未提交条目重新规划
- [x] 6.4 实现批次失败后的差集回退，只把未提交条目交给现有单文件路径，并测试源端失败、目标端失败和部分成功
- [x] 6.5 回归大文件范围传输、任务重试、任务取消、失败消息以及结果列表在启用归档优化前后的语义一致性

## 7. 跨平台验证与性能验收

- [x] 7.1 运行 `:core:jvmTest` 和 `:app:shared:jvmTest`，修复 FSAR、Session、WebRTC、复制任务和链接分享回归
- [x] 7.2 编译 Android、Desktop/JVM、iOS、JS 与 Wasm 相关 source set，验证 zstd 依赖隔离和 `NONE` fallback 均可解析
- [x] 7.3 建立至少 10,000 个普通文件、平均小于 8 KiB 的可重复 fixture 和基准入口，记录逐文件、FSAR2/`NONE`、FSAR2/`ZSTD` 的墙钟、CPU、峰值内存与网络字节
- [x] 7.4 在 Session/LAN、WebRTC/WAN、跨设备 relay 和链接分享 HTTP 上运行基准，并确认 LAN 的主要收益来自装帧、WAN 的 zstd 收益不以无界 CPU/内存为代价
- [x] 7.5 根据测量结果调整批次与 codec 默认策略，记录平台差异、回滚开关和未达标路径
- [x] 7.6 更新相关协议/传输文档与 `md_descriptions_paths.md`，执行 `openspec validate add-stream-compression-to-small-file-transfers --strict` 并修复全部错误

  - 7.2 验证记录（2026-08-31）：Android、Desktop/JVM、JS、Wasm 以及 iOS 相关 metadata（含 `fileStagingMain`、`zstdMain`）均已通过；Linux 不产出实际 iOS klib，发布前仍需在 macOS/Xcode 完成设备构建。

## 8. 开发期协议收紧

- [x] 8.1 删除 FSAR1、版本列表与版本协商模型，让所有归档管道只接受当前 FSAR2 magic
- [x] 8.2 删除链接分享旧请求默认值、Session 旧响应默认能力和 WebRTC 缺失能力回退，旧格式必须显式失败
- [x] 8.3 删除不可达的设备 HTTP archive 编解码与客户端 fast path，保留 `/api/files/archive-*` 为 404
- [x] 8.4 更新当前协议测试、传输文档和基准说明，运行核心/共享测试、跨平台编译与 OpenSpec 严格校验
