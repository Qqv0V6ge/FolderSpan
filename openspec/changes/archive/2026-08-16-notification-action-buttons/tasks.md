# Tasks: notification action buttons (client)

## 1. Route registry

- [x] 1.1 Add `NotificationRouteRegistry` in a source set reachable from both `app/shared` and `proMain`, declaring for each route: `routeKey`, `displayName` (zh-CN / en-US), `group`, `requiresAuth`, ordered parameter declarations, and a screen factory
- [x] 1.2 Add parameter declaration types supporting `string`, `long`, `boolean`, `enum` (with options), each with `name`, `required`, and display names
- [x] 1.3 Seed the catalog with the destinations needed now: `feedback_tickets(ticketUuid: String?)`, `notification_center`, `user_profile`, `personal_settings`, `permission_settings`, `settings`
- [x] 1.4 Deliberately exclude screens whose parameters are local identifiers or filesystem paths (`FileFilterManagerScreen`, `NetworkEditScreen`, …); record the exclusion rationale next to the declaration
- [x] 1.5 Implement `resolve(routeKey, params)`: reject unknown keys, missing required params, undeclared param names, and values that fail to parse as their declared type; return no screen instead of throwing
- [x] 1.6 Unit tests: valid resolution with and without parameters, unknown key, missing required, undeclared name, wrong type, duplicate `routeKey` detection

## 2. Build-time export

- [x] 2.1 Add a `exportNotificationRoutes` Gradle task next to `validateAppStringCatalogs` in `core/build.gradle.kts` that serializes the registry to `notification-routes.json`
- [x] 2.2 Sort entries by `routeKey` and emit no timestamp or build id, so output is byte-stable
- [x] 2.3 Emit the catalog format `version` field
- [x] 2.4 Add `verifyNotificationRoutes` that regenerates into a temp location, diffs against the committed baseline, and fails with the mismatch; wire it into `check`
- [x] 2.5 Commit the generated `notification-routes.json` baseline
- [x] 2.6 Publish `notification-routes.json` as a CI artifact so downstream consumers can obtain it

## 3. Model and transport

- [x] 3.1 `UserNotificationDtos.kt`: remove `link`, add `actions` (`label`, `style`, `kind`, `url`, `route`, `params`) with defaults so an absent list decodes as empty
- [x] 3.2 `UserNotificationModels.kt`: remove `AccountNotification.link`, add `actions: List<NotificationAction>`; add the `NotificationAction` domain model and its style/kind enums
- [x] 3.3 `UserNotificationModels.kt`: replace `AccountNotificationPayloadKeys.Link` with keys carrying the primary action's kind, target, and serialized parameters
- [x] 3.4 `UserNotificationMapper.kt`: map actions, dropping malformed entries rather than failing the whole notification
- [x] 3.5 Unit tests in `UserNotificationMapperTest` and `UserNotificationApiServiceTest`: actions present, actions absent, malformed action entry

## 4. Action dispatch

- [x] 4.1 Rewrite `AccountNotificationNavigation.kt`: delete the `^/support/reports/([^/?#]+)` regex and dispatch on action kind through the registry
- [x] 4.2 `kind=url`: open only `http`/`https`; any other scheme resolves to unavailable
- [x] 4.3 `kind=route`: resolve through the registry; unresolvable resolves to unavailable
- [x] 4.4 Add a pending-route sign-in flow in `ProRoutes.kt`, generalizing the existing `ProFeedbackLoginRoute(ticketUuid)` pattern so any `requiresAuth` route can be the post-sign-in destination
- [x] 4.5 Discard the pending destination when sign-in is abandoned
- [x] 4.6 `NotificationDeepLinkHandler.kt`: perform the primary action from the system notification payload; fall back to the notification center when the notification has no actions or the primary action is unresolvable
- [x] 4.7 Unit tests: url dispatch, route dispatch with parameters, unavailable cases, sign-in redirect and return, abandoned sign-in

## 5. UI

- [x] 5.1 `UnifiedNotificationScreen.kt`: replace the single `isActionableNotificationLink` button block with an action row rendering up to three buttons
- [x] 5.2 Match the existing `RequestActionRow` treatment; support one `primary` action with the rest secondary
- [x] 5.3 Render unavailable actions as visible disabled buttons with an explanatory label
- [x] 5.4 Render server-supplied labels verbatim without passing them through `AppStrings`
- [x] 5.5 Ensure the action row appears in both the split-pane detail view and `NotificationDetailScreen`
- [x] 5.6 Delete `isActionableNotificationLink` and any remaining `link` references

## 6. Strings

- [x] 6.1 Add `app_en.xml` / `app_zhHans.xml` entries for the unsupported-destination label and the sign-in prompt
- [x] 6.2 Remove `ui_notification_related_content` if no longer referenced
- [x] 6.3 `./gradlew :core:validateAppStringCatalogs`

## 7. Verification

- [x] 7.1 `./gradlew build` and the shared/pro test suites
- [x] 7.2 `./gradlew :core:verifyNotificationRoutes`
- [x] 7.3 Repository-wide search for `AccountNotification.link`, `AccountNotificationPayloadKeys.Link`, and `isActionableNotificationLink` residue
- [x] 7.4 Manually verify on one desktop and one mobile target: multi-button rendering, disabled unsupported button, external link, in-app navigation with a parameter, sign-in redirect and return
- [x] 7.5 `openspec validate notification-action-buttons --strict`
