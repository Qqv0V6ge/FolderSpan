## Why

Announcement and account-notification content is currently rendered as plain text, so an author cannot place a contextual link inside the message body. Action buttons can already open safe external URLs or validated in-app routes; inline links should offer the same navigation behavior without requiring every reference to become a separate button.

## What Changes

- Render supported Markdown structures in `announcements.content` and `user_notifications.content`, including safe inline links and HTTP(S) images, while preserving ordinary text unchanged.
- Preserve announcement content verbatim when it is copied or materialized into a user notification.
- Render rich Markdown, interactive links, and images only in full notification detail views; list rows, transient banners, and system notifications use a plain-text preview to avoid nested click targets and platform-specific Markdown behavior.
- Support two safe target classes: `http`/`https` external URLs and explicitly encoded in-app route targets resolved through the notification route registry.
- Reuse the notification-action dispatcher for URL allowlisting, route parameter validation, authentication redirects, and unsupported-target degradation.
- Keep titles plain text; support headings, emphasis, strikethrough, code, lists, blockquotes, horizontal rules, and HTTP(S) images while excluding raw HTML, tables, arbitrary URI schemes, and unsupported Markdown extensions.

## Capabilities

### New Capabilities

- `notification-markdown-links`: Safe parsing, presentation, previewing, image display, and activation of Markdown content in announcement-derived and account-notification content.

### Modified Capabilities

None.

## Impact

- Shared notification UI, especially `UnifiedNotificationScreen.kt` and `AccountNotificationBannerHost.kt`.
- A reusable common-source-set Markdown parser/renderer and its accessibility semantics.
- Existing notification action dispatch and route registry are reused rather than duplicated.
- Announcement-to-user-notification delivery must preserve `content` exactly; no new account-notification DTO field is required.
- Tests for parsing, safe target resolution, previews, cross-platform interaction, and malformed or unsupported links.
