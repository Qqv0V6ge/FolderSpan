## Why

Users have no in-app place to see the current version or learn that a newer build exists. The message-center already publishes platform-scoped app updates at `GET /updates/latest`, but the client never calls it. Without a check, people stay on stale native installs until they notice a store listing or a download page.

## What Changes

- Add a Settings entry **About software** that shows the running app version.
- On Android, iOS, and Desktop, that page also offers **Check for updates**, which calls the public latest-update API for the selected channel (`release` by default, or `beta`) and shows the result in a dialog (up to date, newer version with changelog and link, or retryable failure). Opening About software or switching the channel does not request latest; only Check for updates and the start/5-hour loop do.
- Every target, including Web, offers a **Version history** item that lists published updates for the current platform and selected channel from `GET /api/v1/updates`. Selecting a version shows notes; only an Open download page action opens the link.
- On those same native targets, request latest on process start and again every 5 hours while the process is alive. Do not request on resume from background.
- When the latest published version is newer than the running version, upsert one device-local notification (bell Local tab) and replace the matching system notification by a stable id. An Open download page action on the bell row, notification detail, and system notification opens the update `link` outside the app. Tapping the notification body opens the Local detail instead of the link.
- Web JS/Wasm does not poll, does not show Check for updates, and does not post update notifications; it still offers Version history.
- No forced update, no blocking dialog, no in-app installer. Announcement catalog and `/messages` stay unchanged.

## Capabilities

### New Capabilities

- `about-software-app-updates`: Settings About software page, runtime version display, native latest-update checks (manual and start-plus-5-hour), version comparison, version history list, and a single upserted device notification whose action button opens the update link.

### Modified Capabilities

None. This change reuses `local-system-notifications` and the notification dispatcher as-is. It does not change announcement-tab requirements, which continue to exclude `/updates`.

## Impact

- Settings: new About software screen and navigation entry.
- Runtime version: expose `releaseVersion` (and iOS marketing version) to common code so comparison is possible on every native target.
- Pro networking: public `GET /api/v1/updates/latest?platform=…&channel=…` beside the existing message list client, using a dedicated updates prefix (not `messagePrefix`). `channel` is `release` or `beta` (default `release`) and is chosen on the About software page.
- App runtime: start-once checker with a 5-hour loop on Android, iOS, and Desktop only; not wired to `OnAppResumeEffect`.
- Device notifications: `RequestNotificationFactory` + `RequestNotificationDispatcher` + Open download page action that deep-links to `openUrl(link)`.
- Docs: `docs/notifications.md` and `md_descriptions_paths.md`.
- Tests: version compare, platform mapping, Web skip, upsert/id stability, link open, 404/empty vs failure.
