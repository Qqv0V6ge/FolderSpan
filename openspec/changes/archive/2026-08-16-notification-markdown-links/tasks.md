## 1. Notification Markdown parser

- [x] 1.1 Add common-source-set immutable block and inline models for notification Markdown
- [x] 1.2 Implement bounded parsing for the supported Markdown subset and `[label](target)` grammar while preserving plain text, malformed syntax, and source order
- [x] 1.3 Add a plain-text preview conversion that removes formatting and destinations while retaining visible labels, image alternative text, and document structure
- [x] 1.4 Unit-test plain content, formatting, links, images, escaped delimiters, line breaks, empty components, and malformed or unclosed syntax
- [x] 1.5 Add bounded-content tests or benchmarks demonstrating linear parser behavior for long and adversarial notification content

## 2. Safe target conversion and dispatch

- [x] 2.1 Convert `http` and `https` link targets into the existing notification URL-action model; classify every other external scheme as unavailable
- [x] 2.2 Parse `route:<routeKey>?<query>` targets into the existing notification route-action model with UTF-8 percent-decoded string parameters
- [x] 2.3 Reject empty route keys or parameter names, duplicate parameters, fragments, malformed percent encoding, and unsupported target forms without throwing
- [x] 2.4 Reuse `isAccountNotificationActionAvailable` and `dispatchAccountNotificationAction` for link availability and activation instead of adding a parallel navigation path
- [x] 2.5 Unit-test safe external URLs, custom schemes, parameterless routes, encoded route parameters, unknown routes, invalid parameters, authentication redirect/return, and abandoned sign-in

## 3. Detail rendering and accessibility

- [x] 3.1 Add a reusable common Compose notification-content component that renders parsed segments while preserving typography, whitespace, and wrapping
- [x] 3.2 Style only supported targets as links; render unsupported targets as ordinary visible labels without actionable semantics
- [x] 3.3 Expose a link role and visible-label semantics for supported targets and support pointer, touch, and keyboard activation on the shared targets
- [x] 3.4 Replace the account-notification detail body's plain `Text` in both split-pane and standalone detail flows with the reusable component
- [x] 3.5 Verify inline links and structured action buttons coexist and dispatch only their own targets
- [x] 3.6 Compose UI-test supported, unsupported, multiple, keyboard-focused, and accessible link cases

## 4. Compact and system-notification previews

- [x] 4.1 Use the plain-text preview for account-notification list rows while retaining the row's existing click and selection behavior
- [x] 4.2 Use the plain-text preview for `AccountNotificationBannerHost` while retaining the card's primary-action behavior and dismiss control
- [x] 4.3 Use the plain-text preview as the local/system notification body so Markdown syntax and destinations are not shown by the operating system
- [x] 4.4 Keep notification titles on all surfaces as literal plain text
- [x] 4.5 Test list, banner, and system-notification previews with external, route, malformed, and mixed content

## 5. Announcement-to-user-notification contract

- [x] 5.1 Locate the repository or service that owns `announcements` fan-out and document the concrete integration point; do not add a disconnected client announcement model
- [x] 5.2 Update that fan-out to copy `announcements.content` into `user_notifications.content` without trimming, normalization, Markdown rendering, decoding, or re-encoding
- [x] 5.3 Add upstream tests proving Markdown syntax, whitespace, line breaks, escaping, and percent-encoded route parameters survive fan-out exactly
- [x] 5.4 Add client DTO/API tests proving announcement-shaped `user_notifications.content` decodes and remains unchanged through domain mapping
- [x] 5.5 Record that the upstream owner is outside the available workspace and retain user-provided completion evidence for 5.2–5.3 before claiming end-to-end announcement support

## 6. Documentation and verification

- [x] 6.1 Document the supported Markdown authoring contract, safe links and HTTP(S) images alongside the notification route catalog, and state which Markdown features and URI schemes remain unsupported
- [x] 6.2 Update `md_descriptions_paths.md` for every Markdown file added or modified by implementation
- [x] 6.3 Run parser, target conversion, notification UI, shared, Pro, and relevant upstream fan-out test suites
- [x] 6.4 Run `./gradlew :core:verifyNotificationRoutes` and verify all route examples against the committed catalog
- [x] 6.5 Manually verify on one desktop and one mobile target: external link, parameterized in-app route, sign-in redirect/return, unsupported target, action-button coexistence, list preview, and banner preview
- [x] 6.6 Run `openspec validate notification-markdown-links --strict`
