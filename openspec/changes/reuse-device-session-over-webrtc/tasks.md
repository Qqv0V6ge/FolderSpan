## 1. 建立行为与性能基线

- [x] 1.1 为现有 Device Session 补齐文件、路径、书签、消息、FSAR、复制进度和权限错误的公共行为测试
- [x] 1.2 为现有 WebRTC 文件传输记录单文件、多文件、并发、暂停/恢复/取消及连接中断的回归用例
- [x] 1.3 记录 JVM、浏览器与至少一个移动端的吞吐、峰值内存、DataChannel 缓冲量和并发任务基线

## 2. 抽取公共 Device Session 运行时

- [x] 2.1 从 `DeviceSessionClientManager` 抽取接收 `DeviceSessionByteChannel` 和认证上下文的公共客户端 Session 启动器
- [x] 2.2 将私有 `DeviceSessionClients` 提取为可由 TLS 与 WebRTC 共用的模块内客户端集合/工厂，并覆盖文件、路径、书签和 FSAR 接口
- [x] 2.3 抽取公共消息端点创建与清理逻辑，使两种载体复用相同的 Session 消息 RPC
- [x] 2.4 抽取接收字节通道、审批上下文和现有服务分发器的公共服务端 Session 启动器
- [x] 2.5 将 TLS 客户端和服务端迁移到公共启动器，并验证 ALPN、证书指纹、Identify、Connect、心跳和断开行为不变
- [x] 2.6 增加公共 Session 运行时单元测试，覆盖启动失败、认证失败、关闭顺序和资源释放

## 3. 实现 WebRTC Session 字节通道

- [x] 3.1 定义带主版本的 Session DataChannel 标签、可靠有序配置、保守消息大小和高低水位参数
- [x] 3.2 实现 `WebRtcDeviceSessionByteChannel` 的出站分片、发送失败传播和安全消息大小限制
- [x] 3.3 实现入站消息的有界排队、跨消息连续读取、累计字节限制和超限关闭
- [x] 3.4 实现基于 `bufferedAmount` 的高低水位等待、超时与协程取消，确保只有适配器维护背压循环
- [x] 3.5 将 DataChannel/PeerConnection 关闭映射为字节通道与 Session 关闭，并唤醒全部挂起读写
- [x] 3.6 为字节通道增加分片/重组、帧头跨消息、背压、超时、关闭、超限和并发读写契约测试
- [x] 3.7 验证 JVM、Android、iOS、JS 与 Wasm 的 DataChannel 实现都能提供适配器需要的状态、消息和缓冲量行为

## 4. 绑定 WebRTC 审批与 Session 认证

- [x] 4.1 定义通用的 Session 启动授权模型，包含不透明授权、连接尝试 id 和预审批类型，且不影响标准 TLS Connect
- [x] 4.2 实现目标端短时一次性授权注册表，绑定发起设备、目标设备、连接尝试、角色范围、过期和原子消费状态
- [x] 4.3 在手动审批、自动审批和账号设备审批成功时签发授权，并通过定向信令响应安全返回发起方
- [x] 4.4 在 WebRTC-backed Session Connect 中验证设备身份、连接尝试、过期、角色范围和消费状态，成功后建立普通 Session 身份上下文
- [x] 4.5 禁止启动授权进入日志、持久化或后续业务 RPC，并确保授权失败不会触发第二次 UI 审批
- [x] 4.6 增加合法授权、设备不匹配、连接不匹配、过期、重放、并发消费、拒绝和无二次审批测试

## 5. 将 WebRTC 连接接入公共 Session

- [x] 5.1 让 Offer 发起方创建唯一的可靠有序 Session DataChannel，并让应答方只接受匹配版本和配置的通道
- [x] 5.2 为每个 WebRTC peer 建立独立协程作用域、字节通道、客户端/服务端 Session 和关闭链路
- [x] 5.3 在 WebRTC Session 完成认证后才发布设备已连接状态和安装公共文件、路径、书签及消息客户端
- [x] 5.4 将 `Device` 与分享至其他设备流程改为只在建连阶段选择 TLS/WebRTC 载体，业务调用统一使用 Session 客户端
- [x] 5.5 将 WebRTC 小文件归档读写接到公共 Session FSAR 流，并验证编解码、进度与清理行为一致
- [x] 5.6 更新 JS/Wasm 浏览器设备连接，在 HTTP 信令完成后建立 Session 并使用公共设备客户端
- [x] 5.7 更新 WebRTC 测试页，使其显示信令、PeerConnection、DataChannel、Session 认证阶段并通过公共 Session 发送/接收测试文件
- [x] 5.8 为协议版本不匹配、握手超时和 Session 协议错误提供明确的连接失败状态与中英文文案

## 6. 验证文件任务与并发语义

- [x] 6.1 参数化运行 TLS 与 WebRTC 的文件读写、目录遍历、书签、复制和消息等价性测试
- [x] 6.2 验证多个文件流与 RPC 在同一 Session DataChannel 上并发推进且受全局/单流信用量限制
- [x] 6.3 验证暂停、恢复、取消或失败一个文件流不会关闭 Session 或影响其他流
- [x] 6.4 验证 DataChannel 缓冲超时、连接中断和 Session GOAWAY/RST 均产生正确终态并清理不完整目标
- [x] 6.5 回归“分享到其他设备”的心跳审批、WebRTC 建连、远端 Device Copy、进度和取消流程
- [x] 6.6 回归 TLS/WebRTC 共用 Session 业务链路的敏感路径、符号链接边界、角色权限和批量结果顺序，并验证已退休的 HTTP 设备业务入口返回 404（夹具与真实网络边界见 verification.md）

## 7. 删除旧 WebRTC 业务协议

- [x] 7.1 删除或收薄 `WebRtcDeviceFileClient`、`WebRtcDevicePathClient`、`WebRtcDeviceBookmarkClient` 及其专用装配分支
- [x] 7.2 删除 WebRTC 专用设备 RPC/文件流模型、分发器、token-per-call 接线和消息适配器
- [x] 7.3 删除 `WebRtcChunkCodec`、块确认、缺失区间重传、串行传输计划及对应生产状态机
- [x] 7.4 删除控制/可复用载荷 DataChannel 的业务角色、创建与重建分支，只保留 Session 通道和纯信令/连接测试所需基础设施
- [x] 7.5 将仍有价值的旧测试改写为公共 Session 或 WebRTC 字节通道测试，并删除只验证已移除协议的测试
- [x] 7.6 检查生产源码不存在旧 WebRTC 业务协议引用、兼容回退或按传输类型分派文件/路径/书签/消息的逻辑

## 8. 全平台验证与文档收尾

- [x] 8.1 运行 `./gradlew :core:jvmTest` 和 `./gradlew :app:shared:jvmTest`，修复全部回归
- [x] 8.2 运行 Android、Desktop、JS、Wasm 相关编译/测试任务，并通过 Xcode 验证 iOS 构建
- [x] 8.3 执行浏览器到原生、原生到原生、多 peer、重连和双方版本不匹配的集成测试
- [x] 8.4 对比迁移前后的吞吐、内存、缓冲量与并发结果，确认没有不可接受的性能退化或无界队列
- [x] 8.5 清理废弃中英文资源与文档，补充 Session over WebRTC 架构说明，并同步 `md_descriptions_paths.md`
- [x] 8.6 运行 `openspec validate reuse-device-session-over-webrtc --strict` 并确认全部制品与实现任务一致

验证证据、性能取舍与覆盖边界参见 [verification.md](./verification.md)。
