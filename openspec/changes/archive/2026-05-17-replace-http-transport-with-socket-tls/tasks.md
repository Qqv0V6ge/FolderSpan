## 1. Specification
- [x] 1.1 新增 socket TLS transport 变更文档与 spec delta。
- [x] 1.2 运行 `openspec validate replace-http-transport-with-socket-tls --strict`。

## 2. Implementation
- [x] 2.1 扩展 `SocketDevice` 与 serializer，携带 `httpsPort` 和 TLS 指纹。
- [x] 2.2 新增设备 TLS 身份加载/生成、指纹规范化与受信设备指纹存储。
- [x] 2.3 新增 pinned/discovery HTTPS client factory actual 实现。
- [x] 2.4 新增 raw HTTP/1.1 request/response dispatcher，覆盖设备、路径、书签、文件与共享 API。
- [x] 2.5 将 JVM/Android `FileShareService` 切换为 `SSLServerSocket` raw HTTPS server。
- [x] 2.6 将发现、presence probe、连接、共享轮询与客户端 baseUrl 切换为 HTTPS + `httpsPort`。
- [x] 2.7 移除文件 read/write/share read 成功路径的应用层加密帧校验与写入。
- [x] 2.8 同步 Markdown 索引。

## 3. Verification
- [x] 3.1 运行 `openspec validate replace-http-transport-with-socket-tls --strict`。
- [x] 3.2 运行 `./gradlew :shared:jvmTest`。
- [x] 3.3 运行 `./gradlew :composeApp:jvmTest`。
- [x] 3.4 运行 `./gradlew :shared:compileKotlinJvm`。
- [x] 3.5 可用时运行 `./gradlew :shared:compileServerRouteMainKotlinMetadata`。
