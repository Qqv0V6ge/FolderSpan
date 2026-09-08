## Purpose

使设备会话协议可以安全、可靠地运行在 WebRTC DataChannel 上，从而让 TLS 与 WebRTC 连接共享同一套认证、RPC、多路复用流、流量控制和业务处理语义。

## ADDED Requirements

### Requirement: WebRTC SHALL carry a unified Device Session
系统 SHALL 在 WebRTC 信令和 PeerConnection 建立后，通过专用 DataChannel 建立统一 Device Session。文件、路径、书签、消息及其他设备 RPC MUST 通过该会话承载，不得再并行建立第二套 WebRTC 业务协议。

#### Scenario: Establish session after peer connection
- **WHEN** 已获批准的两个设备完成 WebRTC `offer/answer/ice` 协商
- **THEN** 双方在专用 DataChannel 上完成 Device Session 建连
- **AND** 上层设备客户端仅在 Session 可用后报告业务连接成功

#### Scenario: Invoke multiple device capabilities
- **WHEN** 已连接设备依次执行文件、路径、书签或消息操作
- **THEN** 所有操作共享同一个 Device Session
- **AND** 各能力使用与 TLS Session 相同的 RPC、错误和结果语义

### Requirement: Session DataChannel SHALL be ordered and reliable
承载 Device Session 的 WebRTC DataChannel MUST 使用有序、可靠交付配置，并在会话存续期间复用。系统 MUST NOT 依赖应用层缺块重传来弥补无序或不可靠的 DataChannel 配置。

#### Scenario: Create the session channel
- **WHEN** WebRTC 连接需要创建 Device Session DataChannel
- **THEN** 通道被配置为有序交付
- **AND** 不设置有限重传次数或有限生存时间

#### Scenario: Carry consecutive operations
- **WHEN** 同一对等连接连续执行多个 RPC 或文件流
- **THEN** 它们复用现有 Session DataChannel
- **AND** 系统不为单个 RPC 或文件创建新的 DataChannel

### Requirement: WebRTC carrier SHALL preserve Session frame integrity
WebRTC 载体 SHALL 将 Device Session 的有序字节流拆分为安全大小的 DataChannel 消息，并在接收端按原顺序重组，使公共 Session 帧解析器得到内容相同的字节流。载体 MUST 对单条 DataChannel 消息和待重组数据设置安全上限，并 MUST 拒绝损坏或超限数据。

#### Scenario: Send a valid Session frame
- **WHEN** Session 向 WebRTC 载体提交一个有效帧
- **THEN** 载体可按安全消息大小拆分其编码字节
- **AND** 对端公共 Session 解析器按原顺序读到内容相同的完整帧

#### Scenario: Receive an oversized carrier message
- **WHEN** 对端发送超过协商安全上限的 DataChannel 消息
- **THEN** 载体终止受影响会话并报告协议错误
- **AND** 超限内容不进入 Session 帧解析器或业务处理器

### Requirement: WebRTC carrier SHALL apply bounded backpressure
WebRTC 载体 MUST 观察 DataChannel 待发送缓冲量，并在超过安全高水位时暂停接受更多帧，直至缓冲量降至安全低水位或操作超时。Session 的信用量控制 SHALL 继续约束每条逻辑流，载体背压不得绕过该约束。

#### Scenario: DataChannel buffer reaches high watermark
- **WHEN** DataChannel 的待发送字节达到安全高水位
- **THEN** 后续 Session 帧发送挂起而不是无限加入内存队列
- **AND** 缓冲量降至低水位后发送可继续

#### Scenario: Buffered data cannot drain
- **WHEN** DataChannel 缓冲量在规定超时内无法下降
- **THEN** 受影响操作以传输错误结束
- **AND** 系统不把未交付数据报告为成功

### Requirement: WebRTC approval SHALL bind to Session authentication
系统 SHALL 将 WebRTC 连接请求的审批结果绑定到随后在该 PeerConnection 上建立的 Device Session。绑定 MUST 限定目标设备、发起设备、连接尝试和有效期，并 MUST 防止重放；建立 Session 时 SHALL NOT 再触发一次用户审批。

#### Scenario: Approved peer establishes Session
- **WHEN** 目标设备批准连接且批准的发起方在对应 PeerConnection 上建立 Session
- **THEN** Session 认证接受该次审批绑定
- **AND** 用户不会收到第二个连接审批提示

#### Scenario: Approval binding is replayed or mismatched
- **WHEN** 已过期、已消费或属于其他设备或连接尝试的审批绑定被用于 Session 建连
- **THEN** Session 建连被拒绝
- **AND** 任何设备 RPC 或流均不会执行

### Requirement: Session lifecycle SHALL follow the WebRTC carrier
系统 SHALL 在 Session DataChannel 或 PeerConnection 关闭、失败时终止对应 Device Session，唤醒挂起操作并传播可区分的关闭原因。关闭一个 WebRTC 设备会话 MUST NOT 影响同一信令房间内的其他对等会话。

#### Scenario: Session DataChannel closes during an operation
- **WHEN** 文件流或 RPC 执行期间 Session DataChannel 关闭
- **THEN** 受影响操作结束并收到连接关闭错误
- **AND** 相关缓冲区、流和等待者被释放

#### Scenario: One peer disconnects in a multi-peer room
- **WHEN** 房间中的一个 WebRTC 对等会话关闭
- **THEN** 仅对应 Device Session 被终止
- **AND** 其他对等会话保持可用

### Requirement: Session over WebRTC SHALL support common platforms
Session over WebRTC SHALL 在项目支持的原生、JS 与 Wasm 平台提供相同的设备能力和协议行为；平台限制导致能力不可用时 MUST 在建连或能力调用前返回明确错误，不得静默回退到旧 WebRTC 业务协议。

#### Scenario: Browser connects to a native device
- **WHEN** JS 或 Wasm 客户端通过 WebRTC 连接原生设备
- **THEN** 双方建立统一 Device Session
- **AND** 浏览器通过公共设备客户端访问已授权能力

#### Scenario: Peer uses an incompatible protocol
- **WHEN** 对端不支持 Session over WebRTC
- **THEN** 连接以明确的协议不兼容错误结束
- **AND** 系统不回退到旧版自定义 WebRTC RPC 或文件流协议
