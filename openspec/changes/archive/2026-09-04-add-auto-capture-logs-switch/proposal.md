## Why

用户排查问题时只能看控制台或崩溃页，无法在出错当下把现场日志交回来。关于页缺少一个可开关的本机日志捕获能力：打开后自动记录各功能日志，出错时通知用户，点通知或点「反馈」就能带着日志去提交。

## What Changes

- 在关于软件页增加「自动捕获日志」开关，默认关闭，状态仅保存在本机
- 开关打开后，应用把各功能的日志写入有上限的本机缓冲（内存，必要时落缓存文件）
- 出现错误（`LogKit.e` 或未捕获崩溃）时，向用户发送一条可点的错误通知（应用内铃铛 + 系统通知）
- 点击通知正文或通知上的「反馈」按钮，打开反馈页并附带当前日志快照（`.log` / `text/plain`）
- 崩溃页在捕获开启时增加「反馈」入口，行为与通知一致
- 补齐主要功能路径上缺失的操作/失败日志，使捕获层有东西可记

## Capabilities

### New Capabilities

- `auto-log-capture`: 关于页开关、本机日志缓冲、功能路径日志覆盖与隐私边界
- `error-log-feedback`: 出错通知、点击/反馈按钮进入反馈，并自动附上日志

### Modified Capabilities

- `about-software-app-updates`: 关于软件页增加自动捕获日志开关
- `view-crash-screen`: 捕获开启时，崩溃页提供反馈入口
- `local-system-notifications`: 新增错误日志通知种类及其「反馈」操作按钮

## Impact

- 日志：`LogKit` / Napier 增加可开关的捕获 Antilog；不改现有日志文本格式
- 设置：`SettingsUtils` + `SettingsState` 新增本机布尔项（不进跨设备同步白名单）
- UI：`AboutSoftwareScreen`、`CrashScreen`、字符串目录
- 通知：`RequestNotificationFactory`、`LocalNotifier.notificationActionsFor`、`NotificationDeepLinkHandler`
- 反馈：`FeedbackHomeRoute` / `FeedbackFormViewModel` 预填内容；提交后按现有附件通道上传 `.log`
- 各功能模块：在操作边界与失败路径补 `LogKit` 调用
