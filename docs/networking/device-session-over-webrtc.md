# Device Session over WebRTC

## 目标

WebRTC 只承担 Device Session 的字节载体职责。文件、路径、书签、消息、复制与 FSAR 不再拥有 WebRTC 专用 RPC、分块或重传协议；认证完成后，它们与 TLS 连接使用同一组 Session 客户端和服务端分发器。

这是开发阶段的一次性协议切换，不提供旧 `control`、`payload`、`file:*` DataChannel 或 token-per-call RPC 的兼容回退。

## 建连与认证

1. 发起方通过 WebSocket 或 HTTP 信令发送带 `connectionAttemptId` 的连接请求。
2. 目标端完成手动、自动、账号设备或受限分享审批后，在内存中签发短时一次性启动授权。授权绑定发起设备、目标设备、连接尝试、角色和可选分享路径范围。
3. Offer 发起方创建唯一的 `folderspan-device-session-v1` DataChannel。通道固定为可靠、有序且不设置最大重传次数；应答方拒绝其他标签或重复通道。
4. 发起设备使用信令返回的启动授权执行普通 Session Connect。目标端原子消费授权，验证全部绑定字段后才签发正常 Session token 并建立身份上下文。
5. Connect 成功后才发布 `Connected`，安装公共文件、路径、书签、FSAR 和消息客户端。启动授权不会进入持久化、日志或后续业务 RPC。

## 字节通道与资源上限

`WebRtcDeviceSessionByteChannel` 隐藏 DataChannel 消息边界，为 Session 提供连续读写：

- 出站每条 DataChannel 消息最多 16 KiB；Session 帧可跨任意消息边界。
- 入站单消息最多 16 KiB，最多排队 512 条、累计 8 MiB；任何超限都会关闭载体。
- `bufferedAmount` 达 4 MiB 后暂停发送，降到 1 MiB 后恢复，30 秒未排空则失败。
- 每个 peer 使用独立 `SupervisorJob`、字节通道和 Session 运行时；DataChannel、PeerConnection、Session 任一关闭都会沿关闭链唤醒挂起读写并释放资源。

Session 自身继续负责全局与单流信用量、并发文件流、RPC 复用、RST/GOAWAY、暂停/恢复/取消终态和不完整目标清理。适配器不再实现第二套确认或缺失区间重传。

## 平台装配

- JVM、Android 与 iOS 可作为 Session 客户端或目标端服务端。
- JS/Wasm 浏览器通过 HTTP 信令作为连接发起方，并使用公共 Session 客户端；浏览器不启动本地设备服务端。
- TLS 仍使用相同的公共 Session 运行时，只在建连阶段选择 TLS 字节通道或 WebRTC 字节通道。

## 验证

核心契约由以下测试覆盖：

- `WebRtcDeviceSessionByteChannelTest`：分片/重组、帧头跨消息、背压、超时、关闭、容量上限和并发读写。
- `DeviceSessionBootstrapAuthorizationRegistryTest`：绑定不匹配、过期、重放和并发原子消费。
- `WebRtcDeviceSessionIntegrationTest`：单 Session DataChannel 上的多文件流与 RPC 并发，以及单流取消不影响会话。
- 既有 Device Session 测试：文件、路径、书签、消息、FSAR、信用量、RST/GOAWAY 与权限错误语义。

常用验证命令：

```bash
./gradlew :core:jvmTest
./gradlew :app:shared:jvmTest
./gradlew :core:compileAndroidMain :core:compileKotlinJs :core:compileKotlinWasmJs
```

iOS 完整构建仍需在 macOS 上通过 Xcode 执行。
