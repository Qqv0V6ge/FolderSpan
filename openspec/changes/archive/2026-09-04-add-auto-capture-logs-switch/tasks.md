## 1. Settings and About software switch

- [x] 1.1 Add `SettingsUtils.KEY_AUTO_CAPTURE_LOGS` (`settings.app.autoCaptureLogs`); do not add it to `syncableSettings`
- [x] 1.2 Add `SettingsState.autoCaptureLogs` flow, `setAutoCaptureLogs`, and `reloadFromSettings` using `getBoolean(..., false)`
- [x] 1.3 Add zh/en AppStrings for the switch title, supporting privacy text, error notification title/body, Feedback action, and crash-screen Feedback; run `./gradlew :core:validateAppStringCatalogs`
- [x] 1.4 Put a `ListItem` + `Switch` on `AboutSoftwareActionsCard` (all targets, including Web) bound to `SettingsState`; toggling must not call `AppUpdateRuntime.check`
- [x] 1.5 Tests: default off, persist on, persist off across `reloadFromSettings`, key is not syncable; About UI test that the switch is visible and does not trigger an update check

## 2. Log capture sink

- [x] 2.1 Add `LogCapture` (commonMain): enable/disable, ring buffer capped at 2 MiB dropping oldest, `snapshot(): ByteArray` as UTF-8
- [x] 2.2 Add `LogCaptureAntilog` and install it beside `DebugAntilog` at every `Napier.base` site (Android, Desktop, iOS, JS, Wasm)
- [x] 2.3 Native expect/actual: mirror the buffer into an app-cache `folderspan-capture.log`; Web actual is memory-only
- [x] 2.4 Wire enablement from `SettingsState.autoCaptureLogs` at startup and on toggle; disable clears memory and cache file
- [x] 2.5 Unit tests: disabled records nothing; overflow keeps newest lines and stays ≤ 2 MiB; disable clears snapshot; snapshot is valid UTF-8 `.log` bytes

## 3. Error notification

- [x] 3.1 Add `RequestNotificationFactory` kind/metadata `error_log_capture` with stable request id, `NotificationType.Error`, and `NotificationFactoryConfig(showInBell = true, showInBanner = true, sendSystemNotification = true)`
- [x] 3.2 Always request the system notification for this kind (do not suppress when an in-app banner is visible)
- [x] 3.3 `LogCapture` / crash path: on ERROR or `CrashState.recordCrash` while capture is on, post through `RequestNotificationDispatcher`; reuse the same id within 5 minutes
- [x] 3.4 `notificationActionsFor`: native targets get `ACTION_OPEN` labeled Feedback; Web has no inline actions
- [x] 3.5 Tests: capture off posts nothing; first error posts one notification; second error within 5 minutes upserts; Web actions empty; native actions contain Feedback

## 4. Deep link to feedback with log attachment

- [x] 4.1 `NotificationDeepLinkHandler`: `error_log_capture` + `ACTION_OPEN` or `ACTION_DEFAULT` opens authenticated `ProRoutes.feedbackScreen()` with a pending error summary + snapshot (same sign-in gate as `AppNotificationRouteRegistry`)
- [x] 4.2 `FeedbackFormViewModel` accepts an optional launch payload: prefill type Feedback and content; hold a pending `FeedbackUpload` named `folderspan-error.log`
- [x] 4.3 After successful submit, upload that snapshot through the existing ticket attachment API; do not require the file picker
- [x] 4.4 `CrashScreen` takes `showFeedback`; when true, Feedback starts the same payload flow with `reportText` + snapshot
- [x] 4.5 Tests: body tap and Feedback action open feedback; signed-out hits pending login; submit uploads `.log` ≤ 10 MiB; crash screen shows Feedback only when capture is on

## 5. Feature log coverage

- [x] 5.1 Audit and add `LogKit.i` / `LogKit.e` on failure (and operation start where missing) for file copy/move/delete/rename coordinators
- [x] 5.2 Same for device connect/disconnect and file/easy/link share
- [x] 5.3 Same for network drives (FTP/SFTP/WebDAV/S3), WebRTC send/receive, and sync tasks
- [x] 5.4 Same for bookmarks, search, editor save, clipboard paste, account session, MCP, and settings writes
- [x] 5.5 Do not log secrets (access keys, passwords, TLS passwords). Spot-check that new lines go through `LogKit` so the capture sink sees them

## 6. Verification

- [x] 6.1 `./gradlew :core:jvmTest :proMain:jvmTest :app:shared:jvmTest` — 本变更相关测试通过。全量 `:core:jvmTest` 另有既有失败 `AndroidViewIntentSecurityTest`；全量 `:proMain:jvmTest` 另有既有失败 `ProNetworkHeadersTest.gatewayBaseUrlComesFromBuildConfigAndUsesHttps` 与 `ProfileAvatarControlTest.editProfilePageDoesNotRepeatTheAccountSummaryCardOrAvatarHeading`（均与本次无关）
- [x] 6.2 `openspec validate add-auto-capture-logs-switch --strict`
- [x] 6.3 Manual: About switch on → trigger a logged error → notification → Feedback → ticket has `.log`; switch off → error does not notify; crash with capture on shows Feedback
  - iPhone 17 Pro（iOS 26.5）模拟器实测：开启后 TLS 启动错误产生 `Error detected`，通知时间线点按直达 Feedback 的登录门；开启态崩溃页显示 Feedback；关闭后同一错误仅保留原业务通知，不再产生 `Error detected`，捕获日志文件也被清除；关闭态崩溃页不显示 Feedback。
  - 未登录模拟器不向后端创建真实工单；提交后上传 `folderspan-error.log`（且不超过 10 MiB）由 `FeedbackViewModelTest.launchPayloadPrefillsContentAndUploadsLogAfterSubmit` 契约测试验证。
