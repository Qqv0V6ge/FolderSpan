# notification-markdown-links Specification

## Purpose

Define safe, consistent Markdown behavior for announcement-derived and account-notification content across detail, preview, image, and navigation surfaces.

## Requirements
### Requirement: Announcement content is preserved in user notifications

When an announcement is materialized or copied into a user notification, the system SHALL preserve the announcement `content` string exactly, including Markdown link labels, destinations, escaping, whitespace, and line breaks.

#### Scenario: Announcement link reaches the user notification unchanged

- **WHEN** an announcement containing `[Read more](https://example.com/news)` is materialized as a user notification
- **THEN** the user notification contains exactly `[Read more](https://example.com/news)` in its `content`

#### Scenario: Route parameters remain encoded

- **WHEN** announcement content contains a route link with percent-encoded parameter values
- **THEN** materialization does not decode, normalize, or re-encode that link

### Requirement: Notification content recognizes inline Markdown links

The system SHALL recognize the inline form `[label](target)` in notification content, SHALL support multiple links in document order, and SHALL leave surrounding text unchanged. Link parsing SHALL coexist with the supported block and inline Markdown structures.

#### Scenario: Plain content is unchanged

- **WHEN** notification content contains no valid inline Markdown link
- **THEN** it is presented with the same text and line breaks as the source content

#### Scenario: Multiple links retain their order

- **WHEN** content contains several valid inline Markdown links
- **THEN** their labels appear at the corresponding source positions and activate their own targets

#### Scenario: Malformed syntax remains visible

- **WHEN** content contains an unclosed or otherwise malformed inline link
- **THEN** the malformed source text remains visible as literal text and is not interactive

### Requirement: Notification detail renders the supported Markdown subset

Full notification detail content SHALL render headings, emphasis, strikethrough, inline and fenced code, ordered and unordered lists, blockquotes, horizontal rules, and images. Raw HTML, tables, unsupported nesting, and undocumented extensions SHALL NOT be interpreted as executable or interactive content.

#### Scenario: Block and inline formatting is rendered

- **WHEN** notification content contains supported headings, lists, quotes, code, or inline formatting
- **THEN** the detail view presents the corresponding visual structure while retaining the source text

#### Scenario: HTTP image is presented in detail

- **WHEN** notification detail contains an image with an `http` or `https` destination
- **THEN** the image is loaded in the detail content and can open the shared image preview using its title or alternative text as the caption

#### Scenario: Unsupported image target stays safe

- **WHEN** an image uses a non-HTTP(S) destination
- **THEN** the client does not load or open that destination and keeps a readable non-interactive fallback

### Requirement: Links are interactive only in full detail content

Valid links in a full announcement or account-notification detail view SHALL be visually distinguishable and interactive. Compact list rows, transient banners, and system-notification bodies SHALL show plain-text previews that omit formatting syntax and destinations, replace links with their labels, and replace images with their alternative text. Notification titles SHALL remain plain text on every surface.

#### Scenario: Detail view exposes a link

- **WHEN** a notification containing a valid supported link is opened in full detail
- **THEN** its label is presented as an interactive link

#### Scenario: List and banner use the label as preview text

- **WHEN** the same notification is shown in a list row or transient banner
- **THEN** the preview shows the link label without Markdown delimiters and the containing row or banner retains its existing click behavior

#### Scenario: System notification does not expose Markdown syntax

- **WHEN** the notification is presented by an operating-system notification surface
- **THEN** the body contains the plain-text preview and no Markdown destination

#### Scenario: Title Markdown remains literal

- **WHEN** a notification title contains Markdown-like text
- **THEN** the title is displayed as plain text without creating an inline link

### Requirement: External Markdown links use the safe URL policy

An inline target using the `http` or `https` scheme SHALL activate through the same external-opening policy as a notification URL action. No other external URI scheme SHALL be opened.

#### Scenario: HTTPS link opens externally

- **WHEN** the user activates `[Documentation](https://example.com/docs)` in notification detail
- **THEN** the system opens `https://example.com/docs` outside the app

#### Scenario: Non-HTTP scheme is rejected

- **WHEN** an inline target uses `javascript`, `data`, `file`, `mailto`, or another non-HTTP external scheme
- **THEN** its label remains visible but activation opens nothing

### Requirement: In-app Markdown links use registered notification routes

An inline target of the form `route:<routeKey>` with an optional percent-encoded query string SHALL be treated as an in-app notification action. The route key and decoded string parameters SHALL be resolved through the notification route registry, including its authentication and parameter-validation rules.

#### Scenario: Parameterless route opens its screen

- **WHEN** the user activates `[Settings](route:settings)`
- **THEN** the registered `settings` destination opens in the app

#### Scenario: Route query supplies a parameter

- **WHEN** the user activates `[View ticket](route:feedback_tickets?ticketUuid=ticket%2D123)`
- **THEN** `feedback_tickets` is resolved with `ticketUuid` equal to `ticket-123`

#### Scenario: Authenticated destination preserves the target

- **WHEN** a signed-out user activates an inline route whose destination requires authentication
- **THEN** the system presents sign-in and continues to that route with its parameters after successful authentication

### Requirement: Unsupported inline targets degrade without affecting content

Unknown route keys, invalid or duplicate route parameters, malformed percent encoding, unsupported schemes, and otherwise unresolvable targets SHALL remain visible as non-interactive labels. They SHALL NOT navigate, open an external application, hide surrounding text, or prevent other valid links and action buttons from working.

#### Scenario: Unknown route remains readable

- **WHEN** content contains `[New feature](route:not_available_in_this_version)`
- **THEN** `New feature` remains visible but is not interactive

#### Scenario: Invalid parameter does not affect another link

- **WHEN** one route link has invalid parameters and another link is valid
- **THEN** the invalid link is non-interactive and the valid link remains usable

#### Scenario: Inline link and action button coexist

- **WHEN** a notification contains both inline links and structured action buttons
- **THEN** each control activates only its own target and all controls retain their supplied order and presentation

### Requirement: Interactive links are accessible across supported input modes

Each supported inline link SHALL expose link semantics and an accessible label, and SHALL be activatable using the platform's supported pointer, touch, and keyboard interaction. Unsupported targets SHALL not expose an actionable link semantic.

#### Scenario: Keyboard activates only the focused link

- **WHEN** a keyboard user focuses and activates an inline link in notification detail
- **THEN** only that link's target is dispatched

#### Scenario: Screen reader identifies supported links

- **WHEN** assistive technology traverses notification detail content
- **THEN** each supported inline link is announced using its visible label and link role
