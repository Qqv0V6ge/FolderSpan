## Context

See `proposal.md` for motivation and `specs/notification-announcement-tab/spec.md` for observable behavior.

The live notification center is `NotificationScreen` in `UnifiedNotificationScreen.kt`. Tabs are `Local` and `Account`; they render only when signed in (`shouldShowNotificationTabs(loggedIn)`), and `PrimaryTabRow` uses `NotificationTab.ordinal`. Account messages already have a Pro stack: `UserNotificationApiService` → mapper → `UserNotificationRuntime` → UI. Public announcements have no client model. Gateway auth treats `/api/v1/messages` as public and `/api/v1/messages/user` as required. Traefik strips `/api/v1` before the message-api, whose announcement path is `/messages` (gateway `GET /api/v1/messages`). The HTTP client already sends `Accept-Language`. `UserNotificationDto.toDomainOrNull()` already converts second-or-millisecond timestamps with `toEpochMillis()`.

## Goals / Non-Goals

**Goals:**

- Add a third catalog source beside the two inboxes without changing account SSE, read/unread/delete, or local request notifications.
- Keep announcement HTTP, mapping, and read-cutoff out of Compose screens; screens consume a runtime snapshot the way they consume `UserNotificationRuntime`.
- Make signed-out tab layout a function of visible tabs, not enum ordinal.

**Non-Goals:**

- Calling `/updates` or `/updates/latest`.
- System notifications, SSE, or server-side announcement read state.
- Deduping catalog rows against `user_notifications`.
- Changing Markdown parsing; announcement bodies go through the existing notification Markdown surfaces.

## Decisions

### 1. Mirror the account-notification Pro stack for a public list

Add `MessageApiService.listMessages`, announcement DTOs/domain, a mapper, and `AnnouncementRuntime` (name may vary) that owns paging, refresh, error, and the local cutoff. Inject it next to `UserNotificationRuntime`. The list call does not attach a bearer token.

Alternative considered: fold announcements into `UserNotificationRuntime`. Rejected because auth, identity (`Long` vs UUID), lifecycle (catalog vs inbox), and mutations are different.

Alternative considered: write fetched rows into `NotificationState` as local notifications. Rejected because delete/read semantics and ids would collide with the device inbox.

### 2. Client path is the gateway collection root

Call `RouteBuilder.message()` (empty path) → `/api/v1/messages`. After Traefik strips `/api/v1`, the service sees `/messages`. Treat HTTP 404 the same way account list already does: empty page, not a hard failure. Reuse `toEpochMillis()` for `published_at` (RPC sends Unix seconds; swagger examples use milliseconds).

Alternative considered: `GET /api/v1/messages/messages`. Rejected; the gateway now strips only `/api/v1`, so that would hit `/messages/messages`, which the service does not serve.

Do not put `/updates` on `messagePrefix`; that gateway route also strips `/api/v1`, and this change does not call it.

### 3. Query `type=announcement` and a mapped platform token

Send `type=announcement` (never swagger's sample `default`). Map the running client to one of `android`, `ios`, `linux`, `macos`, `windows`, `js`, `wasm`:

| Client | Query `platform` |
|---|---|
| Android | `android` |
| iOS | `ios` |
| JVM macOS | `macos` |
| JVM Windows | `windows` |
| JVM otherwise | `linux` |
| JS and current Wasm builds (`DeviceType.JS`) | `js` |

Empty API `platform` still matches all platforms on the server. Wasm stays `js` this change because `PlatformType` is `JS` on wasmJs.

### 4. Visible-tab list, not enum ordinal

`shouldShowNotificationTabs` becomes true whenever more than one source is available (always, once announcements exist). Visible tabs:

- Signed out: Local, Announcements
- Signed in: Local, Account, Announcements

`PrimaryTabRow.selectedTabIndex` MUST index into that visible list. Keep `NotificationTab.Account` login-gated; signing out while it is selected falls back to Local (existing behavior) or Announcements only if that was the previous public tab—default to Local to match today's fallback.

`UnifiedNotificationKey` gains an Announcement branch keyed by numeric id. Announcement rows are not selectable. Top-bar mark-all-read on the Announcements tab advances the cutoff; refresh reloads the catalog.

### 5. One Settings watermark, initialized once to now

Store a single epoch-millis cutoff in device Settings. If the key is absent, write `Clock.System.now()` once when the runtime starts, not when the user first opens the tab. Unread means `published_at > cutoff`. Mark-all-read sets cutoff to `max(now, max loaded published_at)` so clock skew cannot leave the newest loaded row unread. Opening detail does not write the cutoff. There is no per-id exception set and no per-item mark/delete UI.

Alternative considered: per-id read set. Rejected; the agreed model is a timestamp catch-up line.

Alternative considered: initialize cutoff on first tab visit. Rejected; "follow from now" means from feature first-run on the device, so announcements published between update and first tab open stay read.

### 6. Synthetic URL action from `link`; reuse Markdown surfaces

Map a non-empty `http`/`https` `link` to one secondary-or-primary URL `NotificationAction` and render it with `AccountNotificationActionRow` / the existing dispatcher. Other schemes become an unavailable action. Empty link shows no action row. List preview uses `notificationPlainTextPreview`; detail uses `NotificationMarkdownContent`. Do not invent announcement-specific parsers.

### 7. Home badge stays local + account

Do not add announcement unread into `HomeScreen` / drawer badge math. The Announcements tab may still show unread dots on its own rows.

## Risks / Trade-offs

- **Watermark cannot express "I read this one row"** → Do not offer per-item read toggles; document mark-all-read as catch-up.
- **`GET /api/v1/messages` vs service `/messages`** → Match Traefik strip `/api/v1`; cover the encoded path in API tests the way account tests assert `/api/v1/messages/user/notifications`.
- **404 means empty on this API** → Map only announcement-list 404 to empty; keep other failures as retryable errors.
- **Same subject in Account and Announcements** → Accepted duplicate; no join key exists on the client.
- **Wasm announcements tagged only `wasm` will not show** → Accepted until `PlatformType` distinguishes wasm; passing both tokens is a later change.

## Migration Plan

- First launch after the change writes the cutoff; no data migration.
- Rollback is removing the tab and runtime; leftover Settings key is inert.
- No server deploy is required; the public list already exists.

## Open Questions

None.
