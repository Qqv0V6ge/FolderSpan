## 1. Scope & behavior
- [x] 1.1 梳理当前启动阶段权限请求入口（Android/iOS/JS/Wasm）并统一为“只检查不请求”。
- [x] 1.2 定义“缺失权限提醒”触发规则（启动后触发、无缺失不提醒、同一会话去重）。

## 2. Reminder notification flow
- [x] 2.1 新增权限提醒通知构建逻辑（标题、消息、metadata）。
- [x] 2.2 启动后检测缺失权限并写入应用内通知中心；具备系统通知能力时同步发送系统通知。
- [x] 2.3 为权限提醒点击增加深链处理，打开权限设置页。

## 3. Permission request timing
- [x] 3.1 Android: 移除 `MainActivity` 启动时 `ensureStoragePermission()` 与 `checkAndRequestNotificationPermission()` 的直接调用。
- [x] 3.2 iOS/JS/Wasm: 初始化通知时不再 `askPermissionOnStart = true`。
- [x] 3.3 保留权限设置页中的手动请求能力，并确保请求后状态刷新。

## 4. Validation
- [x] 4.1 增加或更新测试，覆盖“启动不自动弹窗”“缺失权限提醒通知”“点击提醒跳转权限页”。
- [x] 4.2 运行相关验证（至少 `:shared:jvmTest`、`:composeApp:jvmTest`）。
- [x] 4.3 运行 `openspec validate update-startup-permission-reminders --strict`。
