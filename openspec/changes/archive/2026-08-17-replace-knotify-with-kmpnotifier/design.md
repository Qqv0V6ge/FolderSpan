## Context

See proposal.md — Why. The load-bearing facts established during exploration:

- `LocalNotifier` is an `expect object` with five actuals (~756 lines total). Only the JVM actual uses knotify; Android/iOS/JS/Wasm are hand-rolled. Callers (`RequestNotificationDispatcher`, `HttpServerStartError`) use only `notify(id, title, body, payloadData): Boolean`, `remove(id)`, `addClickListener`, and the `notification_action_id` payload convention (`NotificationActionKeys`).
- KMPNotifier 2.0.0 (`io.github.mirzemehdi:kmpnotifier-local`) publishes artifacts for android/ios/jvm/js/wasmJs, requires Kotlin 2.4.0+ (project: 2.4.10 ✅), minSdk 23 (project: 26 ✅), and local-only iOS is not bound to the push module's 16.0 floor.
- Its desktop implementation (`TrayNotifier`) adds one tray icon per notification and never reclaims it without `remove(id)`, wires no click listener, and falls back to a blocking `JOptionPane` dialog when `SystemTray` is unsupported.
- Nucleus 2.4.6 `notification-common` targets JVM 11 and selects native macOS UserNotifications, Windows Toast, or freedesktop D-Bus at runtime. Its published jars include x64 and ARM64 native binaries for all three desktop operating systems and expose up to five action buttons, per-button callbacks, a dismissible handle, and activation/dismiss/failure callbacks.
- Its published 2.0.0 Web implementation falls back to `window.alert` when permission is missing, does not deliver click payloads, and implements removal as a no-op; direct delegation would regress the current JS/Wasm behavior.
- Its iOS implementation owns the `UNUserNotificationCenter` delegate (strongly retained, installed during `initialize`) — which the project's Swift `AppDelegate` currently also installs, forwarding clicks through `NotificationClickBridge`.
- iOS emits **both** `onAction` and `onNotificationClicked` for an action tap (the click emit is unconditional in its delegate); Android routes action taps through a `NotificationReceiver` broadcast and body taps through the launcher activity — parity of the double-emit there is unverified.

## Goals / Non-Goals

**Goals:**

- One notification facade and event contract in `commonMain`; platform surface is limited to entry-point initialization plus small Desktop/Web backends where KMPNotifier 2.0.0 lacks required behavior.
- Preserve the caller-visible contract: method signatures, `notification_action_id` payload convention, listener buffering of early clicks, fallback-friendly Boolean from `notify`.
- Desktop keeps click-to-open routing, request action buttons, native replacement/removal, and explicit failure when the OS notification service is unavailable, without creating notification tray icons.

**Non-Goals:**

- No Firebase / push module (`kmpnotifier-push-firebase`) — local notifications only.
- No scheduled notifications, images, or text-input actions — available in the library but unused by current callers.
- No compatibility shims for the deleted platform code (pre-release project).

## Decisions

### 1. `LocalNotifier` becomes a single `commonMain` object

```kotlin
// core/src/commonMain/kotlin/com/folderspan/notification/LocalNotifier.kt
object LocalNotifier {   // no longer expect/actual
    fun initialize(askPermissionOnStart: Boolean = true)  // delegates to per-platform hook
    fun notify(id: Int, title: String, body: String, payloadData: NotificationPayload = emptyMap()): Boolean
    fun remove(id: Int)
    fun addClickListener(listener: NotificationClickListener)
}
```

One `KMPNotifier.addListener` registration fans Android/iOS events out to `clickListeners`, merging `actionId` into the payload under `NotificationActionKeys.ACTION_ID` — the convention `NotificationDeepLinkHandler` and `NotificationScreen` already consume. The Desktop backend feeds body clicks and native button callbacks into the same dispatcher; Web feeds default clicks only. Early events keep buffering into `pendingClicks` exactly as today.

Alternatives considered: keeping expect/actual with KMPNotifier inside each actual (pointless indirection — the whole gain is deleting per-platform code); swapping only the JVM actual (keeps ~550 lines of hand-rolled Android/iOS/Web code and gains nothing).

### 2. Desktop uses native OS notifications, not KMPNotifier's tray notifier

KMPNotifier's `TrayNotifier`/`JOptionPaneNotifier` is unusable as-is: tray-icon leak per notification, zero click delivery, modal-dialog fallback. The JVM backend instead wraps Nucleus `notification-common`, which selects the host-native implementation:

```
initialize: Windows → initialize WinRT Toast
            macOS   → request UserNotifications alert authorization
            Linux   → no eager native work
notify:     native service unavailable → return false (no tray/dialog substitute)
            native send failure        → return false
            native send accepted       → retain NotificationHandle by app id
                ├─ activation → drop handle → foreground app → dispatch payload
                ├─ action     → drop handle → foreground app → dispatch payload + action id
                ├─ dismissal  → drop handle
                └─ async fail → drop handle
remove:     drop handle → NotificationHandle.dismiss()
```

The actual transports are macOS UserNotifications, Windows Toast, and freedesktop `org.freedesktop.Notifications`. Nucleus carries x64 and ARM64 native binaries for each packaged target. macOS UserNotifications requires a bundle identifier, so Gradle's plain `run` task intentionally reports unavailable; `runDistributable`, DMG, and PKG launches execute inside the packaged `.app` and can request authorization. Windows packaging retains the existing Start-menu shortcut/application identity required by classic desktop Toast.

Desktop maps the same per-notification `NotificationAction(id, title)` list used by Android/iOS into Nucleus `button(title, onClick)` entries. Button callbacks foreground the app and dispatch the original payload with the selected `notification_action_id` exactly once. The current mapping has at most three buttons, below Nucleus's five-button limit. Linux rendering remains notification-server dependent; Web retains its no-inline-action degradation model.

### 2a. Web uses the browser API behind the common facade

The published KMPNotifier 2.0.0 Web notifier is initialized so the dependency has one consistent lifecycle, but it is not used for posting. Its `WebConsoleNotifier` falls back to `window.alert` when browser notifications are unavailable or permission is missing, does not attach a click listener or payload, and implements `remove(id)` as a no-op. Those behaviors violate both the existing implementation and this change's availability/removal requirements.

Small JS and Wasm backends therefore use the browser `Notification` API directly. They require `Notification.permission == "granted"`, return `false` without leaving an alert or artifact otherwise, replace an active notification with the same id, close notifications on `remove(id)`, and dispatch the captured string payload on click. This is a new implementation behind the shared facade, not a compatibility layer for the deleted actual objects.

### 3. iOS: KMPNotifier owns the delegate; Swift side initializes

- Delete `AppDelegate`'s `UNUserNotificationCenterDelegate` conformance (`willPresent` / `didReceive`) and `NotificationClickBridge.kt`. KMPNotifier's delegate already presents foreground notifications as banner+sound+badge — bit-identical options to the current Swift code — so no behavior loss.
- Initialize from Swift `didFinishLaunching` (`KMPNotifier.shared.initialize(configuration: NotificationPlatformConfigurationIos(...), extensions: [LocalNotifications.shared])`) instead of from Kotlin-in-composition: a cold-start tap delivers its response before the first frame composes, and only a delegate installed in `didFinishLaunching` reliably catches it.
- This requires exporting `kmpnotifier-core` and `kmpnotifier-local` from the final `ComposeApp` iOS framework in `app/shared/build.gradle.kts`.
- Action categories become per-notification (`com.mmk.kmpnotifier.actions.<id>`, library-internal) — the four hand-registered categories and their init-time-frozen labels disappear; labels now render in the language active at post time.

### 4. Android: library receiver + existing launch intent

- `MainActivity` swaps `LocalNotifier.handleIntent(intent)` for `KMPNotifier.onCreateOrOnNewIntent(intent)` in `onCreate`/`onNewIntent`; `handleIntent` and the hand-rolled channel/PendingIntent machinery are deleted.
- `NotificationPlatformConfiguration.Android`: `notificationIconResId = context.applicationInfo.icon` (keeps the current dynamic icon, avoids referencing `androidApp`'s `R` from library code); channel id/name/description configured via `NotificationChannelData` with the existing localized strings.
- POST_NOTIFICATIONS runtime permission stays with the existing `PlatformPermissionProvider` settings flow — untouched by this change.

### 5. Event normalization: iOS dedup, payload sanitizing, dismiss-on-action

Verified against library source (`main` ≈ 2.0.0): **Android single-emits** (`NotificationReceiver` fires only `emitAction` for action taps); **iOS double-emits** (its delegate fires `emitAction` then unconditionally `emitNotificationClicked` for the same tap). The facade therefore normalizes:

- **Dedup (iOS)**: suppress an `onNotificationClicked` whose payload is identical to an `onAction` dispatched within the last ~500 ms. Body taps never pass through `onAction`, so they are unaffected; on Android the dedup never triggers and the code path stays platform-free.
- **Payload sanitizing (Android)**: the receiver copies *all* intent extras into the action payload, including the library's own `action_id` / `notification_id` keys (the latter a non-String Int). The facade drops library-internal keys and stringifies values so listeners keep receiving `Map<String, String>`.
- **Dismiss on action (Android)**: action taps do not cancel the notification (`autoCancel` covers only the body-tap content intent; the receiver's "dismiss" comment is not backed by a cancel call). The facade calls `remove(notificationId)` after dispatching an action event, matching the current `handleIntent` behavior. iOS removes acted-on notifications itself, so this is a no-op there.

### 6. Action-button mapping preserved

The request-kind → buttons mapping is the one piece of domain logic carried over, now expressed as `NotificationAction(id, title)` lists at post time:

| Payload marker | Buttons (Android/iOS/Desktop) |
|---|---|
| `RequestNotificationKind.DeviceShare` | save / view / reject |
| other request kinds | approve / reject |
| permission-reminder metadata | open / delete (Android parity; iOS gains these two as ordinary actions) |
| none | no actions |

Desktop uses the same mapping through Nucleus native buttons. Web posts without actions per its degradation model.

### 7. `notify(): Boolean` semantics

KMPNotifier checks permissions asynchronously, so a synchronous delivery guarantee is impossible on Android/iOS. The facade returns: Desktop → native-service availability plus synchronous Nucleus send acceptance (a later native callback can still report failure); Web → browser support plus granted permission plus constructor success; Android/iOS → `KMPNotifier.isInitialized`. This keeps `RequestNotificationDispatcher.needsFallbackHandling()` meaningful where delivery can be synchronously rejected and matches the previous mobile behavior.

`RequestNotificationDispatcher` suppresses a requested system notification only when the application lifecycle reports foreground and the notification is configured to render an in-app banner. `DeviceConnect` is a strong-alert exception and always attempts system delivery, even when the foreground banner is visible. Persisting an item in the in-app bell is not immediate user-visible delivery, and a configured banner is not visible while the application is in the background. The existing cross-platform `UserNotificationRuntime` lifecycle callbacks forward foreground changes to the dispatcher; the default before lifecycle attachment is background, which safely favors OS delivery during startup.

### 8. Initialization topology

```
androidApp  Application/MainActivity → KMPNotifier.initialize(Android config, LocalNotifications)
desktopApp  main.kt                  → KMPNotifier.initialize(Desktop config, LocalNotifications)
                                       (still needed by shared code paths that call
                                        KMPNotifier APIs; Nucleus native backend installed here)
iosApp      AppDelegate (Swift)      → KMPNotifier.shared.initialize(Ios config, [LocalNotifications])
webApp      js/wasm main             → KMPNotifier.initialize(Web config, LocalNotifications)
                                       + custom browser backend installed here
```

`initializeNotifications()` in `app/shared` only ensures the shared listener is registered. All entry-point initialization and backend installation happens before `App()` composes, so posting from common code is safe.

## Risks / Trade-offs

- [Desktop action rendering varies across Linux notification servers] → The backend always sends the mapped actions and handles callbacks when the server exposes them; the in-app detail page remains the fallback when a server omits buttons.
- [Android double-emit unverified — could double-dispatch approve/reject] → Resolved by source inspection: Android single-emits, iOS double-emits; facade dedup (Decision 5) covers iOS. A device check remains in tasks as cheap confirmation, since source was read from `main` rather than the 2.0.0 tag.
- [iOS cold-start tap lost if delegate installs late] → Init from Swift `didFinishLaunching` (Decision 3), verified by a cold-start test from a killed app.
- [macOS native notification unavailable under plain Gradle `run`] → Required by UserNotifications application identity; return failure without tray fallback and verify through `runDistributable`/installed `.app` instead.
- [macOS authorization can be denied or native posting can fail asynchronously] → Request alert permission once at initialization, mark a denied session unavailable, and release tracked handles from failure callbacks; in-app notification fallback remains available.
- [KMPNotifier Web permission/click behavior differs from the required contract] → Resolved against the published 2.0.0 source by installing small JS/Wasm browser backends; they retain no-prompt startup, permission-gated failure, click payload delivery, id replacement, and removal.
- [Native desktop dependency maturity and binary footprint] → Nucleus is pinned at 2.4.6, limited to `jvmMain`, covered by an adapter contract test, and can be rolled back independently without changing common callers. KMPNotifier's Desktop/Web implementations remain bypassed while its Android/iOS path stays pinned at 2.0.0.

## Migration Plan

Single change, no staged rollout (pre-release, no external consumers of the internal API). Suggested verification order: Android emulator → desktop (Linux/Windows) → Web → iOS device. Rollback = revert the change; no data or persistent-state migration exists (notifications are ephemeral). Land before `notification-action-buttons` implementation begins.

## Open Questions

None at implementation level. Android/iOS/Desktop/Web runtime smoke checks remain device/environment verification tasks.

The Android event questions were resolved from the published 2.0.0 source: `onCreateOrOnNewIntent` routes local-notification body taps via the `ACTION_NOTIFICATION_CLICK` extra, and Android action taps single-emit (only iOS needs the dedup). Tasks retain device confirmation for OS integration behavior.
