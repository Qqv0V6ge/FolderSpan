# AGENTS 指南（service/http）

本文件适用于目录 `com.folderspan.service.http` 及其子目录（client、templates 等），
以及 `serverRouteMain` 下的 `com.folderspan.routes`。原生设备直连另见 `com.folderspan.service.session`。

## 目录与职责
- `client/`：LinkShare HTTP 客户端、连接复用与请求封装（优先复用 `HttpClientFactory`）。
- `routes/`（`serverRouteMain/kotlin/com/folderspan/routes`）：LinkShare Raw HTTP API 分发与处理器；设备业务也可被 TLS 会话服务端复用。
- `templates/`：HTTP 返回的 HTML 模板/片段。
- `service/session/`：原生设备 API 的 TLS 多路会话（ALPN `folderspan/1`），不再走 HTTP/1.1。

## 目录与职责
- `client/`：HTTP 客户端、连接复用与请求封装（优先复用 `HttpClientFactory`）。
- `routes/`（`serverRouteMain/kotlin/com/folderspan/routes`）：Raw HTTP API 分发与处理器。
- `templates/`：HTTP 返回的 HTML 模板/片段。

## 编码风格与命名
- Kotlin 官方风格，4 空格缩进，建议每行 ≤120 列。
- 包名根为 `com.folderspan.service.http`。
- 类型 UpperCamelCase；方法/属性 lowerCamelCase；常量 UPPER_SNAKE_CASE。
- 多平台差异放在对应后缀文件：`*.android.kt`、`*.jvm.kt`、`*.ios.kt`、`*.js.kt`、`*.native.kt`。

## 依赖边界
- 本层不得直接依赖 UI；通过状态/事件或上层管理器（如 `HttpRouteClientManager`）交互。
- 公共模型/协议优先放在 `commonMain`，平台差异通过 expect/actual 或后缀文件隔离。

## 协议与序列化
- 原生设备 API 使用 TLS + ALPN `folderspan/1` 多路会话：控制 RPC 与文件流共用一条连接，帧 payload ≤64KiB。Connect 之后 token 绑定到该 TLS 会话，后续帧不再携带 Bearer。
- 设备监听器不解析 HTTP/1.1；未协商到 `folderspan/1` 的连接直接关闭。浏览器/JS/Wasm 不连 `12040` 会话端口。
- LinkShare 仍走 HTTP/HTTPS：Content-Type 固定为 `ContentType.Application.ProtoBuf`，业务请求/响应使用 `ProtoBufCodec` 直接编解码；传输安全由 TLS 提供，不再叠加应用层对称加密。
- 设备 API 只允许 TLS 传输；JS/Wasm 不再使用 HTTP 加密载荷兼容通道。收到旧 `X-FolderSpan-Encrypted: v1` 设备 API 请求时必须拒绝进入业务分发。
- LinkShare 客户端请求必须携带 `Content-Type: application/x-protobuf`。
- 服务端在 `RawHttpApiDispatcher` 中校验 LinkShare Content-Type 与鉴权；设备会话鉴权失败发送 goaway，不再返回 HTTP 401。
- `@Serializable` 数据类字段必须标注 `@ProtoNumber(tag)`，tag 从 1 递增且只追加不复用。

## 共享审批短轮询
- 设备共享审批使用 `/api/share/heartbeat` 的短轮询（约 1s），不再使用 SSE。
- 原生端将该路由放在独立、证书钉扎的 TLS HTTP 审批监听器上（默认 `12042`），端口通过 LAN beacon / Session Identify 发布；禁止把 HTTP 心跳发往只接受 ALPN `folderspan/1` 的 Session 端口（默认 `12040`）。
- 当前协议处于开发阶段，不保留旧端点兼容：审批端口缺失、非法或与 Session 端口相同时直接拒绝，禁止回退复用 Session 端口。
- 客户端在收到 `COMPLETED`/`REJECTED`/`ERROR` 后停止轮询，`WAITING` 继续轮询。

## Protobuf 顶层响应约束（必须遵守）
- `protobuf(...)`/`ProtoBufCodec.encode(...)` 的顶层实参静态类型必须为非空。
- 禁止使用 `getOrNull()?.copy(...)` 等写法导致类型变为 `X?`。
- 失败场景用 HTTP 状态码或 `SerializableResult<T>`，不要用 `null` 表达失败。

原因：Raw HTTP 响应按非空顶层消息解码；若编码端静态类型变为可空，
客户端按非空解码会产生 `ProtobufDecodingException`。

示例（禁止 → 正确）：
```kotlin
// 禁止（顶层可空）
val info = result.getOrNull()?.copy(protocol = FileProtocol.Device)
protobuf(info) // T 被推断为 FileInfo?

// 正确（顶层非空）
val info = result.getOrThrow().copy(protocol = FileProtocol.Device)
protobuf(info) // T 为 FileInfo
```

## 协程、并发与资源管理
- 不使用 `GlobalScope`；在生命周期内注入/传入 `CoroutineScope`。
- 高并发时使用 `Semaphore`/`Mutex` 控制并发与一致性。
- 长生命周期或易被 UI 取消的任务，用 `SupervisorJob` 包装。
- 客户端实例尽量复用，避免每次请求都新建 `HttpClient`；按需配置超时与连接池。
- 释放 I/O 资源（流、通道、句柄），`HttpClient` 在卸载时关闭。

## 错误处理与日志
- 明确区分网络错误、协议错误与业务错误；统一映射到 `HttpStatusCode`。
- 统一返回 `Result<T>` 或抛出受控异常；避免在日志中泄露令牌、密钥、绝对路径。
- 仅使用 `LogKit`：
  - `LogKit.i`：关键状态/进度（传输开始/进度/完成）。
  - `LogKit.d`：必要调试细节，避免噪声。
  - `LogKit.e`：错误与异常（携带 `throwable`）。
- 传输进度日志按现有实现输出百分比/速度/剩余时间，频率约 1s。

## 接口新增联动（routes → client → Device）
- 在 `routes/` 新增接口时，必须同步：
  - `client/` 或管理器中添加同名/等价调用方法。
  - `core/src/commonMain/kotlin/com/folderspan/data/main/Drawer.kt` 的 `Device` 中新增业务方法。
  - 更新 HTTP 示例 `http/linkShare/*.http` 与必要文档。
- 命名建议：
  - 路由：REST 风格（动词用 HTTP Method 表达，路径用资源名）。
  - 客户端：`fun shareFile(id: String, ...)`，必要时提供 `suspend` 版本。
  - Device：`suspend fun shareFile(...)`，内部委托 client/manager 并处理状态与日志。

## 测试
- 纯逻辑测试放在 `core/src/commonTest` 或 `app/shared/src/commonTest`。
- Raw HTTP 分发逻辑优先用 `RawHttpApiDispatcher` 级别测试覆盖。
- 使用 `kotlin("test")` 断言，避免真实网络请求。

## 安全与兼容
- 禁止提交密钥、私有证书或本地绝对路径；遵循 `SECURITY.md`。
- 令牌只在内存中传递与校验，不写日志、不持久化。
- 调整并发/缓冲等参数时评估 Android/JVM/JS/iOS 影响。

## 提交与文档
- 提交信息遵循 Conventional Commits（如 `feat(service/http): ...`）。
- 路由/协议变更需同步更新示例与说明；Protobuf 结构变更在提交描述中注明影响范围与兼容策略。

## 提交前自检
- 搜索 `protobuf(` 和 `ProtoBufCodec.encode(`，确认顶层实参静态类型为非空。
- 避免 `?.` 链导致顶层类型可空；必要时先绑定非空局部变量。
- 失败路径使用 HTTP 状态码或 `SerializableResult<T>`。
