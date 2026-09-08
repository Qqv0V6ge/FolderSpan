## Context

See `proposal.md` - Why。现有 `FolderSpanArchiveCodec` 定义 FSAR1：`FSAR` magic、版本字节、重复的“4 字节 header 长度 + protobuf entry header + 文件字节”，最后以 0 长度 header 结束。它已经具备相对路径规范化、重复文件检查、1 MiB 小文件阈值及 32 MiB 批次上限；`selectSmallFileArchiveBatches()` 也已按条目数和载荷大小分批。

设备复制栈已经从设备 HTTP archive 路由迁移到 TLS Session / WebRTC 的 `DeviceFileClient`，默认是 manifest、空目录 control RPC 和每文件一条流。旧的 `FileRuntimeTaskExecutor.processRuntimeCopyArchiveBatches` 仍直接依赖 `FileRouteClient`，因此原生 Session 路径无法使用已有打包能力，Device→Device 也仍逐文件读取再写入。链接分享的 `/api/share/archive-download` 是应保留的唯一 HTTP FSAR 路径。

实现必须覆盖 Android、Desktop/JVM、iOS、JS 与 Wasm source set；不得要求浏览器客户端拥有原生压缩实现。设备端口不能恢复 `/api/files/archive-download` 或 `/api/files/archive-upload`。归档是复制优化，不能改变路径授权、符号链接策略、暂停取消或失败重试的用户语义。

## Goals / Non-Goals

**Goals:**

- 把 FSAR 编码、压缩和解码从 HTTP 客户端/路由中抽成可复用的有界流管线。
- 在 Session、WebRTC 和链接分享 HTTP 上使用同一份自描述字节格式与协商模型。
- 让 D→L、L→D 和跨设备 D→D 使用同一个批次规划器与条目结果模型；原生分享自然继承设备复制能力。
- 在带宽受限链路提供整段快速 zstd，在 LAN 或不支持压缩的目标上保留无压缩装帧。
- 仅保留当前 FSAR2 wire format；大文件和失败批次仍可使用单文件路径，但旧 peer/旧请求不触发兼容回退。

**Non-Goals:**

- 不生成 ZIP/TAR，不先落完整归档，不改变浏览器面向用户的 ZIP 导出。
- 不为单个大文件实现新的断点格式；大文件继续使用现有范围/单文件流。
- 不引入 rsync、内容寻址、去重或跨任务压缩字典。
- 不在压缩流中间持久化 codec 状态；暂停后的恢复从首个未提交条目重新开始。
- 不调整同设备 `copyPath` 的服务端本地复制行为。

## Decisions

### 1. FSAR2 是唯一归档格式

FSAR2 采用以下线性布局：

```text
"FSAR" + version(2)
u32 streamHeaderLength
protobuf ArchiveStreamHeader {
    codec
    entryCount
    declaredFileBytes
    declaredFrameBytes
}
codec stream {
    u32 entryHeaderLength + ArchiveEntryHeader + file bytes
    ...
    u32(0)
}
```

外层 stream header 始终不压缩，接收端在创建解压器前即可校验版本、codec 和声明上限。压缩层只包裹连续的条目帧区，所有小文件共享一个压缩上下文；`ArchiveEntryHeader`、相对路径格式和 0 长度结束符保持现有语义。`declaredFileBytes` 用于任务总进度和解压上限，`declaredFrameBytes` 用于限制 header 开销和验证解压后的完整帧区。

不保留 FSAR1 编解码器或版本协商。发送端始终产生 FSAR2；接收端必须完整匹配当前 magic/version。未知版本、未知 codec、声明长度越界、缺少结束符或结束符后仍有解压数据都直接失败。

备选方案是给每个 entry header 增加 `compressed` 字段。它会为每个小文件重建压缩状态，压缩率和 CPU 开销都更差，也无法让 D→D 直接转发完整流，因此不采用。另一个方案是依赖 HTTP `Content-Encoding`，它无法覆盖 Session/WebRTC，且会让字节格式不再自描述，也不采用。

### 2. 首版 codec 为 `NONE` 与 `ZSTD_FAST`

稳定协议枚举首版定义 `NONE` 和 `ZSTD`；压缩级别不是 wire format 的一部分，发送端统一使用项目配置的快速档（初始为 zstd level 1）。`NONE` 在所有目标上强制可用。Android、JVM/Desktop 和 Kotlin/Native 使用 `com.squareup.zstd:zstd-kmp-okio` 连接现有 Okio 流；该库提供 zstd 的 KMP/Okio 封装并覆盖 Android、JVM 和主要 Kotlin/Native 目标。JS/Wasm 不在其支持矩阵内，因此浏览器目标只广告 `NONE`，不能流式 FSAR 时继续逐文件回退（[zstd-kmp](https://github.com/square/zstd-kmp)）。

默认策略按管道而非文件扩展名决定：WebRTC/WAN 在双方支持时选 `ZSTD`，Session/LAN 默认 `NONE`；链接分享请求显式列出可接受 codec，服务端按相同优先级选择。后续测量可根据吞吐、CPU 压力调整策略或增加 LZ4 枚举，不改变 FSAR2 布局。

备选的 gzip 生态更广，但快速档吞吐和跨相似小文件的性价比不如 zstd；gzip-9 明确不采用。首版同时实现 zstd 与 LZ4 会扩大多平台依赖、协议矩阵和测试面，先用可扩展枚举保留 LZ4 后续空间。

### 3. 协商信息属于归档能力，实际 codec 仍写入流头

新增公共模型：

- `ArchiveStreamCapabilities(codecs, maxEntries, maxFileBytes, maxBatchBytes)`；
- `ArchiveStreamOptions(codec)`；
- `ArchiveTransferResult(completedRelativePaths, committedFileBytes)`；
- archive read request 包含源根、已规范化条目和选定 codec；archive write request 包含目标根和批次声明。

Session 在连接响应中广告能力，并增加 `ArchiveRead` / `ArchiveWrite` stream kind；具体请求放入 `DeviceSessionStreamOpen.payload`。WebRTC 设备文件 RPC 增加对偶的 archive stream 操作和分块 DataChannel 消息，不能经 `readBytes()` / `writeBytes()` 聚合成一个 `ByteArray`。`DeviceFileClient` 增加传输无关的 `archiveCapabilities()`、`readArchiveStream(...)` 和 `writeArchiveStream(...)`，Session 与 WebRTC 分别实现它们。

链接分享的 `FolderSpanArchiveDownloadRequest` 必须携带可接受 codec；缺失或没有交集时直接拒绝。响应 body 的 FSAR prelude 是最终解码依据，HTTP header 只保留诊断用途，不参与正确性判断。

这种方案让 codec 协商在发送前完成，避免已发出 body 后才发现不支持；同时即使字节流脱离原管道被 D→D 转发，接收端仍能独立解码。版本固定为当前 FSAR2，不维护开发期版本矩阵。

### 4. 编解码器以有界 chunk 流为边界

在 archive 包下拆出四层：

- `ArchiveFrameEncoder`：按条目懒读取源文件并产出未压缩帧 chunk；
- `ArchiveStreamEncoder`：写 FSAR2 prelude，并用 `NONE` 或 zstd 包裹帧区；
- `ArchiveStreamDecoder`：解析 prelude、限制解压字节并产出 entry 事件；
- `ArchiveEntryExtractor`：在授权目标根下准备文件、增量写入、提交条目并返回完成列表。

API 使用 `Flow<ByteArray>` 或等价的挂起 source/sink，缓冲上限沿用设备流推荐 chunk 与 credit window，不以批次大小分配数组。编码器不读取下一个文件，直到下游为当前 chunk 提供容量；解码器也不在内存中积累整条目。HTTP route/client 仅负责把 body channel 适配到这套 API。

备选方案是在每个 transport 内各写一套 parser。它会重复路径校验、限额与失败语义，并使未来 codec/版本演进产生三份协议，故不采用。

### 5. 复制协调器统一选择批次和端点

新增 `SmallFileArchiveTransferCoordinator`（名称可按现有包结构调整），让 `copyViaTransportClients` 与 runtime task 执行器调用统一的 `selectSmallFileArchiveBatches()`。旧 `processRuntimeCopyArchiveBatches` 不再解析协议或直接获取 `FileRouteClient`；它只把任务状态、源/目标端点和批次交给协调器。

| 用户路径 | 生产端 | 消费端 | 字节管道 |
|---|---|---|---|
| Device→Local | `DeviceFileClient.readArchiveStream` | 本地 extractor | Session 或 WebRTC |
| Local→Device | 本地 encoder | `DeviceFileClient.writeArchiveStream` | Session 或 WebRTC |
| Device→Device（不同设备） | 源设备 archive read | 目标设备 archive write | 本机有界 byte pipe |
| 原生 Share→Local / Share→Device | 批准后的源设备 | 本地或目标设备 | 继承上述设备路径 |
| 链接 Share→Local | `/api/share/archive-download` | 本地 extractor | HTTP body |

同一设备复制在进入协调器前继续走 `copyPath`。目录仍由 manifest/traversal 发现，空目录继续走批量 control RPC；归档只接管规划器选中的普通文件。

### 6. D→D 使用一进一出的结构化并发 pipe

跨设备中继在同一 coroutine scope 中启动目标 `writeArchiveStream` 和源 `readArchiveStream`，两者之间使用容量受限的 `Channel<ByteArray>`。目标消费速度形成反压；任一端失败或任务取消时关闭 channel 并取消另一端，不把源流继续读入内存。

中继只解析并校验未压缩的 FSAR2 prelude，codec 区域按原字节转发，不在本机解压再压缩。目标设备负责验证解压后的 entry header、文件字节和结束符，并通过 stream trailer / `ArchiveTransferResult` 返回已提交相对路径。源端必须能够生成目标支持的 codec；没有 codec 交集时直接报告协议错误。

### 7. 条目提交是进度、失败和恢复的唯一边界

解码器在读取 entry header 后按声明的未压缩 size 统计进度；协调器只选择生产端或消费端其中一个进度源，避免 D→D 双重计数。文件完整写入且 flush/close 成功后才加入 `completedRelativePaths`。当前条目失败时清理或截断其部分目标，之前完成的文件保留。

归档失败后，以 `batch entries - completedRelativePaths` 计算剩余条目，并交给现有单文件路径。暂停/取消通过协程取消和现有 Session/WebRTC/HTTP request registry 向整条管线传播；runtime metadata 只保存完成路径与批次计划，不序列化 zstd 上下文。恢复时从首个未提交条目新建归档批次或直接走单文件回退。

### 8. 安全检查在解压前后各执行一次

发送前复用 `FolderSpanArchiveCodec.normalizeRelativePath`，拒绝绝对路径、反斜杠、`.` / `..`、空段和重复普通文件路径；遍历层继续跳过符号链接。接收端不能信任发送端，必须再次规范化路径，并通过现有写权限/敏感路径/目标根检查准备目标文件。

FSAR2 prelude 的 entry 数、声明文件字节和声明帧字节先受硬上限约束；解压期间分别累计 entry payload、总 payload 和总 frame bytes，任何一项超限立即取消流。目标文件只在完整条目校验成功后计为提交，防止截断流或解压炸弹留下“成功”状态。

### 9. 以端到端基准决定默认启用和后续调参

建立可重复的基准 fixture（至少 10,000 个普通文件、平均小于 8 KiB），分别测量当前逐文件流、FSAR2/`NONE`、FSAR2/`ZSTD` 的墙钟、CPU、峰值内存和传输字节。覆盖 Session/LAN、WebRTC/WAN、D→D relay 与链接分享 HTTP；大文件、暂停取消、失败回退和符号链接作为回归组。

默认启用条件仍来自现有批次规划器和能力协商。目标是先证明 `NONE` 在 LAN 上消除固定成本，再验证 `ZSTD` 在 WAN 上减少字节；不把未经测量的压缩比或“数量级提升”写成正确性条件。

## Risks / Trade-offs

- [zstd-kmp 不覆盖 JS/Wasm] → `NONE` 是强制 codec；浏览器不能流式 FSAR 时沿用逐文件回退，不阻塞 native 路径。
- [FSAR2、Session 与 WebRTC 同时演进可能产生协议漂移] → 所有管道共享 FSAR2 golden byte fixtures，只协商 codec，任何非当前版本 fail closed。
- [压缩在高速 LAN 上消耗 CPU并降低吞吐] → Session/LAN 默认 `NONE`，只在带宽受限策略命中时启用快速 zstd。
- [D→D 一端失败可能让另一端悬挂] → 使用结构化并发、有限 channel 和双向取消，并以目标 trailer 作为最终提交依据。
- [部分条目已写入导致重试覆盖] → 完成列表是唯一提交记录；只回退未提交条目，当前部分文件在失败时清理或截断。
- [恶意压缩流造成内存、CPU或磁盘放大] → prelude 硬上限、增量解压计数、entry size 校验、有限 buffer 和任务取消共同限制资源。
- [旧 HTTP archive 代码与新设备协调器并存形成两套调度] → HTTP 层仅保留链接分享适配器，设备复制统一经过 `DeviceFileClient` archive API。

## Migration Plan

1. 先落唯一 FSAR2 编解码、`NONE`/`ZSTD` codec 与 golden fixtures；不保留 FSAR1 回归入口。
2. 扩展 `DeviceFileClient`、Session 与 WebRTC 协议并完成 archive read/write 测试；双方能力齐全后才广告 FSAR2。
3. 接入 D→L、L→D 和 D→D 协调器，保留单文件路径作为无能力与失败回退；确认同设备仍走 `copyPath`。
4. 将原生分享复用验证纳入设备复制测试，再让链接分享 HTTP 与 native 客户端同时切换到强制 FSAR2 请求。
5. 跑真实小文件基准并记录各平台结果，再按结果启用默认策略和调节批次/并发参数。

回滚不需要数据迁移：关闭规划器的 archive fast path 即可让当前客户端对普通设备复制回到 manifest + 单文件流；链接分享 archive 端点随当前客户端一起回滚，不发送 FSAR1。不得用恢复设备 HTTP archive 路由作为回滚方式。
