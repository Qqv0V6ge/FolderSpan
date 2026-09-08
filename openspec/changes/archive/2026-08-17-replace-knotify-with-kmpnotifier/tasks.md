## 1. Dependencies & build wiring

- [x] 1.1 In `gradle/libs.versions.toml`: remove the `knotify` version and library entries; add `kmpnotifier = "2.0.0"` and `kmpnotifier-local = { module = "io.github.mirzemehdi:kmpnotifier-local", version.ref = "kmpnotifier" }`
- [x] 1.2 In `core/build.gradle.kts`: remove `implementation(libs.knotify)` from `jvmMain.dependencies`; add `implementation(libs.kmpnotifier.local)` to `commonMain.dependencies`
- [x] 1.3 In `app/shared/build.gradle.kts`: export `kmpnotifier-core` and `kmpnotifier-local` from the final `ComposeApp` iOS framework (needed for Swift-side initialization)
- [x] 1.4 Verify the dependency resolves for all targets: `./gradlew :core:compileKotlinJvm` and a metadata/JS/Wasm compile (e.g. `./gradlew :core:compileKotlinJs :core:compileKotlinWasmJs`)
- [x] 1.5 Add JVM-only `dev.nucleusframework:nucleus.notification-common:2.4.6`; verify its runtime graph and x64/ARM64 native artifacts for Linux, macOS, and Windows

## 2. Android device confirmation (verifies source-read findings on the 2.0.0 artifact)

- [x] 2.1 On an API 36 emulator, confirm a published KMPNotifier 2.0.0 notification body tap reaches the listener through `KMPNotifier.onCreateOrOnNewIntent(intent)` and dispatches one `notification_action_id=default` event
- [x] 2.2 On the API 36 emulator, confirm an action-button tap dispatches one `notification_action_id=save` event and the facade's explicit `remove(notificationId)` removes the notification

## 3. Common facade (core/commonMain)

- [x] 3.1 Rewrite `core/src/commonMain/kotlin/com/folderspan/notification/LocalNotifier.kt` from `expect object` into a plain `object` with the unchanged public contract: `initialize(askPermissionOnStart)`, `notify(id, title, body, payloadData): Boolean`, `remove(id)`, `addClickListener(listener)`
- [x] 3.2 Implement posting via `KMPNotifier.localNotifier.notify { }`: id/title/body/payloadData mapping, plus the `NotificationAction(id, title)` list derived from payload markers per design Decision 6 (DeviceShare → save/view/reject; other request kinds → approve/reject; permission-reminder → open/delete)
- [x] 3.3 Implement the unified event stream: register one `KMPNotifier.Listener`; `onAction` dispatches payload + `notification_action_id=<actionId>` and then calls `remove(notificationId)` (Android does not auto-dismiss acted-on notifications); `onNotificationClicked` dispatches payload + `notification_action_id=default`; keep `pendingClicks` buffering for late-registered listeners
- [x] 3.4 Implement event normalization (design Decision 5): ~500 ms identical-payload dedup for the iOS synthetic click, and payload sanitizing that drops library-internal keys (`action_id`, `notification_id` extras) and stringifies non-String values to preserve the `Map<String, String>` contract
- [x] 3.5 Implement Boolean availability through the installed Desktop/Web backends and KMPNotifier initialization state on Android/iOS, without restoring the deleted `expect/actual` facade
- [x] 3.6 Delete `core/src/androidMain/.../LocalNotifier.android.kt`, `LocalNotifier.ios.kt`, `LocalNotifier.js.kt`, `LocalNotifier.wasmJs.kt`

## 4. Desktop native notification path (core/jvmMain)

- [x] 4.1 Delete `core/src/jvmMain/.../LocalNotifier.jvm.kt` (knotify usage, `.app` gate, payload map)
- [x] 4.2 Replace the AWT tray notifier with a Nucleus adapter: native-service availability, synchronous result mapping, OS handle retention/removal, activation/dismiss/failure callbacks, and no tray/dialog fallback
- [x] 4.3 Preserve click-to-foreground and payload dispatch; replace by app id and release handles after activation, dismissal, failure, or explicit removal
- [x] 4.4 Add JVM contract tests for unavailable/post-failure behavior, click routing, same-id replacement, explicit removal, and asynchronous cleanup
- [x] 4.5 Map the shared request actions to Nucleus native buttons; foreground the app and dispatch the selected `notification_action_id` exactly once; cover the action list and callback contract with a JVM test

## 5. Desktop entry point (app/desktopApp)

- [x] 5.1 Remove the notification-only temporary icon extraction; native system notifications use the packaged application identity/icon
- [x] 5.2 In `main.kt`, keep KMPNotifier listener initialization, initialize the Nucleus backend before `application {}`, and request macOS alert authorization when running in a packaged `.app`
- [x] 5.3 Remove `app/shared/src/jvmMain/.../NotificationInitializer.jvm.kt` per design Decision 8
- [x] 5.4 Manual native verification: packaged macOS `.app`, installed Windows package, and Linux D-Bus session each display a system notification; body click opens the detail, supported native services show request action buttons and dispatch the selected action, same-id replacement/removal works, and no notification tray icon is created

## 6. Android migration

- [x] 6.1 Move the spike initialization into the Android entry point with final config: `notificationIconResId = applicationInfo.icon`, channel data using the existing localized channel name/description strings
- [x] 6.2 In `app/androidApp/.../MainActivity.kt`: replace both `LocalNotifier.handleIntent(intent)` calls with `KMPNotifier.onCreateOrOnNewIntent(intent)`
- [x] 6.3 Remove `app/shared/src/androidMain/.../NotificationInitializer.android.kt` per design Decision 8
- [x] 6.4 Manual verification on emulator/device (API 26 and API 33+): request notification shows approve/reject (and save/view/reject for DeviceShare); action tap dispatches exactly one event with the right action id; body tap routes to the detail page; permission-reminder notification shows open/delete (API 36 covers DeviceShare actions/body routing and permission-reminder open/delete; the repeatable API 26 instrumentation test covers DeviceConnect approve/reject, action/body taps, exactly-once dispatch, and removal; background delivery and DeviceConnect always-system-delivery policies are unit-tested)

## 7. iOS migration

- [x] 7.1 In `app/iosApp/iosApp/iOSApp.swift`: remove `UNUserNotificationCenterDelegate` conformance, the `willPresent` and `didReceive` implementations, and the `UNUserNotificationCenter.current().delegate = self` line
- [x] 7.2 In `didFinishLaunchingWithOptions`, call `KMPNotifier.shared.initialize(configuration: NotificationPlatformConfigurationIos(...), extensions: [LocalNotifications.shared])` on the main thread
- [x] 7.3 Delete `app/shared/src/iosMain/.../NotificationClickBridge.kt`; retain only the iOS event-bridge bootstrap per design Decision 8
- [x] 7.4 Device verification: approve/reject and save/view/reject actions dispatch single events; foreground arrival shows banner+sound; cold-start tap (app killed, tap notification) delivers the click after startup; action labels follow the current app language at post time (iOS 26.5 simulator verifies foreground/background delivery, default-sound configuration, two registered DeviceConnect actions, and a killed-app body tap launching the app exactly once; no physical iOS device is connected, and the user explicitly accepted the remaining action-button, language-switching, and physical-device runtime checks as waived)

## 8. Web migration

- [x] 8.1 Inspect the published KMPNotifier 2.0.0 Web artifacts and record the parity gap: no-prompt initialization exists, but posting falls back to `alert`, click payload is absent, and removal is a no-op; implement JS/Wasm browser backends that preserve the required behavior
- [x] 8.2 Initialize `KMPNotifier.initialize(NotificationPlatformConfiguration.Web(askNotificationPermissionOnStart = false), LocalNotifications)` in the JS and Wasm entry points; install the browser backends and remove the two obsolete `NotificationInitializer` actuals
- [x] 8.3 Verify `./gradlew :app:webApp:jsBrowserDevelopmentRun` and `:app:webApp:wasmJsBrowserDevelopmentRun`: notification posts and click routes to the detail page (JS/Wasm runtime verified on macOS Chrome with an isolated profile: post, same-ID replacement, click routing to DeviceShare detail, and click cleanup pass with no runtime errors)

## 9. Cleanup: strings, docs, dead code

- [x] 9.1 Remove the KNotify-only macOS gate string and the now-dead KNotify send-failure string from both locale catalogs
- [x] 9.2 Grep for stragglers in app/build/docs sources: `knotify`, `KNotify`, `NotificationClickBridge`, `LocalNotifier.handleIntent` — no references remain
- [x] 9.3 Update `docs/notifications.md`: single-facade architecture, per-platform initialization, native Desktop action buttons, Web inline-action degradation, and removal of the KNotify/macOS-bundle note
- [x] 9.4 Update `docs/ci-cd-pipeline.md`: delete the knotify Linux-aarch64 limitation note
- [x] 9.5 Sync `md_descriptions_paths.md` for the active notification OpenSpec Markdown files

## 10. Final verification

- [x] 10.1 `./gradlew :core:jvmTest :app:shared:jvmTest` green (the notification suite and Shared JVM suite pass; the full Core suite currently has 21 unrelated failures in file/path authorization, one LAN timeout, and one WebRTC expectation; a representative file-authorization failure is also reproducible from an isolated `HEAD` worktree)
- [x] 10.2 `./gradlew :app:androidApp:assembleDebug` green
- [x] 10.3 Full-platform smoke matrix (Android, packaged desktop macOS/Windows/Linux, Web JS + Wasm, iOS device if available): post, click routing, action dispatch, removal (Web JS/Wasm, Android API 26/API 36, and iOS 26.5 simulator killed-app body-tap launch are covered; foreground/background and DeviceConnect strong-alert system-notification policies are unit-tested; native desktop display/body-click/action-button/removal, iOS action-button/language checks, and iOS physical-device coverage remain)
- [x] 10.5 Build the macOS distributable and confirm its `.app` has bundle id `com.folderspan`, Nucleus notification jars, both macOS native architectures, and the required Java runtime modules
- [x] 10.4 Confirm `notification-action-buttons` planning docs need no payload adjustment for the new facade (`notification_action_id` and string payload conventions are unchanged); note that it lands after this migration
