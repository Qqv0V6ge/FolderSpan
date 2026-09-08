## Context

参见 [proposal.md](./proposal.md) 的动机。当前 Device Session 已经将协议状态机与底层 TLS I/O 分开：`DeviceSessionTransport` 依赖 `DeviceSessionByteChannel`，在其上完成帧编解码、批量写入和连接调度；`DeviceSessionConnection` 再提供逻辑流、信用量、Ping/Pong、RST 与 GOAWAY。`DeviceSessionClients` 已覆盖文件、路径、书签与归档流，但仍私有地绑定在 TLS Session 客户端管理器中。

现有 `WebRtcDataChannel` 是消息型接口，提供 `send(ByteArray)`、`onMessage`、`bufferedAmount` 与关闭事件。生产 WebRTC 还维护自定义 RPC 信封、控制通道、可复用载荷通道、块确认/缺块重传和独立客户端。这些功能与 Session 上层重复，但信令、SDP/ICE、PeerConnection 和 DataChannel 生命周期仍然只能由 WebRTC 层负责。

约束如下：

- WebRTC DataChannel 由 SCTP/DTLS 提供有序可靠消息，但不是任意长度的连续字节流；平台安全消息大小与缓冲行为存在差异。
- JS/Wasm 与原生平台必须共用 `commonMain` 协议实现。
- 现有 Session TLS 使用 `folderspan/1` ALPN，不能因本次改造改变行为。
- WebRTC 已在 Offer 前完成设备连接审批；Session 建立时不能再次触发用户审批。
- 当前处于开发期，允许一次性线协议切换，不要求兼容旧版 WebRTC 业务协议。

## Goals / Non-Goals

**Goals:**

- 让 TLS 与 WebRTC 只保留底层建连和 I/O 差异，共享完整 Device Session 协议、客户端、处理器和任务语义。
- 以最小范围改造现有 Session 核心，优先增加薄适配器和公共工厂，而不是重写帧层。
- 对 DataChannel 的消息大小、发送缓冲、关闭和内存队列建立明确上限。
- 将 WebRTC 预审批安全地转换为一次 Session 身份绑定，避免重复审批和授权绕过。
- 完成切换后删除 WebRTC 专用业务传输代码及其维护负担。

**Non-Goals:**

- 不统一 LinkShare 的 HTTP/HTTPS 下载协议。
- 不替换 WebRTC 信令服务、SDP/ICE 或 PeerConnection 平台实现。
- 不修改 FSAR 文件格式、文件任务调度策略或现有角色/敏感路径策略。
- 不为旧版 WebRTC 自定义 RPC、文件流或载荷通道提供运行时兼容回退。
- 不把底层 SCTP 拥塞控制与 Session 信用量合并为一套算法；两者解决不同层次的资源约束。

## Architecture

```text
Device / 分享到其他设备 / 文件任务 / 消息
                    │
          公共 Device Session 客户端与处理器
                    │
        RPC + 多路复用流 + 信用量 + FSAR
                    │
          DeviceSessionTransport / Io
              ┌─────┴─────┐
              │           │
       TLS ByteChannel   WebRTC Session ByteChannel
              │           │
         TLS Socket     ordered reliable DataChannel
                              │
                    signaling + SDP/ICE（仅建连）
```

职责边界：

| 层级 | 统一复用 | 保留差异 |
|---|---|---|
| 设备业务 | 文件、路径、书签、消息、权限、进度、取消、FSAR | 无 |
| Session | 帧、RPC、逻辑流、信用量、心跳、关闭 | 无 |
| 通道 | 连续读写与关闭契约 | TLS Socket；DataChannel 分片、重组与背压 |
| 建连 | 建连成功后交给公共 Session 启动器 | TLS/ALPN；WebRTC 审批、信令、SDP/ICE |

## Decisions

### 1. 复用字节通道边界，不引入第二套帧载体

新增 `WebRtcDeviceSessionByteChannel`（最终命名可遵循现有包约定）实现现有 `DeviceSessionByteChannel`：

- 写入时将 `DeviceSessionTransport` 产生的字节批次切成不超过 `carrierChunkBytes` 的 DataChannel 消息。Session 当前约 1 MiB 的写批次无需感知 DataChannel 限制。
- 读取时按 `onMessage` 到达顺序进入有界队列，`read` 可跨消息边界填充调用方缓冲区，从而向 `DeviceSessionIo.readFrame` 呈现连续字节流。
- 单条入站消息、累计未消费字节和队列元素数均受上限约束；超限直接关闭 Session。
- DataChannel 关闭或发送失败会关闭通道并以同一原因唤醒挂起的读写协程。

选择该方案是因为它可以原样复用 `DeviceSessionTransport`、`DeviceSessionIo` 和帧验证，也不会迫使 TLS 通道迁移到新接口。

备选方案：

- 新建 `DeviceSessionFrameCarrier`，让 TLS 和 WebRTC 都直接发送帧列表。边界更显式，但需要同时重写稳定的 TLS I/O、批处理与测试，收益不足。
- 让每条 DataChannel 消息严格对应一个 Session 帧。实现简单，但大帧和平台消息上限强耦合，难以复用现有批量写入，并会把载体限制泄漏到帧层。
- 保留 WebRTC 自定义 RPC/文件流，仅复用业务接口。改动较少，但无法消除主要状态机和协议维护成本。

### 2. 每个 PeerConnection 使用一个专用有序可靠 Session DataChannel

由 Offer 发起方创建固定版本标签的专用通道，例如 `folderspan-device-session-v1`；应答方只接收匹配标签且配置为 `ordered = true`、无限重传的单一通道。该通道承载控制 RPC 和全部逻辑流，并在 PeerConnection 生命周期内复用。

DataChannel 消息只作为 Session 字节流分片，不再区分 WebRTC 控制通道与文件载荷通道。并发和公平性由 Session stream id、最大活动流数与信用量控制完成。

选择单通道是为了避免 Session 多路复用之上再次维护通道角色、通道重建和串行传输计划。多 DataChannel 理论上可减少队头阻塞，但会重新引入调度与生命周期复杂度；在有性能证据前不采用。

### 3. 载体背压与 Session 信用量分层工作

通道写入使用高低水位：发送前若 `bufferedAmount` 达到高水位，则挂起至低水位、关闭或超时；`send` 返回失败立即终止写入。优先复用平台的低水位事件；平台抽象暂不支持时，集中在适配器内使用有上限的短间隔检查，旧 WebRTC 发送循环删除后不再存在多份轮询逻辑。

安全消息大小按以下顺序确定：双方可用限制的最小值、平台已知限制、跨平台保守默认值。首个 Session 控制消息使用保守默认值，能力交换完成后才允许提高。由于字节通道可跨消息重组，Session 最大帧大小无需等于 DataChannel 最大消息大小。

Session 信用量仍限制逻辑流和会话级在途数据；DataChannel 水位只限制已交给 SCTP 但尚未排空的数据。两层限制同时生效，任何一层都不能用来绕过另一层。

### 4. 抽取公共 Session 启动器和客户端集合

把当前 TLS 管理器中的工作拆成两部分：

1. 载体建连器负责得到 `DeviceSessionByteChannel` 与载体认证上下文。
2. 公共 Session 启动器负责窗口计划、`DeviceSessionTransport`、`DeviceSessionPeer`、Connect/Identify、服务端分发、心跳和客户端集合。

`DeviceSessionClients` 从 TLS 管理器的私有实现移动为模块内公共工厂产物，同时实现既有 `DeviceFileClient`、`DevicePathClient`、`DeviceBookmarkClient` 和消息端点接口。TLS 与 WebRTC 的连接管理器都返回同一客户端集合；`Device` 仅在建连阶段选择载体，连接后不再按传输类型选择文件、路径、书签或消息实现。

服务端复用同一 RPC 分发器、文件流处理器、权限服务、复制控制和消息处理器。FSAR 编解码与协调器继续位于公共层，WebRTC 不再实现自己的归档流接线。

### 5. WebRTC 审批结果转换为一次性 Session 启动授权

WebRTC 目标设备在现有连接审批通过后签发高熵、短时、一次性的启动授权，并在本地登记：目标设备 id、发起设备 id、连接尝试 id、审批角色范围、过期时间和消费状态。授权通过定向信令审批响应返回发起方，禁止记录到日志或持久化。

首个 Device Session Connect 请求携带通用的 `bootstrapAuthorization`，其类型标记为预审批连接并包含不透明授权和连接尝试 id。目标端验证：

- 授权存在、未过期且未消费；
- 发起/目标设备 id 与已有设备身份校验一致；
- 授权属于当前 PeerConnection 的连接尝试；
- 当前角色和权限没有比审批时扩大。

验证成功后原子消费授权，并创建与普通 Session 相同的会话身份/令牌上下文；后续 RPC 从 Session 上下文取身份，不再携带 WebRTC 专用 token。验证失败直接拒绝 Session，不触发第二次 UI 审批。自动审批和账号设备审批也走相同的授权签发路径。

备选方案是让 WebRTC 建连后再次调用现有标准 Connect 审批，但会产生双提示并允许信令审批与 Session 审批状态漂移；因此不采用。

### 6. 使用明确版本标签并执行一次性协议切换

DataChannel 标签包含 Session over WebRTC 主版本。建立通道后仍使用现有 Session Connect/能力交换确认具体能力；标签不匹配、握手超时或协议错误均以“不兼容”结束。

不实现旧 WebRTC RPC/文件流探测与回退。这样可以在删除旧代码后确保任何测试失败都暴露真实迁移缺口，而不是被静默回退掩盖。连接双方版本不一致时向用户显示可区分的升级提示。

### 7. Session 状态成为设备业务连接状态的唯一依据

WebRTC 状态分为信令已连接、PeerConnection 已连接、Session DataChannel 已打开、Device Session 已认证四个阶段。只有最后一个阶段才能发布 `Device` 已连接和安装业务客户端。

DataChannel/PeerConnection 关闭时，由单一父级协程作用域依次关闭字节通道、Session、业务端点和设备状态。一个 peer 使用独立作用域与 Session，避免多 peer 房间中相互取消。

### 8. 以行为等价测试保护删除工作

测试分四层：

- 字节通道契约：消息分片/重组、跨消息读取、有界入站队列、高低水位、发送失败、关闭与超时。
- Session 参数化测试：同一组 RPC、并发流、信用量、RST/GOAWAY、暂停/取消用例分别运行在内存通道和假 WebRTC 通道上。
- 审批安全测试：合法绑定、设备不匹配、连接尝试不匹配、过期、重放、并发消费及无二次审批。
- 跨平台与集成测试：JVM/Android/iOS/JS/Wasm 编译，浏览器到原生设备的文件、路径、书签、消息、FSAR 和“分享到其他设备”回归。

只有公共 Session 路径覆盖原 WebRTC 行为且测试通过后，才删除 `WebRtcDeviceFileClient`、`WebRtcDevicePathClient`、`WebRtcDeviceBookmarkClient`、WebRTC 业务 RPC 模型、`WebRtcChunkCodec`、缺块追踪、专用消息适配器及控制/载荷通道业务分支。仍被纯 WebRTC 测试工具使用的信令与 PeerConnection 基础设施保留。

## Risks / Trade-offs

- [Risk] 单个可靠有序 DataChannel 可能发生 SCTP 队头阻塞，极端丢包下并发流相互影响 → 先依靠 Session 公平调度和信用量限制，并建立吞吐/延迟基线；只有数据证明必要时再设计多通道载体，避免预先恢复双层调度。
- [Risk] 当前 1 MiB Session 写批次超过部分平台安全消息大小 → 字节通道适配器强制分片，初始使用跨平台保守值，协商后只能收紧或在双方确认后放宽。
- [Risk] `bufferedAmount` 事件能力在平台实现间不一致 → 将事件或有界轮询封装在唯一适配器内，并对超时、关闭和取消建立契约测试。
- [Risk] WebRTC 审批与 Session 身份绑定错误可能造成授权绕过或重复提示 → 使用短时一次性授权、连接尝试绑定、原子消费和设备身份证明，并在任何不匹配时失败关闭。
- [Risk] 一次性删除旧协议会使新旧版本无法互通 → 通过版本标签给出明确升级错误；开发期按要求不维护兼容层。
- [Risk] 私有 Session 客户端抽取可能改变 TLS 行为 → 先以工厂重构且保持 TLS 调用与测试不变，再接入 WebRTC；公共测试必须同时覆盖两种载体。
- [Trade-off] 以消息分片模拟字节流会产生少量拷贝和队列开销 → 换取复用成熟帧解析与协议状态机；通过复用缓冲、批量读取和基准测试控制开销。

## Migration Plan

1. 为当前 TLS Session 和 WebRTC 对外行为补齐等价性测试与性能基线，不改变生产路径。
2. 抽取公共 Session 启动器、服务端运行时与客户端集合；让 TLS 改用该入口并完成全量回归。
3. 实现 WebRTC Session 字节通道、固定可靠通道配置、背压与关闭契约测试。
4. 实现一次性预审批启动授权，并完成安全与并发消费测试。
5. 将原生、JS/Wasm WebRTC 连接接入公共 Session，更新设备连接状态和测试页；验证文件、目录、书签、消息、FSAR、并发和任务控制。
6. 原子切换生产 WebRTC 设备路径，删除旧 RPC、文件流、块确认/重传、控制/载荷业务通道和传输类型分支。
7. 运行核心、共享 UI、各目标编译和跨设备集成测试，记录切换前后吞吐、内存与并发结果。

回滚通过回退本次版本或变更提交完成，不在运行时协商回退到旧协议。旧代码删除前可在开发分支对比两条路径，但不得作为发布后的长期兼容开关。
