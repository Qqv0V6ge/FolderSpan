## Why

The notification center only shows device-local messages and signed-in account messages. Public product announcements already exist on the message-center API (`GET /messages`) but have no client surface, so unsigned-in users never see them and signed-in users only see a copy if the backend happens to fan one out into their inbox.

## What Changes

- Add a third notification-center tab, **Announcements**, that lists public announcements from `GET /messages?type=announcement`.
- Show the tab without signing in (Local | Announcements when signed out; Local | Account | Announcements when signed in). The Account tab stays login-gated.
- Filter the list by the current client platform. Localize title and body through the existing `Accept-Language` request header.
- Track read/unread with a device-local published-at watermark initialized to "now" on first use, so historical announcements start as read and only later publications are unread.
- Reuse the existing notification Markdown detail and list-preview surfaces. Treat a non-empty `link` as a single HTTP(S) URL action.
- Keep announcement unread out of the home bell badge. Do not call `/updates`, do not send announcement system notifications, and do not dedupe against account notifications.

## Capabilities

### New Capabilities

- `notification-announcement-tab`: Browse the public announcement catalog in the notification center, including tab visibility, platform-filtered loading, local watermark read state, detail presentation, and the `link` action.

### Modified Capabilities

None. Announcement bodies reuse `notification-markdown-links` as-is; this change adds a catalog surface rather than changing Markdown, link, or fan-out preservation requirements.

## Impact

- Shared notification UI: `UnifiedNotificationScreen.kt`, `NotificationDetailScreen.kt`, and tab/filter helpers in `NotificationScreen.kt`.
- Pro networking: new public message-list API client beside `UserNotificationApiService`, plus domain model, mapper, and a runtime for paging/refresh.
- Device-local Settings watermark; platform mapping from `DeviceType` / OS to message-API platform tokens.
- Home/drawer unread badge stays local + account only.
- Tests for routing, 404-as-empty, watermark filters, tab visibility when signed out, and platform query mapping.
- Existing account-notification SSE, read/unread/delete APIs, and app-update endpoints are unchanged.
