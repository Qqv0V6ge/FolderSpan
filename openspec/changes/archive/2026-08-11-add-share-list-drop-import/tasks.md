## 1. 前置验证：Android content-URI 可分享性

- [x] 1.1 手工验证：把一个 `content://` 条目（`FileProtocol.Share` + `SYSTEM_SHARE_DESK_ID`）放入 `fileShareState.files`，通过链接分享下载并校验内容
- [x] 1.2 把验证结论记录到 design.md；结论为可保留 content-URI 原样入列

## 2. 共享导入与资源路由

- [x] 2.1 新增 `ShareListDropRegistry`，提供带接收器身份比对的注册、注销与投递能力
- [x] 2.2 为 `ShareListDropRegistry` 补 commonTest，覆盖无接收器、成功投递与旧接收器注销保护
- [x] 2.3 在 `FileShareScreen` 生命周期内注册 Desktop 拖入接收器，落地复用 `FileShareState.updateIncomingFiles`
- [x] 2.4 新增 commonMain 的 `openIncomingFilesInFileShare`，先写入 `incomingFiles`，再按栈顶页面决定是否导航到 `FileShareScreen`
- [x] 2.5 让资源引用集合包含当前列表、`incomingFiles` 与 `pendingIncomingFiles`，避免冷启动或等待合并期间提前释放
- [x] 2.6 确认继续复用现有合并弹窗与分享同步副作用，不新增第二套合并/同步逻辑

## 3. Desktop 拖放

- [x] 3.1 `DesktopFileDropHandler` 在分享页接收器存在时优先投递到分享列表，未命中时保持文件浏览器原行为
- [x] 3.2 把 `List<java.io.File>` 归一化为 `FileProtocol.Local` 条目，按路径去重并保留目录属性
- [x] 3.3 手工验证分享页拖入文件/文件夹不会切换浏览路径；离开分享页后既有拖入行为不变

## 4. Android 系统文件分享

- [x] 4.1 为 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 文件 URI 增加分享列表归一化入口，产出 `FileProtocol.Share` + `SYSTEM_SHARE_DESK_ID` 条目
- [x] 4.2 文件分享先调用 `updateIncomingFiles`，再打开 `FileShareScreen`；页面已在栈顶时不重复导航
- [x] 4.3 冷启动阶段把文件分享的初始页面设为 `FileShareScreen`，避免首页闪现
- [x] 4.4 保持纯文本 `ACTION_SEND` 与 `ACTION_VIEW` 的首页处理行为
- [x] 4.5 运行 Android debug 构建
- [x] 4.6 在 Android 设备/模拟器验证单文件、多文件 Intent 均直达分享页

## 5. iOS 系统文件打开

- [x] 5.1 `IosDocumentOpenHandler` 对系统传入 URL 去重并读取 `FileSimpleInfo`
- [x] 5.2 把文件作为 `FileProtocol.Local` 条目写入 `incomingFiles` 并打开 `FileShareScreen`，不再先进入文件浏览页
- [x] 5.3 在读取前注册 security-scoped URL；无法读取时立即注销
- [x] 5.4 把成功条目的注销回调交给资源注册表，在取消、替换或移除后释放
- [x] 5.5 通过 Kotlin iOS Simulator 与 Xcode Simulator 编译
- [x] 5.6 在 iOS 设备/模拟器验证系统文件打开直达分享页

## 6. Web 能力边界

- [x] 6.1 保留 js/wasmJs 已有底层拖入归一化实现，不回退已完成代码
- [x] 6.2 明确 Web 不启用分享入口，不增加 Web 分享手工验收任务

## 7. 文档与回归验证

- [x] 7.1 更新 proposal、spec 与 design，把支持范围改为 Desktop 拖放 + Android/iOS 系统外部文件入口
- [x] 7.2 同步 `md_descriptions_paths.md` 中本变更的文档说明
- [x] 7.3 运行 `:app:shared:jvmTest` 与本变更直接相关的 `ShareListDropRegistryTest`（全量 `:core:jvmTest` 已运行，存在 20 个与本变更无关的权限/设备路径/WebRTC 基线失败）
- [x] 7.4 运行 `./gradlew :app:androidApp:assembleDebug`
- [x] 7.5 运行 iOS Kotlin/Xcode 编译
- [x] 7.6 运行 `openspec validate add-share-list-drop-import --strict`
