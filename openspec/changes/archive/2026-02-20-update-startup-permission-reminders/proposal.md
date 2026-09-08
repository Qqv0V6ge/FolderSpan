# Change: Update startup permission flow to reminder-first

## Why
当前实现会在应用启动阶段直接拉起系统权限弹窗（Android 存储/通知，iOS/JS/Wasm 通知权限）。
这会打断首次使用流程，也不符合“由用户主动触发授权”的体验目标。

## What Changes
- 移除启动阶段的自动权限请求，不在 App 打开时立即弹系统权限框。
- 启动后仅检测缺失权限，并生成“权限提醒通知”引导用户前往权限设置页。
- 权限请求入口收敛到用户主动动作（权限设置页按钮或功能触发场景）。
- 为权限提醒通知增加可跳转行为，支持从通知打开权限设置页。

## Impact
- Affected specs: `manage-permission-settings`（MODIFIED/ADDED）。
- Affected code (expected):
  - `composeApp/src/androidMain/kotlin/com/folderspan/MainActivity.kt`
  - `shared/src/androidMain/kotlin/com/folderspan/permission/PermissionController.android.kt`
  - `composeApp/src/{iosMain,jsMain,wasmJsMain}/kotlin/com/folderspan/notification/NotificationInitializer.*.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/notification/*`
  - `composeApp/src/commonMain/kotlin/com/folderspan/notification/NotificationDeepLinkHandler.kt`
  - `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/settings/PermissionSettingsScreen.kt`（导航落点）
