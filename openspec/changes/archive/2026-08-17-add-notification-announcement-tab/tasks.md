## 1. API client and mapping

- [x] 1.1 Add announcement list DTOs and domain types (numeric id, type, title, content, version, platform, link, publishedAt, locale) plus a paged snapshot, modeled next to `UserNotification*` without a bearer token
- [x] 1.2 Add `MessageApiService.listMessages` calling `RouteBuilder.message()` (gateway `GET /api/v1/messages`) with `type=announcement`, mapped `platform`, `page`, and `pageSize`; do not send `Authorization`
- [x] 1.3 Treat HTTP 404 as an empty page; treat other non-success statuses as failures
- [x] 1.4 Map `published_at` with the existing second-or-millisecond heuristic; keep `content` verbatim
- [x] 1.5 Map a non-empty `http`/`https` `link` to one URL action; map any other scheme to an unavailable action; map blank link to no actions
- [x] 1.6 Unit tests: encoded path `/api/v1/messages`, query params, no Authorization header, 404 → empty, mapping of seconds vs millis, link action cases

## 2. Platform token and local cutoff

- [x] 2.1 Add a mapper from the running client to one of `android`, `ios`, `macos`, `windows`, `linux`, `js` per `design.md` (wasm stays `js`)
- [x] 2.2 Add a device Settings key for the announcement published-at cutoff; do not add it to the settings-sync whitelist
- [x] 2.3 If the key is absent, persist `Clock.System.now()` once at runtime start
- [x] 2.4 Unread means `publishedAt > cutoff`; mark-all-read sets cutoff to `max(now, max loaded publishedAt)`
- [x] 2.5 Unit tests: platform mapping, first-run init, unread/read comparison, mark-all-read covering loaded items, sign-in/out does not rewrite the cutoff

## 3. Announcement runtime

- [x] 3.1 Add `AnnouncementRuntime` (or equivalent) owning list snapshot, paging, refresh, error, and cutoff, without requiring a session
- [x] 3.2 Refresh on tab/foreground as needed; `loadMore` appends pages; retry clears the error
- [x] 3.3 Opening detail does not write the cutoff
- [x] 3.4 Register the runtime in Koin next to `UserNotificationRuntime` and start it with the app so first-run cutoff is written even before the tab is opened
- [x] 3.5 Unit tests: paging merge, 404 empty vs other errors, mark-all-read, detail open leaves cutoff unchanged

## 4. Notification center UI

- [x] 4.1 Add `NotificationTab.Announcement` and visible-tab helpers: signed out → Local + Announcements; signed in → Local + Account + Announcements
- [x] 4.2 Drive `PrimaryTabRow.selectedTabIndex` from the visible-tab list, not enum ordinal; fall back to Local when signing out of Account
- [x] 4.3 Add `UnifiedNotificationKey.Announcement` and a timeline item; announcement rows are not selectable and have no delete or per-item read toggles
- [x] 4.4 Apply All / Unread / Read filters locally against the cutoff; do not call account status APIs from this tab
- [x] 4.5 Wire refresh, load more, retry, and mark-all-read on the Announcements tab
- [x] 4.6 List rows use the existing plain-text Markdown preview; titles stay literal text
- [x] 4.7 Detail (split pane and `NotificationDetailScreen`) renders `NotificationMarkdownContent` and the synthetic link action through the existing dispatcher
- [x] 4.8 Add `app_en.xml` / `app_zhHans.xml` strings for the Announcements tab and empty/error copy; run `./gradlew :core:validateAppStringCatalogs`
- [x] 4.9 Update `NotificationScreenNavigationTest` (and related UI tests) for signed-out two-tab layout, three-tab signed-in layout, filter behavior, and missing delete/read toggles

## 5. Badge, docs, and verification

- [x] 5.1 Confirm Home and drawer unread badges still sum only local + account unread; add a regression assertion if one is missing
- [x] 5.2 Update `docs/notifications.md` so the announcement catalog client (`GET /api/v1/messages`, public, local watermark, not in the bell badge) is documented beside the existing fan-out boundary
- [x] 5.3 Update `md_descriptions_paths.md` for changed docs and this OpenSpec change
- [x] 5.4 `./gradlew :proMain:jvmTest :app:shared:jvmTest :core:jvmTest` (or the equivalent module test tasks covering the new code)
- [x] 5.5 `openspec validate add-notification-announcement-tab --strict`
