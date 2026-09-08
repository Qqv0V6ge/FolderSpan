## Why

System notification delivery is five hand-rolled platform implementations (~756 lines) behind an `expect/actual` facade, and the desktop piece — knotify 0.4.3, JVM-only — has documented platform gaps: no Linux aarch64 native library (flagged in `docs/ci-cd-pipeline.md`), and macOS is unusable unless running from a .app bundle. KMPNotifier 2.0.0 (`kmpnotifier-local`) provides the Android/iOS notification implementation and a shared event API, and matches the project's Kotlin 2.4 toolchain. Its published Web notifier does not preserve this app's click, removal, or permission-gating behavior, while its desktop notifier is based on tray balloons and has resource and click-delivery gaps. Web therefore keeps a small browser backend, while Desktop uses Nucleus 2.4.6 to reach each operating system's native notification service. The project is pre-release, so no compatibility with the current implementations is preserved — the old platform code is deleted, not adapted.

## What Changes

- Replace `io.github.kdroidfilter:knotify:0.4.3` with `io.github.mirzemehdi:kmpnotifier-local` (local notifications only; the Firebase push module is explicitly not adopted), plus the JVM-only `dev.nucleusframework:nucleus.notification-common:2.4.6` native desktop adapter.
- **BREAKING** Delete all five `LocalNotifier` actuals (android, ios, jvm, js, wasmJs). `LocalNotifier` becomes a single `commonMain` object: Android/iOS delegate to `KMPNotifier.localNotifier`, while Desktop/Web install small capability-preserving backends. The existing `notify(id, title, body, payloadData): Boolean` / `remove(id)` / `addClickListener` contract and the `notification_action_id` payload convention remain unchanged for callers.
- Action buttons become per-notification `NotificationAction`s on Android, iOS, and Desktop (replacing hand-rolled `PendingIntent`s on Android and one-time `UNNotificationCategory` registration on iOS — action labels follow the app language at post time). Desktop maps the same actions into Nucleus native buttons and routes callbacks through the shared `notification_action_id` event contract.
- Desktop does **not** use KMPNotifier's `TrayNotifier`/`JOptionPaneNotifier`. Nucleus dispatches through macOS UserNotifications, Windows Toast, or freedesktop D-Bus and returns an OS notification handle for replacement/removal. Unsupported or unpackaged environments report failure; there is no tray-balloon or modal-dialog fallback. macOS requires the packaged `.app` identity and requests alert authorization at desktop notification initialization.
- **BREAKING (iOS integration)**: KMPNotifier owns the `UNUserNotificationCenter` delegate. The Swift `AppDelegate` notification methods and `NotificationClickBridge` are deleted; initialization moves to Swift `didFinishLaunching` so cold-start notification taps are captured (requires exporting `kmpnotifier-core`/`kmpnotifier-local` in the iOS framework).
- Android `MainActivity` replaces `LocalNotifier.handleIntent(intent)` with `KMPNotifier.onCreateOrOnNewIntent(intent)`.
- The `notify()` Boolean return becomes approximate (mobile permission checks and macOS native posting complete asynchronously): desktop reports native-service availability plus synchronous post acceptance, other targets report initialization state. Fallback logic in `RequestNotificationDispatcher` is preserved.
- iOS action taps double-emit (`onAction` + `onNotificationClicked`), while Android action taps single-emit; the facade deduplicates so listeners see exactly one event per user interaction.
- Remove the knotify-specific string resource (`ui_knotify_cannot_used_current_process_requires_app_package_macos`, both locales); reword desktop-unavailable logging.
- Update `docs/notifications.md` and `docs/ci-cd-pipeline.md` (the Linux aarch64 notification limitation is resolved); sync `md_descriptions_paths.md`.

## Capabilities

### New Capabilities

- `local-system-notifications`: posting, updating, and removing system notifications across all five targets; delivery of body-tap and action-button events into the shared payload contract; per-platform initialization; native Desktop action buttons with Web-only inline-action degradation.

### Modified Capabilities

None. No existing spec covers system notification delivery (`device-share-requests` and `handle-device-share-requests` specify request flows and are behavior-agnostic to how the OS notification is posted).

## Impact

**Dependencies / build**

- `gradle/libs.versions.toml`: remove `knotify`, add `kmpnotifier-local` (pulls `kmpnotifier-core`) and Nucleus notification-common.
- `core/build.gradle.kts`: KMPNotifier moves to `commonMain`; Nucleus is added to `jvmMain` only.
- `app/shared/build.gradle.kts`: the final `ComposeApp` iOS framework exports `kmpnotifier-core` + `kmpnotifier-local` for Swift initialization.

**Deleted**

- `core/src/{androidMain,iosMain,jvmMain,jsMain,wasmJsMain}/kotlin/com/folderspan/notification/LocalNotifier.*.kt` (five actuals, ~756 lines)
- `app/shared/src/iosMain/.../NotificationClickBridge.kt`
- Swift `UNUserNotificationCenterDelegate` methods in `app/iosApp/iosApp/iOSApp.swift`
- `LocalNotifier.handleIntent` usage in `app/androidApp/.../MainActivity.kt`

**New / modified**

- `core/src/commonMain/.../LocalNotifier.kt` — single object, event dedup, listener registry (was `expect`).
- `core/src/jvmMain/.../notification/` — Nucleus-backed native desktop notifier, action-button callback mapping, and OS-handle lifecycle adapter.
- `core/src/{jsMain,wasmJsMain}/.../notification/` — browser Notification backends preserving permission gating, click payloads, id replacement, and removal.
- `app/shared/src/commonMain/.../NotificationInitializer.kt` — common event-listener registration; obsolete platform actuals are deleted.
- `app/desktopApp` — initializes the native desktop backend before composition; no notification-only tray icon is extracted or passed.
- `app/iosApp/iosApp/iOSApp.swift` — initialize in `didFinishLaunching`; drop delegate methods.
- String catalogs (both locales), `docs/notifications.md`, `docs/ci-cd-pipeline.md`, `md_descriptions_paths.md`.

**Sequencing**

- Should land before implementation of `notification-action-buttons` begins: that change edits the same payload-key surface (`LocalNotifier` payload conventions), and landing this first lets its work build on the final structure.
