# Why

当前 TLS Session 与 WebRTC 分别维护设备 RPC、文件流、背压、取消、路径、书签和消息传输逻辑。两套实现已出现相同业务语义由不同协议和状态机承载的情况，新增能力需要重复接入、测试和修复，也容易产生平台行为差异。

现有 Device Session 已具备连接认证、RPC、多路复用流、信用量控制、生命周期和错误映射能力。将它抽象为与底层载体无关的统一设备会话协议，并让 WebRTC DataChannel 仅承担帧载体职责，可以显著减少重复代码，同时保持 WebRTC 的浏览器直连和 NAT 穿透能力。

# What Changes

- 复用并完善现有 Device Session 字节通道边界，由 TLS Socket 与 WebRTC DataChannel 分别提供通道实现，保持 Session 帧解析和状态机只有一套。
- WebRTC 完成信令、SDP/ICE 和连接审批后，在专用的有序可靠 DataChannel 上建立 Device Session；已有审批结果绑定到会话认证，不再弹出第二次审批。
- 文件、目录、路径、书签、设备消息、权限检查、进度、暂停/恢复/取消和小文件 FSAR 统一复用 Session 客户端、服务端处理器与流控制逻辑。
- WebRTC 载体负责 DataChannel 消息边界、最大消息大小、`bufferedAmount` 背压和关闭传播；Session 继续负责协议级多路复用与信用量控制。
- 浏览器 JS/Wasm 与原生平台通过同一套 Session 上层 API 使用 WebRTC 载体。
- 在行为与测试达到一致后，删除或收薄 WebRTC 专用的设备 RPC、文件流、分块确认/缺块重传及对应业务分支。
- **BREAKING**：开发期内直接切换 WebRTC 设备协议，不兼容旧版自定义 WebRTC RPC/文件流线协议；连接双方必须升级到支持 Session over WebRTC 的版本。
- 链接方式分享（LinkShare）的 HTTP/HTTPS 下载协议不变。

# Capabilities

## New Capabilities

- `device-session-over-webrtc`: 定义 Device Session 在有序可靠 WebRTC DataChannel 上的载体、建连、认证绑定、背压、关闭和跨平台行为。

## Modified Capabilities

- `webrtc-device-rpc`: 将设备级 WebRTC 自定义 RPC 信封改为统一 Device Session RPC，同时保留先审批后创建 Offer 的安全边界。
- `webrtc-device-file-rpc`: 文件操作通过 WebRTC 承载的 Device Session RPC/流执行，并复用统一权限、结果和任务语义。
- `webrtc-device-path-rpc`: 远端路径浏览通过 WebRTC 承载的 Device Session 路径客户端与处理器执行。
- `webrtc-device-bookmark-rpc`: 远端书签通过 WebRTC 承载的 Device Session 书签客户端与处理器执行。
- `webrtc-device-file-transfer-performance`: 用 Session 多路复用流、信用量和统一任务控制替代 WebRTC 专用串行载荷传输计划。
- `webrtc-data-channel-file-transfer`: WebRTC 测试页改为验证 Session over WebRTC 文件传输，不再维护独立的文件块确认与缺失区间重传协议。
- `connect-browser-device-webrtc`: 浏览器完成 WebRTC 信令后建立统一 Device Session，并通过公共设备客户端访问远端能力。

# Impact

- 主要影响 `core` 中 `service/session`、`service/webrtc`、设备客户端/服务端处理器，以及 `app/shared` 中 WebRTC 测试页和相关连接状态展示。
- `Device` 的 Session/WebRTC 分支将收敛为相同的会话客户端集合，WebRTC 仅保留信令、PeerConnection 与载体生命周期差异。
- 需要补充载体契约测试、TLS/WebRTC 参数化会话测试、浏览器 JS/Wasm 测试，以及文件/目录/书签/消息的跨载体回归测试。
- 与活跃变更 `add-connected-device-messaging`、`add-stream-compression-to-small-file-transfers`、`share-to-device-via-device-copy` 集成：其业务能力保持不变，WebRTC 适配层迁移到统一 Session 通道。
- 不新增第三方依赖，不改变 LinkShare 对外协议。
