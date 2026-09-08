# Design: notification action buttons (client)

Scope: the route registry, its export format, and action dispatch. The `actions` wire format is a server contract this app consumes; only the fields the app reads are restated here.

An action as received:

```json
{
  "label":  "View ticket",
  "style":  "primary",
  "kind":   "route",
  "route":  "feedback_tickets",
  "params": { "ticketUuid": "3f2a9c1e-..." }
}
```

`kind` is `url` or `route`; `url` and `route` are mutually exclusive; `params` is a string map only meaningful for `route`; at most three actions per notification, at most one `primary`. `label` is already in the notification's language.

Both `route` and `params` originate outside the app and are **untrusted**: the server does not verify that a route key exists or that parameters match a screen. Validating them is this app's job.

## 1. Why a registry is unavoidable

There is no extractable source of truth for routes today:

```
Extractable:      ProRoute — @Serializable sealed interface, @SerialName is
                  already a stable key, constructor params are declared
                    feedback_tickets(initialTicketUuid: String?)
                    user_profile()   personal_settings()   ...

Not extractable:  AppScreenRoute — 40+ plain classes, no key convention,
                  parameters scattered across constructors
                    NotificationScreen(initialTab)
                    FileFilterManagerScreen(filterId: Long)
                    PermissionSettingsScreen()   SettingsScreen()   ...
```

Scraping sources with regexes would be fragile and would silently drift. Instead the registry is an explicit declaration that serves two consumers from one definition:

```
        NotificationRouteRegistry
                │
      ┌─────────┴─────────┐
      ▼                   ▼
 Gradle export       Runtime resolve
 → routes.json       → AppScreenRoute
```

Because both read the same declaration, a route that disappears from code disappears from the exported catalog on the next build.

Only a curated subset of the 40+ screens is registered. Screens whose parameters are local identifiers or filesystem paths must stay out unless there is a concrete need, since the backend can supply arbitrary values.

## 2. Registry entry shape

Each entry declares:

- `routeKey` — stable string, snake_case, never renamed once shipped
- `displayName` — `zh-CN` / `en-US` labels, so a consumer's route picker can show a human name rather than the key
- `group` — grouping hint for such a picker (`account`, `settings`, `files`, ...)
- `requiresAuth` — whether an active session is needed
- `params` — ordered parameter declarations
- a factory turning validated parameters into an `AppScreenRoute`

Parameter declaration: `name`, `type` (`string` | `long` | `boolean` | `enum`), `required`, `displayName` (`zh-CN` / `en-US`), and for `enum` the permitted `options`.

## 3. Export format (`notification-routes.json`)

```json
{
  "version": 1,
  "routes": [
    {
      "routeKey": "feedback_tickets",
      "displayName": { "zh-CN": "工单详情", "en-US": "Ticket details" },
      "group": "account",
      "requiresAuth": true,
      "params": [
        {
          "name": "ticketUuid",
          "type": "string",
          "required": false,
          "displayName": { "zh-CN": "工单 UUID", "en-US": "Ticket UUID" }
        }
      ]
    },
    {
      "routeKey": "notification_center",
      "displayName": { "zh-CN": "通知中心", "en-US": "Notification center" },
      "group": "account",
      "requiresAuth": false,
      "params": []
    }
  ]
}
```

`version` guards the format itself. Entries are sorted by `routeKey` so the file is diff-stable. No timestamp or build id is embedded — a generated timestamp would make every build dirty the baseline and defeat drift verification.

## 4. Build integration

Two Gradle tasks, sitting next to the existing `validateAppStringCatalogs` in `core/build.gradle.kts`:

- `exportNotificationRoutes` — serializes the registry and writes the committed baseline
- `verifyNotificationRoutes` — regenerates into a temp location and fails if it differs from the baseline; wired into `check`

The baseline is committed so route changes are visible in review, and CI publishes it as a build artifact.

```
./gradlew :core:exportNotificationRoutes
  → notification-routes.json
      ├ committed baseline (reviewable, drift-verified)
      └ CI artifact (consumed downstream by deployment)
```

How a consumer obtains the artifact is outside this repository's concern. What this repository owes them is that the file is deterministic, versioned, and never silently out of step with the code — hence the drift check in `check`.

## 5. Action dispatch

```
action
  │
  ├ kind = url ──── http/https ──▶ openUrl(url)
  │                 otherwise ───▶ disabled button
  │
  └ kind = route ─▶ registry[routeKey]
                      │
                      ├ missing ─────────▶ disabled button + "not supported
                      │                     in this version" label
                      │
                      ├ params invalid ──▶ disabled button (same treatment)
                      │
                      └ resolved
                          │
                          ├ requiresAuth && no session
                          │     └▶ sign-in screen carrying the pending route
                          │          └▶ on success, replace with target screen
                          │
                          └▶ mainState.requestOpenScreen(screen)
```

Parameter validation means: every `required` parameter is present, every supplied key is declared, and every value parses to its declared type. Unknown keys are rejected rather than ignored, so a backend typo surfaces as a disabled button instead of a silently wrong destination.

`openAccountNotification` loses its regex entirely. The `/support/reports/{uuid}` case becomes an ordinary registry entry (`feedback_tickets` with `ticketUuid`), produced by the report service.

## 6. Sign-in with a pending route

`ProFeedbackLoginRoute(ticketUuid)` already implements "go to sign-in carrying a target, then `replaceLast` to the target on success". This generalizes to a pending-route field so any `requiresAuth` registry entry can use it, rather than adding one login route variant per destination.

Note that the Account tab is only reachable while signed in, so this path is mostly hit when a session expires between opening the notification and pressing the button, or when a button is activated from a system notification.

## 7. Button presentation

Up to three buttons, rendered with the same treatment as `RequestActionRow` so account and device notifications look consistent. One action may be `primary`; the rest are secondary. Buttons appear in the detail pane and in the split-pane detail view, not in list rows — list rows stay compact, and device-request notifications already own the in-row action area.

Button labels are server-authored strings in the notification's language, so they are rendered verbatim. They are **not** run through `AppStrings`, and they do not follow the app UI language: a notification published in Simplified Chinese shows Chinese buttons even when the app UI is English, consistent with its title and body. Only the degradation and sign-in prompts described below are client strings and therefore localized.

Disabled buttons remain visible with an explanatory label. Hiding them would make a backend misconfiguration invisible to both the user and support.

## 8. System notification payloads

`NotificationPayload` is a `Map<String, String>`, so structured actions cannot travel as-is. `AccountNotificationPayloadKeys.Link` is replaced by keys carrying the **primary action only** — its kind, target, and serialized parameters. Tapping the notification body performs that action; the remaining actions are available after opening the notification in-app.

Encoding the full action list into the payload was considered and rejected: platform payload size limits vary, and a system notification affords one tap target anyway.
