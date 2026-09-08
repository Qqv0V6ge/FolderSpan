# 剪贴板文件粘贴验证记录

最近验证日期：2026-08-23

## 完成结论

- OpenSpec 中原待完成的 8 项测试任务（4.5、5.6、5.9、7.1 至 7.5）均已有自动化或等价运行证据，任务清单已全部完成。
- 四个平台的准备器测试共覆盖真实文件、目录与空目录、多表示去重、仅文本、不可读或缺失条目、部分失败、生成图片文件名、真实文件优先、位图/Blob 转换和租约清理。
- 公共导入协调器原有测试继续覆盖目标快照、冲突覆盖/跳过/保留两者、暂停、取消、失败重试和无任务时资源释放；平台测试只验证各自载荷到公共批次的边界，避免重复实现任务语义。

## 自动化测试

- `./gradlew :core:jvmTest :app:shared:jvmTest --no-configuration-cache`：通过。`core` 1169 项、`shared` 316 项，失败 0、跳过 0。
- Desktop/JVM `DesktopClipboardImagePreparationTest`：6 项通过。覆盖 AWT 文件列表、目录、稳定去重、真实文件优先、仅文本、无效图片、位图转 PNG 和租约释放清理。
- Android Host `AndroidClipboardFilePreparationTest`：2 项通过。覆盖真实文件、目录/空目录、缺失条目导致的部分失败、不支持目录、仅文本、去重和暂存清理。
- iOS Simulator `:app:shared:iosSimulatorArm64Test --no-configuration-cache`：210 项通过；其中 `IosClipboardFilePreparationTest` 2 项覆盖文件 URL、目录/空目录、图片数据、缺失项目、URL 优先、多表示去重、仅文本和租约清理。
- JS 与 WasmJS `BrowserClipboardFilePreparationTest`：各 4 项通过。覆盖真实 `File` 优先、图片 `Blob`、自动命名、目录/空目录、重复、不可读、非法路径、仅文本和内存批次清理。
- WasmJS 浏览器全量 212 项通过。JS 浏览器的 4 项剪贴板准备器测试通过；JS 全量 212 项中仍有 20 项既有 Compose/Skia UI 测试因 `org_jetbrains_skia_Surface__1nMakeRasterN32Premul is not defined` 失败，与剪贴板准备器无关。

## 平台构建与运行

### Android

- `:app:androidApp:assembleDebug` 通过，Universal Debug APK 安装到 Redmi K30 Pro 真机并成功冷启动，进程和 `MainActivity` 保持前台，无崩溃或 ANR。
- 通过 MediaStore `content://` URI 和 `FLAG_GRANT_READ_URI_PERMISSION` 从外部 `ACTION_SEND` 投递真实单文件；另投递目录条目和未授权 DocumentsProvider URI，应用均正常接收且无 `FATAL EXCEPTION`、`SecurityException` 或进程退出。
- 多文件、空目录、缺失条目、目录不受支持、部分失败、去重和清理由 Android Host 适配层测试完成等价覆盖。真机专用 `Download/folderspan-test` 数据已在验证后删除。

### Web JS/Wasm

- `:app:webApp:jsBrowserDevelopmentWebpack` 在禁用 JS 增量编译后通过；`:app:webApp:wasmJsBrowserDevelopmentWebpack` 通过。Webpack 仅保留项目既有的 `os`、`path` 垫片和动态依赖警告。
- Wasm 开发服务器真实浏览器运行通过：搜索输入框中的普通文本粘贴保持默认行为；向系统剪贴板写入 68 B PNG 后在文件浏览上下文触发粘贴，界面生成 `clipboard.png, 68 B`，并显示“已将 1 个剪贴板文件加入任务”。
- 浏览器运行中记录到任务持久化写入 OPFS 的既有 `NoModificationAllowedError`，但本次剪贴板图片入队成功；准备器测试确认生产者租约释放后隐藏内存批次被清理。

### iOS

- `:core:compileKotlinIosSimulatorArm64`、`:app:shared:linkDebugFrameworkIosSimulatorArm64` 和完整 iOS Simulator 测试均通过。
- `xcodebuild -project app/iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build` 输出 `BUILD SUCCEEDED`。
- 文件提供器多项目、目录结构、图片、部分失败、表示优先级和暂存生命周期由 iOS Simulator 适配层测试覆盖；目标切换、冲突与任务控制由公共协调器测试覆盖。
- 构建仍提示 Kotlin framework 最低 iOS 15 与 Xcode target 14 不一致及一个既有搜索路径警告，均未阻断编译、链接或测试。

### Desktop

- `:app:desktopApp:run` 成功启动完整应用服务；`:app:desktopApp:createDistributable` 通过并生成可运行的 `FolderSpan.app`，打包应用可正常启动和退出。
- 原生 `Ctrl/Cmd+V`、文本焦点保护、显式入口、文件/目录/位图准备、多表示去重和租约清理由 JVM 测试覆盖；本地、设备与网络目标的冲突、暂停、取消、重试和目标快照由公共协调器/网关夹具覆盖。
- macOS Accessibility 无法读取 Compose Desktop 窗口树，因此无法用 UI 自动化再次观察画布内容；应用启动、进程存活、服务日志和打包产物均正常，行为证据由自动化测试补足。

## 回归修复

- 修复 iOS 原有编译阻塞：HTTP 服务处理器参数、Darwin loopback 地址、SFTP 端口字符串和 Keychain CoreFoundation 键类型。
- 修复 JVM 测试在 macOS `/var` 到 `/private/var` 规范化后的管理员路径夹具，并保持访客权限测试不被扩大。
- 修复本地 MCP 网关查询尚不存在末级文件时误报 `permission_denied` 的问题，使新文件写入能进入标准创建流程。
- 更新 Raw HTTP CORS 与 Android View Intent 安全测试以验证当前的同主机同端口和复用 URI 授权守卫；MCP LAN 测试显式绕过系统代理，并只在存在双向自回连网卡时执行 LAN HTTPS 连接，避免 VPN/多网卡环境假失败。

## 平台能力限制

- Desktop/JVM：系统剪贴板提供真实文件列表时支持文件和目录；没有文件列表时支持 AWT 图片并转换为租约管理的 PNG。
- Android：支持获授权的 `content://` 文件与图片；仅当提供器暴露 DocumentsContract 文档树时可遍历目录，否则返回“目录不受支持”的结构化诊断。
- iOS：文件提供器返回文件 URL 时支持目录；只提供图片数据时生成图片文件。安全作用域和暂存资源均随租约配对释放。
- Web JS/Wasm：标准 `FileList` 不表达空目录；提供 `webkitRelativePath` 时可保留目录结构，普通图片 Blob 转为命名文件。浏览器不提供目录能力时返回结构化“不支持”结果。
