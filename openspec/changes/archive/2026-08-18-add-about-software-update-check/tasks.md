## 1. Runtime version and comparison

- [x] 1.1 Apply `libs.plugins.build-config` on `:core` and generate a `MAJOR.MINOR.PATCH` constant from Gradle `releaseVersion` (strip optional leading `v`, default `1.0.0`)
- [x] 1.2 Expose that constant to `commonMain` so Settings and the update checker read the same string on every target
- [x] 1.3 Add a pure `MAJOR.MINOR.PATCH` compare helper: parse three non-negative integers, newer iff the tuple is lexicographically greater; reject suffixes and other shapes
- [x] 1.4 Unit tests: equal, greater, older, missing parts, suffix rejected, leading `v` only handled at BuildConfig generation not in the helper

## 2. Latest-update API client

- [x] 2.1 Add `updatesPrefix = "/api/v1/updates"` and `RouteBuilder.updates(path)` next to `message()`; do not put `/updates` on `messagePrefix`
- [x] 2.2 Add `MessageApiService.latestAppUpdate(platform, channel)` as `GET routes.updates("/latest")` with `platform` and `channel` (default `release`), no `Authorization`
- [x] 2.3 Decode into the existing `MessageEnvelopeDto<MessageItemDto>` (or equivalent); HTTP 404 and null/empty `data` are “no update”; other non-success statuses are failures
- [x] 2.4 Map the item with the existing second-or-millisecond `published_at` heuristic; keep `content` and `link` verbatim
- [x] 2.5 Unit tests in `MessageApiServiceTest`: encoded path `/api/v1/updates/latest`, query `platform` and `channel`, no Authorization, 404 → empty, null data → empty, non-404 failure

## 3. App update runtime

- [x] 3.1 Add domain types for a single latest update and a snapshot (idle/checking/up-to-date/newer/error) consumed by Settings and tests
- [x] 3.2 Add `AppUpdateRuntime` that calls `latestAppUpdate(announcementPlatformToken())`, compares against the BuildConfig version, and never requires a session
- [x] 3.3 `start(scope)` on Android/iOS/Desktop: check immediately with `notify = true`, then loop `delay(5.hours)` until cancelled; do nothing when `PlatformType` is `DeviceType.JS`
- [x] 3.4 Manual `check(notify = false)` updates the snapshot only and MUST NOT post a device notification
- [x] 3.5 Automatic checks post only when the latest version is newer; empty/older/unparseable latest posts nothing
- [x] 3.6 Register the runtime in Koin and `start`/`cancel` it from `AppRuntimeEffects` next to `AnnouncementRuntime`; do not call it from `OnAppResumeEffect`
- [x] 3.7 Unit tests: Web/JS start is a no-op, notify vs silent check, empty catalog, malformed version, 5-hour loop uses the delay (fake clock or injected delay)

## 4. Device notification and link activation

- [x] 4.1 Add a stable `app_update` kind/request id and factory helper using `NotificationFactoryConfig(showInBell = true, showInBanner = false, sendSystemNotification = true)`
- [x] 4.2 Automatic newer-version path posts through `RequestNotificationDispatcher` so Local `upsert` and `LocalNotifier` share `notificationIdFor("app_update")`
- [x] 4.3 Store HTTP(S) `link` and version in metadata; skip URL open when the scheme is not `http`/`https`
- [x] 4.4 `NotificationDeepLinkHandler`: `app_update` + `ACTION_OPEN` calls `openUrl(link)`; default body activation opens the notification center detail
- [x] 4.5 Local-tab row, notification detail, and system notification show an Open download page action; row/body activation pushes `NotificationDetailScreen` and does not open the link
- [x] 4.6 Unit tests: same id on two posts, `ACTION_OPEN` opens HTTPS, default action does not open a URL, empty/non-HTTP link is a no-op, detail screen is pushed
- [x] 4.7 `notificationActionsFor` adds `ACTION_OPEN` for `app_update` when the link is HTTP or HTTPS, and omits it otherwise

## 5. Settings About software UI

- [x] 5.1 Add `app_en.xml` / `app_zhHans.xml` strings for About software, current version, check for updates, up to date, newer version, retryable error, and open-link; run `./gradlew :core:validateAppStringCatalogs`
- [x] 5.2 Add `AboutSoftwareScreen` showing the BuildConfig version; show Check for updates only when `PlatformType` is not `DeviceType.JS`
- [x] 5.3 Wire in-page states from `AppUpdateRuntime` (checking / up to date / newer with text + HTTP(S) open / retryable error); manual check uses `notify = false`
- [x] 5.4 Add an About software row on `SettingsScreen` that pushes the new screen
- [x] 5.5 UI tests: version visible, Web hides check, native check shows up-to-date vs newer vs error without posting a notification

## 6. Docs and verification

- [x] 6.1 Update `docs/notifications.md` with the public latest-update client (`GET /api/v1/updates/latest`), start-plus-5-hour cadence, Web skip, stable `app_update` notification, and Open download page action behavior
- [x] 6.2 Update `md_descriptions_paths.md` for changed docs and this OpenSpec change
- [x] 6.3 `./gradlew :core:jvmTest :proMain:jvmTest :app:shared:jvmTest` (or the module tasks covering the new code)
- [x] 6.4 `openspec validate add-about-software-update-check --strict`

## 7. Update channel

- [x] 7.1 Map `channel` on the latest-update DTO and always send `channel` (`release` or `beta`; unknown → `release`)
- [x] 7.2 Persist the selected channel on this device, default `release`; `AppUpdateRuntime` uses it for automatic and manual checks
- [x] 7.3 Native About software offers Release/Beta switching without requesting latest; Web still hides the check UI including the channel control
- [x] 7.4 Tests: default query `channel=release`, requested `beta`, invalid token normalized, runtime does not request on switch, About page switcher visibility

## 8. Version history

- [x] 8.1 Add `MessageApiService.listAppUpdates` as `GET routes.updates()` with `platform`, `channel`, `page`, `pageSize`; no Authorization; 404 is an empty page
- [x] 8.2 Repository/runtime: load history on screen open for the selected channel, paginate, reset history when the channel changes; do not fetch history at process start
- [x] 8.3 About software always shows a Version history item (including Web) that opens `AppUpdateHistoryScreen`
- [x] 8.4 History UI: list notes in a dialog on compact width and a list–detail split from Medium; Open download page action only; empty and retryable error states
- [x] 8.5 Tests: list path `/api/v1/updates`, paging/channel reset, About item visible on Web, selecting a version opens notes without opening the URL until the action is pressed
