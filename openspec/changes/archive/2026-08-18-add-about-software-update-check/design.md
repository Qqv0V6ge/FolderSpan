## Context

See `proposal.md` for motivation and `specs/about-software-app-updates/spec.md` for observable behavior.

Settings (`SettingsScreen`) has no About software row. Runtime code cannot read a shared app version: Android `versionName` and Desktop package version come from Gradle `releaseVersion` (default `1.0.0`); iOS `MARKETING_VERSION` is still `1.0` in `Config.xcconfig`; Web has no version surface. `com.github.gmazzo.buildconfig` is already in the version catalog and unused.

The public latest-update API is `GET /updates/latest?platform=…` on message-api. Traefik exposes it as `GET /api/v1/updates/latest` and strips `/api/v1`. Gateway auth marks `/api/v1/updates` public. `RouteBuilder` only has `messagePrefix` (`/api/v1/messages`). `MessageApiService` only lists announcements. `announcementPlatformToken()` already maps Android/iOS/JVM-OS/JS. `AnnouncementRuntime` starts in `AppRuntimeEffects` and also refreshes on resume after the first `OnAppResumeEffect`; this change must not copy that resume refresh.

Device notifications already go through `RequestNotificationFactory.buildCustomNotification` → `RequestNotificationDispatcher.post` → `NotificationState` plus `LocalNotifier`. `NotificationDeepLinkHandler` does not yet open arbitrary HTTP(S) links from local notifications. `openUrl` already exists on `App`.

## Goals / Non-Goals

**Goals:**

- One Pro-side latest-update client and runtime that Settings and the start/5-hour loop both call.
- A comparable `MAJOR.MINOR.PATCH` string visible to common code on every target, even though Web never checks.
- One stable local+system notification identity for “update available”, with an Open download page action.
- An About-software Version history item that lists published updates from `GET /api/v1/updates`.

**Non-Goals:**

- In-app download, store APIs, or forced update.
- Resume-triggered checks, WorkManager/background fetch after process death, or Web polling.
- About Libraries / licenses (existing unused spec stays untouched).
- Persisting `NotificationState` across process restarts.
- Unifying iOS `MARKETING_VERSION` packaging with Gradle in this change beyond using the KMP-injected version for comparison and display.

## Decisions

### 1. Dedicated updates prefix, method on `MessageApiService`

Add `updatesPrefix = "/api/v1/updates"` and `RouteBuilder.updates(path)` analogous to `message()`. Call `GET routes.updates("/latest")` with `platform` and `channel`. Do not attach a bearer token. Reuse the existing HTTP client (`Accept-Language` already set).

Treat HTTP 404 and a successful envelope with null/empty `data` as “no update”. Other non-success statuses are failures.

Alternative considered: `routes.message("/updates/latest")`. Rejected; that becomes `/api/v1/messages/updates/latest`, which message-api does not serve.

Alternative considered: a separate `UpdatesApiService`. Rejected as extra surface; this is the same public message-center HTTP API with a different gateway prefix.

### 2. Reuse announcement platform tokens; skip Web at the runtime boundary

Pass `announcementPlatformToken()` as `platform` (`android`, `ios`, `macos`, `windows`, `linux`). Do not start the checker and do not render Check for updates when `PlatformType` is `DeviceType.JS` (covers JS and current Wasm).

Wasm-only server records tagged `wasm` remain invisible, same limitation as announcements.

### 3. `AppUpdateRuntime` started from `AppRuntimeEffects`, no resume hook

Mirror `AnnouncementRuntime`: Koin single, `start(scope)` once. On native targets, `start` launches: check immediately, then `while (isActive) { delay(5.hours); check() }`. Cancel on dispose. Do **not** call it from `OnAppResumeEffect`.

Manual About-page check calls the same `check(notify = false)` (or equivalent). Automatic checks use `notify = true` and only post when the latest version is newer.

Alternative considered: refresh on resume like announcements. Rejected; agreed cadence is process start plus a 5-hour in-process interval.

### 4. Inject `releaseVersion` via BuildConfig into `core` commonMain

Apply the already-catalogued BuildConfig plugin on `:core` (or the smallest module both Settings UI and Pro runtime can see). Generate a `MAJOR.MINOR.PATCH` constant from the same Gradle `releaseVersion` property Android/Desktop packaging already uses (`orElse("1.0.0")`, strip optional leading `v`). Read that constant in common code for display and comparison on all targets, including Web display.

iOS UI still shows this injected string, not `CFBundleShortVersionString`, so comparison matches what we publish through the message-center. Packaging docs already warn iOS marketing version is separate; do not block this change on rewriting `Config.xcconfig`.

Alternative considered: expect/actual reading `versionName` / package version / bundle version. Rejected; Web has nothing to read, and the three natives would drift.

### 5. Compare only `MAJOR.MINOR.PATCH` numeric triples

Parse `\d+\.\d+\.\d+` (optional extra suffix is not a valid compare). Latest is newer iff `(maj, min, pat)` is lexicographically greater than the running tuple. Equal or older → up to date. Unparseable current or latest → automatic check posts nothing; manual check is a retryable failure (current) or up to date (empty latest) / failure (malformed latest).

The server orders by `published_at`, not semver. Client comparison is the source of truth for “newer”.

### 6. One stable notification identity; action button opens the link

Kind/request id: constant `app_update` (not `app_update:<version>`). `RequestNotificationFactory.notificationIdFor` / `systemNotificationIdFor` then stay stable so Local `upsert` and `LocalNotifier.notify(id)` replace.

`NotificationFactoryConfig(showInBell = true, showInBanner = false, sendSystemNotification = true)` so start-up does not toast a banner, but the system shade still updates.

Metadata holds `link` (and version). System notifications expose an Open download page action (`NotificationActionKeys.ACTION_OPEN`) when the link is `http`/`https`. `NotificationDeepLinkHandler` opens `openUrl(link)` only for that action. Default body activation and Local-tab row clicks push `NotificationDetailScreen`, which also shows the same action. An empty or non-HTTP link omits the system action and is a no-op if the action is still invoked.

Manual checks never post. Automatic checks that are not newer do not post; they also do not have to delete a prior in-memory notification (process restart already clears `NotificationState`; upgrading requires a new process).

Alternative considered: per-version request ids. Rejected; a later version would stack a second system notification while the old one lingered.

### 7. About software is a normal Settings `AppScreenRoute`

Add a row on `SettingsScreen` (after device info is fine) pushing `AboutSoftwareScreen`: an identity card with the running version, and on native targets a grouped card with the channel control, a Check for updates `ListItem`, and Version history. Opening the page does not call latest or history. Check for updates requests only when that row is activated. A newer-version dialog opens after a check that finds a newer build, or when the user opens the update notes; it does not open just because the page appeared. Strings in `app_en.xml` / `app_zhHans.xml`.

Web still gets the screen, version, and Version history, without the check row or channel control.

### 8. Persist update channel on device; default `release`

The latest-update API accepts `channel=release|beta` (default `release`). Native About software shows a two-way control. Store the token in `settings.app.updateChannel` on this device only (not settings-sync). Invalid stored values and omitted query values map to `release`. Automatic and manual checks both send the selected channel. Switching channel persists the choice, clears the in-page check snapshot and history cache, and does not request latest. The next manual Check or automatic interval uses the new channel.

Announcements keep using `/messages` without this channel switcher; `channel` on `/messages` is ignored unless `type=app_update`, which this client does not list.

### 9. Version history uses the public paged updates list

About software always shows a Version history item (including Web). It opens `AppUpdateHistoryScreen`, which calls `GET /api/v1/updates?platform=…&channel=…&page=…&pageSize=…` with the current platform token and the selected channel. Do not use `/messages?type=app_update`. Load on screen open, not at process start; paginate with the announcement-style `hasMore` / `loadMore` pattern.

Compact width opens a changelog dialog with an Open download page action. Medium and wider uses a list–detail split. Empty or non-HTTP links omit the action.

## Risks / Trade-offs

- **Server latest is newest by publish time, not semver** → Client compares versions; an accidentally republished older version with a newer timestamp will not notify if its tuple is not greater.
- **iOS bundle marketing version can disagree with BuildConfig** → Display and checks use BuildConfig; call out the packaging mismatch in release docs only if we touch that file.
- **Every native cold start with an update will replace the system notification** → Accepted; same id means replace, not a new daily stack. Banner stays off.
- **No background work after process death** → Next process start checks again; 5-hour loop only covers long-lived Desktop/foreground processes.
- **Empty or non-HTTP link** → User can see About-page text / bell title but the Open download page action is omitted or is a no-op; we do not invent a store URL.
- **Home bell badge will count this local unread** → Desired; this is a device notification, unlike announcements.

## Migration Plan

- No data migration. No new Settings sync keys.
- Rollback: remove the Settings row, runtime start, and API method; leftover in-memory notifications vanish on process exit.
- No server deploy; `/api/v1/updates/latest` already exists.

## Open Questions

None.
