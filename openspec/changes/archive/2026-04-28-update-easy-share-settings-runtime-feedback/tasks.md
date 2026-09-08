## 1. Settings Screen Feedback

- [x] 1.1 Update `EasyFileShareSettingsScreen` to remember `SnackbarHostState` and centralize Snackbar launch helpers for setting-change feedback.
- [x] 1.2 Show a restart-app-required Snackbar after both enabling and disabling "自动启动".
- [x] 1.3 Keep existing mutual-exclusion behavior between "自动启动" and "打开页面自动启动" while ensuring the Snackbar reflects the user's selected auto-start change.

## 2. Port Change Restart Flow

- [x] 2.1 Inject or access `FileShareState` and `HttpShareFileServer` from `EasyFileShareSettingsScreen` without introducing new global state.
- [x] 2.2 When a valid service port is saved, persist the new port and branch on the current Easy Share service running state.
- [x] 2.3 If the service is running, show a Snackbar with a restart action instead of immediately restarting the service.
- [x] 2.4 When the restart action is clicked, stop the current Easy Share service and start it again with the persisted port.
- [x] 2.5 Show success or failure Snackbar feedback after the restart attempt.
- [x] 2.6 If the service is stopped, persist the port silently and do not show a restart action or port-update Snackbar.

## 3. Default Option Scope

- [x] 3.1 Audit link-share initialization and authorization paths to confirm 默认分享路径、默认自动允许、默认同设备自动允许、默认密码访问 are read when creating new sessions or authorizations.
- [x] 3.2 Preserve existing authorized clients' files, password state, and approval state when those defaults are changed in settings.
- [x] 3.3 Add concise code comments only where needed to document the "new connections only" boundary.

## 4. Verification

- [x] 4.1 Run the relevant JVM compile/test task for the touched Compose/shared code.
- [x] 4.2 Manually verify the settings page flows: auto-start on/off Snackbar, port change while stopped, port change while running, restart action, and default options not mutating existing connections.
