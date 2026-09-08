# MCP 变更验证记录

日期：2026-08-04

## 自动化测试

- MCP 定向 JVM 测试通过：协议协商、无状态/有状态端点、SSE、会话删除与过期、Bearer Token、Scope、Host/Origin、防重绑定、1 MiB 请求上限、工具业务、四数据源文件矩阵、范围读取、异步删除、链接/设备分享、移动安全和 LAN HTTP/HTTPS。
- `:app:shared:jvmTest` 全量通过，包含 MCP 管理页面的窄屏/宽屏、深浅主题和动态色测试。
- Android host 上 `McpHttpServiceLifecycleTest` 通过。
- iOS Simulator Arm64 上 `McpHttpServiceLifecycleTest` 通过；由于仓库已有 `copyTestComposeResourcesForIosSimulatorArm64` 隐式任务依赖问题，运行时使用 `--no-configuration-cache` 并跳过与该纯逻辑测试无关的测试资源复制任务。
- `:core:jvmTest` 已全量运行：921 项中 18 项失败，失败均位于既有 `DeviceFileServiceTest`、`DevicePathServiceTest` 和 `WebRtcStreamTransferFailureJvmTest`；MCP 相关测试无失败。

## 平台构建

- Android：`:app:androidApp:assembleDebug` 通过。
- Desktop/JVM：`:app:desktopApp:compileKotlin` 通过。
- iOS Simulator Arm64：`:app:shared:compileKotlinIosSimulatorArm64` 通过。
- Web JS/Wasm：`:app:shared:compileKotlinJs` 与 `:app:shared:compileKotlinWasmJs` 通过；MCP 服务 actual 明确返回不支持且不监听端口。

## 静态检查

- `:core:validateAppStringCatalogs` 通过。
- `plutil -lint app/iosApp/iosApp/Info.plist` 通过。
- `git diff --check` 通过。

## 平台限制

- JVM 已在真实非回环局域网地址上完成 HTTP/HTTPS 集成测试，并验证端口占用可恢复。
- Android/iOS 的前后台行为由共享串行生命周期控制器测试覆盖，平台 actual 已分别完成 Android host/iOS Simulator 测试或编译；当前环境未运行 Android 设备仪器测试，也未在 iOS 真机上发起局域网请求。
- iOS 原生监听器当前绑定 IPv4，因此只广告实际可连接的 IPv4 LAN 地址；共享 URL 构造仍覆盖 IPv6 格式，JVM/Android 可广告其可用 IPv6 地址。
