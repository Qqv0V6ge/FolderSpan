# 通知开发说明

本文说明如何新增/修改通知类型，以及如何控制系统通知分发。

## 现有入口

- 请求类通知由 `RequestNotificationFactory` 生成，`RequestNotificationDispatcher` 负责写入 `NotificationState` 并触发系统通知。
- 设备连接/设备分享请求：在 `DeviceState.updateConnectionRequest` / `DeviceState.updateShareRequest` 中创建通知。
- 链接分享请求：在 `FileShareState.addPendingLinkShareDevice` 中创建通知。
- 系统通知点击：由 `NotificationDeepLinkHandler` 路由。设备请求类通知打开 `NotificationScreen` 详情页。应用更新只写入铃铛本地页签，不发送系统通知。

## 新增请求类通知

1. 在 `RequestNotificationKind` 中新增枚举值。
2. 在 `RequestNotificationFactory` 中新增构建函数（标题、消息、分类、metadata）。
3. 调用 `RequestNotificationDispatcher.post(...)` 写入通知并发送系统通知。
4. 在 `NotificationScreen` 中完善 `toRequestInfo()` 解析与动作处理逻辑（如需要动作按钮）。

## 新增非请求类通知

1. 使用 `RequestNotificationFactory.buildCustomNotification(...)` 构建通知。
2. 通过 `RequestNotificationDispatcher.post(...)` 写入通知。
3. 若需要系统通知，保持 `NotificationFactoryConfig.sendSystemNotification = true`。

## 系统通知分发配置

- `NotificationFactoryConfig` 控制是否发送系统通知。
- 默认 `sendSystemNotification = true`，如需禁用，请在构建通知时传入 `NotificationFactoryConfig(sendSystemNotification = false)`。
- `showInBell` 只表示通知存入应用内铃铛列表，不代表用户已经看到通知；`showInBanner` 仅在应用前台时形成可见的应用内横幅。
- 对于非设备连接请求，只有应用处于前台且应用内横幅可见时，`RequestNotificationDispatcher` 才抑制对应的系统通知；应用在后台时仍会尝试发送系统通知。
- 设备连接请求属于强提醒：无论应用处于前台还是后台，都尝试发送系统通知；前台可同时显示应用内横幅。

## 账号通知正文的 Markdown 约定

账号通知详情支持受控的 Markdown 子集：标题、粗体、斜体、删除线、行内代码、围栏代码块、有序/无序列表、引用、分隔线、链接和图片。原始 HTML、表格、未支持的嵌套结构及任意扩展不会作为可执行或交互内容解析。

链接可使用以下两种形式：

- 外部链接：`[帮助文档](https://example.com/help)`，仅 `http` 和 `https` 协议可点击。
- 应用内路由：`[查看工单](route:feedback_tickets?ticketUuid=ticket%2F7)`。路由键和参数必须存在于仓库根目录的 [`notification-routes.json`](../../notification-routes.json) 中。

路由参数是 UTF-8 字符串，参数名和值中的保留字符必须使用百分号编码；`+` 按字面加号处理，不代表空格。空路由键、空参数名、重复参数、片段（`#...`）、错误的百分号编码、未知路由和未声明参数都不会触发导航。它们的标签仍会作为普通文字显示。

图片使用 `![替代文字](https://example.com/image.png "可选标题")`，只加载 `http` 和 `https` 地址；点击图片会打开全局图片预览，并优先使用标题、其次使用替代文字作为说明。其他协议不会加载或打开。

需要显示字面分隔符时，可使用反斜杠转义 Markdown 分隔符；不完整或不符合约定的语法按普通文字显示。

完整通知详情中的受支持目标可以点击并支持键盘操作。通知列表、临时横幅和操作系统通知只显示纯文本预览：移除格式分隔符和目标地址，链接替换为可见标签，图片替换为替代文字；通知标题在所有表面始终是不可解析的字面文字。结构化动作按钮仍使用既有通知动作模型，与正文链接相互独立。

### 公告扇出边界

当前仓库同时拥有账号通知客户端与公开公告目录客户端。账号通知 REST 列表入口为 `/api/v1/messages/user/notifications`，实时入口为 `/api/v1/messages/user/notifications/stream`。公开公告目录入口为 `GET /api/v1/messages`（`type=announcement`，按当前客户端平台过滤，不带 `Authorization`；网关只剥离 `/api/v1`，不要再拼接 `/messages`）。仓库内没有从公告生成用户通知的扇出实现，`server/` 也只是保留的服务端骨架。

公开公告目录使用设备本地 `published_at` 水位线表示已读，不调用服务端已读接口，也不计入主页/抽屉铃铛未读。同一主题可以同时出现在账号消息页签和公告页签，客户端不做去重。

具体集成点是账号消息后端负责物化 `user_notifications` 记录的公告扇出流程。该流程把 `announcements.content` 按文本契约原样复制到 `user_notifications.content`，不得裁剪空白、规范化换行、预渲染 Markdown、解码或重新编码。上游精确保真测试以及本客户端 DTO 解码、领域映射和跨端展示均已完成验证；上游源码不属于本工作区。

### 应用更新检查

公开最新应用更新入口为 `GET /api/v1/updates/latest?platform=…&channel=…`（按当前客户端平台过滤；`channel` 为 `release` 或 `beta`，缺省与未识别值按 `release`；不带 `Authorization`；网关只剥离 `/api/v1`，不要走 `messagePrefix` 拼成 `/api/v1/messages/updates/latest`）。打开关于页不会请求 latest。关于页可切换渠道并保存在当前设备，切换时不立刻请求；下次手动检查或定时检查使用新渠道。自动检查与手动检查都使用当前所选渠道。历史版本列表走独立的 `GET /api/v1/updates?platform=…&channel=…&page=…&pageSize=…`，只在打开历史版本页时请求，不在进程启动时拉取。该客户端与公告目录相互独立：公告列表不用于判断是否有应用更新，最新更新响应和更新列表也不填入公告页签。

Android、iOS 和 Desktop 在进程启动时请求一次，并在同一进程内存活期间每 5 小时再请求一次。不要仅因应用从后台恢复而检查。Web JS/Wasm 不轮询、不展示「检查更新」、不发送应用更新通知；关于页仍可显示当前版本，并提供「历史版本」入口。

当最新发布版本的 `MAJOR.MINOR.PATCH` 新于当前运行版本时，通过 `RequestNotificationDispatcher` 写入一条设备本地通知，使用稳定 identity `app_update`（`RequestNotificationFactory.APP_UPDATE_REQUEST_ID`）在本地页签 upsert，避免堆叠。该通知的 `NotificationFactoryConfig.sendSystemNotification` 为 `false`，不要发送系统通知。手动「检查更新」只更新关于页状态，不发通知。铃铛本地页签和通知详情提供「打开下载页面」动作按钮；仅该按钮在 `link` 为 `http`/`https` 时调用 `openUrl`。点击铃铛行进入通知详情，不会打开下载链接。空链接或其他协议下该动作为 no-op。应用不会因存在更新而强制升级。

## 通知权限与入口

- 权限入口由 `PlatformPermissionProvider.permissions()` 提供，设置页展示并可请求/跳转系统设置。
- 请求权限使用 `PlatformPermissionProvider.request(...)`，状态刷新使用 `PlatformPermissionProvider.status(...)`。
- Android 13+ 使用 `POST_NOTIFICATIONS`；低版本视为已授权。`openSettings()` 会跳转到通知设置或应用详情页。
- iOS 使用 `UNUserNotificationCenter.requestAuthorizationWithOptions` 请求权限。
- Web/JS/Wasm 仅在 `Notification` 可用时展示入口，使用 `Notification.requestPermission()`。

## 元数据约定

请求类通知默认包含：

- `request_id`：请求唯一标识（request kind + device id）
- `request_kind`：请求类型
- `device_id` / `device_name`

## 系统通知架构

- 公共代码只调用 `LocalNotifier`。该门面保留 `notify`、`remove` 和点击监听接口，并统一把通知点击、动作按钮事件转换为带 `notification_action_id` 的字符串元数据。
- Android 和 iOS 使用 KMPNotifier 2.0.0 的本地通知实现。Android 在 `Application` 初始化，并由 `MainActivity` 将创建/新 Intent 交给 KMPNotifier；iOS 在 Swift `didFinishLaunchingWithOptions` 初始化，以覆盖冷启动点击。
- Desktop 使用 Nucleus 2.4.6 调用原生系统通知：macOS UserNotifications、Windows Toast、Linux freedesktop D-Bus。请求动作按钮复用 Android/iOS 的映射与本地化标题，点击正文或按钮都会回到应用并通过 `notification_action_id` 分发；按 id 替换/移除通过原生通知句柄完成。原生服务不可用时返回失败，不创建通知托盘图标或模态对话框作为替代。
- Web JS/Wasm 使用浏览器 `Notification` API。启动时不主动申请权限；浏览器不支持或权限未授予时返回失败。
- `initializeNotifications()` 在 `App()` 中只负责确保公共事件监听器已注册，各平台依赖与后端均在应用入口、首帧组合前完成初始化。

## 注意事项

- 请求超时处理位于 `DeviceState` 中，与 Banner 逻辑一致。
- 链接分享待处理列表清理时请使用 `FileShareState.clearPendingLinkShareDevices()` 以同步清理通知。
- Android、iOS 和 Desktop 支持系统通知点击及请求动作按钮；Linux 桌面是否渲染按钮取决于通知服务能力。Web 系统通知仅负责提示和点击导航，具体动作在应用内通知详情页完成。
- macOS 原生通知依赖 `.app` 的 bundle identity，并在桌面通知初始化时申请提醒权限；请使用 `runDistributable`、DMG/PKG 或已安装应用验证。普通 Gradle `run` 不在 `.app` 内，会按不可用处理且不会回退到托盘气泡。
