## Context

See `proposal.md` for motivation and `specs/notification-markdown-links/spec.md` for observable behavior.

Account-notification content is currently a raw `String` rendered by ordinary Compose `Text` calls. The full detail body is independently scrollable, while list rows and transient banners make their entire container clickable. System notifications also receive the same raw body. The repository has no existing Markdown renderer or Markdown dependency.

The active notification-action work already provides the security-sensitive pieces needed by links: `http`/`https` allowlisting, a curated in-app route registry, typed parameter validation, graceful rejection, and pending-route sign-in. Link handling must reuse that boundary rather than create a second navigation system.

No `announcements` model, endpoint, or rendering surface currently exists in this repository. Announcement preservation is therefore an integration contract for the upstream announcement fan-out; this repository can verify the received `user_notifications.content` value and render it, but must not invent a disconnected local announcement model.

## Goals / Non-Goals

**Goals:**

- Parse notification content deterministically and identically on every Compose Multiplatform target.
- Render a documented Markdown subset, including block structures, inline formatting, safe links, and HTTP(S) images.
- Give inline links the same safe dispatch semantics as structured notification actions.
- Keep compact surfaces readable without nesting link click targets inside clickable rows or cards.
- Preserve existing plain-text content and structured action buttons.
- Keep parsing linear in input size and suitable for recomposition caching.

**Non-Goals:**

- Complete CommonMark compatibility, including tables, nested block structures, raw HTML, and arbitrary extensions.
- Making notification titles interactive.
- Loading link previews, HTML, remote metadata, or images from non-HTTP(S) schemes.
- Registering a platform-wide deep-link handler for the internal `route:` syntax.
- Adding an announcement API or screen to this client without an actual upstream contract and integration point.

## Decisions

### 1. Parse a documented Markdown subset into immutable blocks and segments

A common-source-set parser turns content into ordered block and inline models. Supported blocks include headings, paragraphs, ordered and unordered lists, fenced code, blockquotes, and horizontal rules. Inline content supports emphasis, strikethrough, code, links, and images. The same model produces both rich detail content and a plain-text preview; malformed or unsupported constructs remain readable.

The parser uses bounded state-machine passes rather than a broad regular expression. That keeps work linear in the input size and makes malformed delimiters, escapes, and multiple links predictable. Parsed output is cached by the original content string at the Compose boundary.

Alternative considered: add a full Markdown rendering library. Rejected because the supported subset is intentionally controlled, the project has no such dependency, and raw HTML or arbitrary extensions would expand the security and compatibility surface.

### 2. Convert link targets to the existing notification action model

Target classification happens after syntax parsing:

```
target
  ├─ http:// or https:// ───────────────▶ URL action
  ├─ route:<key>?<encoded parameters> ─▶ route action
  └─ anything else ─────────────────────▶ unavailable
```

The `route:` form is an in-content authoring contract, not an operating-system URI. Everything after `route:` and before `?` is the route key. Query names and values are UTF-8 percent-decoded into the string parameter map. Empty names, duplicate names, malformed percent encoding, and fragments make the target unavailable. The existing registry remains authoritative for declared names, required values, types, route availability, and authentication.

Both availability checks and activation call the existing notification-action dispatcher. This prevents drift between buttons and inline links.

Alternative considered: allow arbitrary app URLs such as `folderspan://...`. Rejected because it would require a second deep-link routing convention and might accidentally escape to platform URL handling.

### 3. Render rich content only where interactive content has an unambiguous owner

The full notification detail body uses a reusable Markdown composable. It renders supported blocks and inline formatting, presents supported link segments with link styling and semantics, and dispatches their converted actions. HTTP(S) images load only in detail content and open the shared image-preview overlay. Unsupported link targets remain ordinary text, and unsupported image targets never invoke platform URL handling.

List rows, account-notification banners, and system-notification bodies use the parser's plain-text preview. Formatting delimiters and destinations are omitted, links contribute their labels, and images contribute their alternative text. This avoids nested click handling because list rows and banners already use their whole surface to open notification detail or a primary action.

If a future announcement detail screen is added, it consumes the same reusable component rather than a separate parser.

### 4. Preserve raw content through transport and persistence

DTOs and domain models continue to store the original content string; parsed segments are presentation data and are not serialized or persisted. The announcement fan-out must copy `announcements.content` to `user_notifications.content` byte-for-byte at the text-contract level, without pre-rendering or normalization.

Client transport tests include announcement-shaped Markdown content and assert exact decoding. The repository that owns the upstream fan-out verifies exact-copy behavior independently; its completed verification is recorded as external evidence because its source is outside this workspace.

### 5. Keep unsupported links safe and locally diagnosable

Unsupported links remain readable, but detail rendering does not invoke platform URL APIs or navigation for them. The client does not fetch a target while parsing or rendering. Tests cover custom schemes, unknown routes, undeclared and duplicate parameters, malformed encoding, and mixed valid/invalid content.

No raw HTML is interpreted, so server-authored content cannot inject executable markup into any target.

## Risks / Trade-offs

- **The supported subset may surprise authors expecting full CommonMark** → Publish the supported blocks, inline forms, image policy, `[label](target)`, and `route:` contract alongside the route catalog.
- **Older clients display Markdown delimiters** → Deploy link-capable clients before authors publish linked announcements, or gate publication by minimum client version.
- **Announcement fan-out lives outside this repository** → Track its exact-copy requirement explicitly and retain verification evidence for both upstream fan-out tests and client transport fixtures before declaring end-to-end completion.
- **Nested brackets and complex destinations are intentionally unsupported** → Require percent encoding for reserved target characters and keep malformed source visible instead of guessing.
- **Long content is reparsed during recomposition** → Use a linear parser and cache parsed segments by raw content.
- **Route syntax could drift from the exported catalog** → Treat route keys and parameters as registry-owned and test examples against the committed route catalog.

## Migration Plan

1. Add the Markdown parser, target conversion, preview generation, and tests without changing any rendering surface.
2. Switch compact and system-notification surfaces to plain-text previews.
3. Enable rich Markdown, interactive links, and HTTP(S) image preview in full account-notification detail and any real announcement detail integration point.
4. Update and verify the upstream announcement fan-out exact-copy behavior.
5. After supported clients are deployed, allow notification authors to publish inline links and `route:` targets.

Rollback is presentation-only: restore raw plain-text rendering and stop authoring Markdown links. Stored source content remains unchanged, so no data migration is required.
