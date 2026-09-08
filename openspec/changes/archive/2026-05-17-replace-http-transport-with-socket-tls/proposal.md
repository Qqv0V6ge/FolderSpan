# Change: Replace HTTP Transport With Socket TLS

## Why
设备间文件传输仍运行在明文 HTTP/Ktor CIO 上，业务 protobuf 依赖应用层对称加密，文件热路径还存在额外加密帧和拷贝成本。需要把传输安全边界下沉到 TLS，让抓包不可见 URL、header、body 和文件内容，同时让文件内容在 TLS 内部以普通字节流传输。

## What Changes
- **BREAKING** 设备 API 只通过 HTTPS 访问；不提供明文 HTTP fallback。
- **BREAKING** 设备发现、连接、共享轮询、文件复制 URL 改用 `https://host:httpsPort`。
- **BREAKING** `/api/files/read-bytes`、`/api/files/write-bytes` 与 `/api/share/read-bytes` 的成功文件内容 body 不再使用应用层加密帧。
- 新增每设备自签名 TLS 身份，发现信息携带证书 SHA-256 指纹。
- 客户端连接前校验已保存的设备证书指纹；连接批准后保存指纹，后续不匹配直接失败。
- JVM/Android 设备 API 服务端从 Ktor CIO 切换为 raw `SSLServerSocket` HTTP/1.1 dispatcher。

## Impact
- Affected specs: `http-socket-tls-transport`
- Affected code: `SocketDevice`, HTTP client factories, `FileShareService`, raw HTTP server, device discovery/connection/share polling, file transfer clients
