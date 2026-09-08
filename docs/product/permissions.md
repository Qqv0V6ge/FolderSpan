# 权限需求与新增指引

本文用于记录权限设置页的需求与后续新增权限的开发清单，确保各平台实现一致且可维护。

## 设计原则
- 设置入口仅在 `PlatformPermissionProvider.permissions()` 非空时展示（`SettingsScreen`）。
- 列表只包含当前平台“可请求或可跳转设置”的权限。
- `status()` 返回实际状态；不支持的平台返回 `PermissionStatus.Unsupported`。
- 已授权权限的“关闭”动作通过 `openSettings()` 引导用户到系统设置完成。
- Web/WASM 平台的“设置”跳转为帮助链接或浏览器设置页面。

## 当前权限清单
| ID | 展示名称 | 平台 | Action | 备注 |
| --- | --- | --- | --- | --- |
| `read_external_storage` | 读取外部存储 | Android | `Request` | READ_EXTERNAL_STORAGE（Android 9 及以下可能包含 WRITE_EXTERNAL_STORAGE） |
| `all_files_access` | 所有文件访问 | Android (R+) | `OpenSettings` | 仅可引导系统设置授权 |
| `notifications` | 通知权限 | Android (T+), iOS, JS/WASM | `Request` | Web 仅在 `Notification` 可用时展示 |
| `battery_optimization` | 电池优化 | Android (M+) | `Request` | 通过系统设置申请忽略优化 |
| `shizuku` | Shizuku 权限 | Android (M+) | `Request` | 服务缺失时标记为 `Unsupported` |
| `login_startup` | 开机启动 | JVM (Windows/macOS/Linux) | `Request` | 注册当前用户登录后自动启动 FolderSpan；开发态 JVM 启动器标记为 `Unsupported` |

## 新增权限流程（Checklist）
1. **定义 ID**
   - 在 `core/src/commonMain/kotlin/com/folderspan/permission/PlatformPermissionProvider.kt`
     的 `PermissionIds` 中新增常量（kebab 风格的字符串）。
2. **补齐展示文案**
   - 在 `app/shared/src/commonMain/kotlin/com/folderspan/ui/screen/settings/PermissionSettingsScreen.kt`
     的 `permissionDisplay()` 中添加标题与描述。
3. **平台列表入口**
   - 在各平台 `PlatformPermissionProvider.*.kt` 的 `permissions()` 中按平台与版本条件加入。
   - 对不支持的平台不加入列表，或在 `status()` 返回 `Unsupported`。
4. **状态与请求逻辑**
   - 为新权限补齐 `status()` 与 `request()` 分支。
   - 若系统不允许主动请求，设置 `PermissionAction.OpenSettings`。
5. **设置跳转**
   - 在 `openSettings()` 中提供系统设置入口或帮助链接。
   - Android 无明确入口时可回退到应用详情页。
6. **平台配置**
   - Android：更新 `AndroidManifest.xml` 与运行时权限请求（如需）。
   - iOS：更新 `Info.plist` 说明与能力配置（如需）。
   - Web/WASM：确认 API 可用性与权限策略。
7. **验证**
   - 权限列表是否出现、状态是否可刷新、请求与设置跳转是否可用。

## 新权限模板（最小落地）
- `PermissionIds.NewPermission = "new_permission"`
- `permissionDisplay()` 添加展示文案
- `PlatformPermissionProvider.permissions()` 增加入口
- `status()` / `request()` / `openSettings()` 增加分支
- 必要时补齐平台配置与文档说明
