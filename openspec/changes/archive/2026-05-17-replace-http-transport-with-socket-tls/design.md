## Context
现有设备 API 使用 Ktor server CIO 暴露 `/ping` 与 `/api/*`，文件内容在明文 HTTP 内额外包装加密帧。该结构让业务数据安全依赖应用层加密协议，并使大文件读写产生额外 CPU 与内存开销。

## Goals / Non-Goals
- Goals: 设备 API 默认 HTTPS；抓包不可见 HTTP request line、headers、protobuf body 和文件内容。
- Goals: 发现信息携带 `httpsPort` 与证书 SHA-256 指纹，批准连接后持久化指纹。
- Goals: 文件内容只由 TLS 保护，不再套 `X-FolderSpan-Encrypted-Stream` 加密帧。
- Goals: 保留现有业务 protobuf DTO 与 `CryptoProtoBuf`。
- Non-Goals: 不兼容旧明文 HTTP 客户端、旧 Ktor route server 或旧文件加密帧 wire format。
- Non-Goals: 不优化浏览器自签证书信任提示。

## Decisions
- 设备 API 使用 raw HTTP/1.1 dispatcher，平台 server 负责 TLS socket、request parsing 和 response 写回。
- JVM/Android 使用 `SSLServerSocket` 加载本机持久化自签名证书；证书 DER 的 SHA-256 十六进制值作为设备指纹。
- 发现阶段使用 HTTPS discovery client 接收 `/ping` 响应中的指纹；连接阶段若已有保存指纹则必须匹配，随后使用 certificate pinning HTTPS client。
- `/api/files/read-bytes`、`/api/files/write-bytes`、`/api/share/read-bytes` 成功 body 为 `application/octet-stream` 明文字节流；失败仍为加密 protobuf failure。
- 业务 API request/response body 保持 `CryptoProtoBuf`，即使 TLS 已覆盖传输层。
- iOS 设备 API 服务端在平台 TLS 封装落地前不再启动旧 Ktor 明文 listener，避免提供 HTTP fallback。

## Risks / Trade-offs
- 自签名证书对浏览器不友好，用户访问浏览器分享链接可能看到证书警告。
- iOS 的 server-side TLS 需要平台能力封装，先以接口保留边界，JVM/Android 作为可编译主实现。
- 这是 breaking wire format，旧客户端无法继续连接。
