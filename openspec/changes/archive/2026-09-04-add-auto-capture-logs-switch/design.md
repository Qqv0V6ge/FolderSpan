## Context

See proposal.md for motivation.

Today `LogKit` formats a line and forwards it to Napier, and every target installs only `DebugAntilog()` — logs go to logcat/console and are discarded. There is no ring buffer, no file snapshot, and no path from an ordinary `LogKit.e` to the user.

Unhandled crashes already hit `installPlatformCrashHandler` → `CrashState.recordCrash` → `CrashScreen` (restart / copy / exit). Feedback already exists: drawer → `ProRoutes.feedbackScreen()`, attachments allow `.log` / `text/plain` up to 10 MiB, and unsigned users go through `ProPendingLoginRoute`. Notifications already share one factory + dispatcher + `NotificationDeepLinkHandler` click stream (`app_update` and `permission_reminder` are the pattern to copy).

The About software page (`AboutSoftwareScreen`) currently shows version, native update check, history, and two external links. Settings toggles elsewhere use `SettingsState` + `SettingsUtils` `putBoolean` / `Switch` in a `ListItem`. Keys absent from `SettingsUtils.syncableSettings` stay device-local (same as MCP).

## Goals / Non-Goals

**Goals:**

- One capturing Napier Antilog, gated by a persisted About-page switch
- Error and crash → one updatable notification → feedback with a `.log` snapshot
- Fill missing `LogKit` calls on major feature failure paths so the buffer has useful content
- Stay inside existing notification, feedback, and settings patterns

**Non-Goals:**

- Remote log shipping, analytics, or always-on capture
- A log viewer / level filter UI
- Changing Napier line format
- Replacing `CrashScreen` or the existing crash copy/restart/exit actions
- Syncing the switch across devices
- Attaching logs on the create-ticket request (create remains text-only; upload after submit)

## Decisions

### 1. Switch lives on About software, default off, device-local

User asked for 关于页面. Put a `ListItem` + `Switch` on `AboutSoftwareActionsCard`, after history / links, visible on every target including Web.

- Key: `SettingsUtils.KEY_AUTO_CAPTURE_LOGS = "settings.app.autoCaptureLogs"`
- `SettingsState.autoCaptureLogs` + `setAutoCaptureLogs`, same `MutableStateFlow` / `reloadFromSettings` pattern as `fileShareEnabled`
- Do **not** add the key to `syncableSettings`
- Default `false`

Alternative considered: Developer settings. Rejected — the user named About, and diagnostic capture is something a non-developer should be able to turn on before sending feedback.

### 2. Capture via a second Napier Antilog, not by wrapping every `LogKit` call site

Install `LogCaptureAntilog` next to `DebugAntilog` at the existing `Napier.base(...)` sites (Android, Desktop, iOS, JS, Wasm). `LogCapture` reads `SettingsState.autoCaptureLogs` (or a tiny `LogCaptureController` fed from it).

- In-memory ring buffer, cap **2 MiB** / drop oldest
- Native targets: mirror the same cap into the app cache directory (`folderspan-capture.log`) so a crash restart can still attach logs
- Web: memory only
- Turning the switch off: uninstall/ignore the antilog and clear memory + cache file
- Snapshot export: UTF-8 `.log` bytes, truncated to `MAX_FEEDBACK_ATTACHMENT_BYTES` (10 MiB) if needed — the 2 MiB cap already keeps it under

Do not add `isAutoCaptureEnabled` onto `LogKit` itself; `LogKit` stays a formatter. Capture is a Napier sink so every existing `LogKit.*` / `Napier.*` line is recorded without touching 94 call sites.

Alternative considered: only record `LogKit.e`. Rejected — the user asked to capture logs for all features; the error notification is the *alert*, the buffer is the *context*.

### 3. What counts as an error for the notification

Trigger when capture is on and either:

- Napier records a line at `ERROR` (including `LogKit.e` and crash handler logging the throwable), or
- `CrashState.recordCrash` runs

Do **not** notify on warnings.

Throttle / identity: stable request id `error_log_capture` (same idea as `app_update`). A new error within **5 minutes** `upsert`s that notification; after 5 minutes the next error may post a fresh timestamp but still the same id so the OS row is replaced.

`NotificationFactoryConfig(showInBell = true, showInBanner = true, sendSystemNotification = true)` — the user should see it even in the foreground. Unlike DeviceConnect, banner-visible may still suppress the system notification in `RequestNotificationDispatcher`; for this kind treat it like a strong reminder: always request the system notification (same exception path DeviceConnect uses, or a dedicated `KIND_ERROR_LOG` flag).

### 4. Click / Feedback action → existing feedback route, attach after submit

Reuse `NotificationActionKeys.ACTION_OPEN` for the labeled「反馈」button (label from a new AppString). Body tap is `ACTION_DEFAULT`. Both go to the same handler branch:

`NotificationDeepLinkHandler` → `mainState.requestOpenScreen(authenticated ProRoutes.feedbackScreen())` with a small pending payload (`error_summary`, snapshot token).

`FeedbackFormViewModel`:

- Prefill `type = Feedback`, `content` = short summary (exception name / last error message), truncated to `MaxFeedbackContentLength`
- Keep a pending `FeedbackUpload` (`folderspan-error.log`, `text/plain`) from `LogCapture.snapshot()`
- On successful `submit()`, call the existing ticket attachment upload (`FeedbackSessionService` / detail upload API) with that snapshot — do not wait for the user to pick a file
- If the user is signed out, `ProPendingLoginRoute` already wraps feedback destinations via `AppNotificationRouteRegistry`

Crash screen: pass `autoCaptureLogs` into `CrashScreen`; when on, show a `TextButton`「反馈」that starts the same pending-payload flow (crash `reportText` + snapshot). Restart still clears crash state as today.

### 5. Feature logging is additive at operation boundaries

Do not log every function. After the capture sink exists, add `LogKit.i` on start and `LogKit.e(..., throwable)` on failure where a user-facing feature currently swallows errors or has no log:

- File copy / move / delete / rename (`FileState*` / `FileRuntimeTaskExecutor`)
- Device connect / disconnect (`DeviceState`, `Device.kt`)
- File share / easy share / link share
- Network drives (FTP / SFTP / WebDAV / S3)
- WebRTC send/receive
- Sync tasks
- Bookmarks, search, editor save, clipboard paste
- Account session, MCP, settings writes

Prefer catch blocks and operation coordinators already in `core`. UI screens only log if they have a unique failure path that never reaches those coordinators.

### 6. Privacy

- Off by default; About supporting text explains paths / device names may appear
- Do not log access keys, passwords, TLS passwords, or `SensitiveSettingKeys`
- Snapshot is not uploaded until the user submits feedback
- Switch off wipes the buffer

### 7. Open questions from the earlier draft — resolved

- In-form log viewer: **no**. Auto-attach after submit.
- Log-level filter on the switch: **no**. Capture all lines; notify only on error.

## Risks / Trade-offs

- [Hot-path logging cost] → Capture Antilog is a no-op when the switch is off; when on, append to a pre-sized ring, no per-line I/O on Web, batched file flush on native
- [Notification spam] → Single stable id + 5-minute upsert
- [PII in logs] → Default off, supporting text, no secrets, user-initiated upload only
- [Crash on Android kills the process] → Cache-file mirror so relaunch crash screen / notification can still attach
- [Feedback create API has no attachment] → Upload after submit using the existing ticket attachment channel
- [“All features” is unbounded] → Spec lists the feature families; tasks audit those packages rather than every composable

## Migration Plan

- New key, default off → no behavior change until the user toggles it
- Rollback: turn the switch off, or remove the key (treated as off)

## Open Questions

None. Remaining implementation details (exact cache path, flush interval) do not change specs or the task breakdown.
