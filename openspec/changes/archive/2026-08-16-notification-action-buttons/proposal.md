## Why

Account notifications currently carry a single `link` string. `openAccountNotification` decides what to do with a hardcoded regex (`^/support/reports/([^/?#]+)`), and the detail pane renders at most one button with a fixed label. Every new deep-linkable destination means editing that regex, and a destination can only be identified by a path convention rather than by name and typed parameters.

The server contract is becoming a structured `actions` list where an action targets either an external URL or an in-app route key with parameters. To consume it, the app needs a registry that turns a route key plus parameters into a real screen. That registry also needs to be exportable, so that whoever authors a notification can pick a destination by name instead of hand-writing a path — which in turn means the export must be verifiably in step with the code.

The project is pre-release; no backward compatibility with `link` is required.

## What Changes

- **BREAKING** Remove `link` from the account notification model: `UserNotificationDto.link`, `AccountNotification.link`, and `AccountNotificationPayloadKeys.Link`.
- Add `actions` to the account notification model. Each action is either "open an external URL" or "open an in-app screen with parameters". A label is a plain string already written in the notification's own language, so the app renders it verbatim and performs no button-label localization — exactly as it already does nothing to localize the title and body.
- Introduce a notification route registry: a single declaration mapping a stable route key to a typed parameter schema and a screen factory. It is the source of truth for both runtime resolution and build-time export.
- Add a Gradle task that serializes the registry to a `notification-routes.json` catalog artifact, plus a verification task that fails the build when the committed baseline drifts from the registry.
- Replace the regex-based `openAccountNotification` with action dispatch driven by the registry.
- Render up to three action buttons in both the notification detail pane and the split-pane detail view, matching the existing `RequestActionRow` treatment used by device-request notifications.
- Unknown route keys and parameter mismatches degrade gracefully: the button is disabled with an explanatory label rather than disappearing or crashing.
- Actions targeting screens that require a session redirect an unauthenticated user to sign-in and return to the intended screen afterwards, generalizing the existing `ProFeedbackLoginRoute(ticketUuid)` pattern.
- System notification click payloads carry the primary action instead of `link`.

## Capabilities

### New Capabilities

- `notification-route-registry`: the stable route-key catalog, its parameter schema, build-time export and drift verification, and runtime resolution with graceful degradation.
- `account-notification-actions`: how account notification action buttons are presented and what happens when they are activated, including external links, in-app navigation, and the unauthenticated path.

### Modified Capabilities

None. `app-navigation` already requires external entry points to open target screens through the project navigation boundary; the registry is an implementation of that requirement and does not change it.

## Impact

**New**

- Notification route registry declaration and runtime resolver (shared source set, must be reachable from both `app/shared` and `proMain`)
- Gradle export and drift-verification tasks, alongside the existing `validateAppStringCatalogs` task in `core/build.gradle.kts`
- Committed baseline `notification-routes.json`

**Modified**

- `proMain/kotlin/com/folderspan/pro/data/remote/dto/UserNotificationDtos.kt`
- `proMain/kotlin/com/folderspan/pro/domain/model/UserNotificationModels.kt`
- `proMain/kotlin/com/folderspan/pro/data/mapper/UserNotificationMapper.kt`
- `app/shared/src/commonMain/kotlin/com/folderspan/notification/AccountNotificationNavigation.kt`
- `app/shared/src/commonMain/kotlin/com/folderspan/notification/NotificationDeepLinkHandler.kt`
- `app/shared/src/commonMain/kotlin/com/folderspan/ui/screen/main/UnifiedNotificationScreen.kt`
- `proMain/kotlin/com/folderspan/pro/presentation/navigation/ProRoutes.kt` (pending-route sign-in flow)
- `core/src/commonMain/libres/strings/app_en.xml` and `app_zhHans.xml` (degradation and sign-in prompts)

**Published artifact**

- `notification-routes.json` becomes a build output of this repository. Its format is a contract with external consumers, so the format version and the drift-verification task are part of the deliverable, not conveniences.
