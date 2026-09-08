## Context
The app runs on Android, iOS, JVM desktop, and JS/Wasm. Permissions are platform-specific (Android runtime and special permissions, iOS/JS notifications). We need a settings page to view and request required permissions across platforms. Android already has a PermissionController used at startup.

## Goals / Non-Goals
- Goals:
  - Provide a common permissions settings screen with descriptions and current status.
  - Allow users to request permissions from that screen.
  - Hide the settings entry when the platform exposes no required permissions.
  - Use an expect/actual boundary so platform logic lives in platform sources.
- Non-Goals:
  - Introducing new permission types not used by the app.
  - Reworking existing permission flows outside the settings screen.
  - Adding platform-specific UI beyond the shared screen.

## Decisions
- Decision: Add a `PermissionInfo` model in commonMain with id, title, description, and action type (Request/OpenSettings/None).
- Decision: Define a `PermissionStatus` enum (Granted/Denied/NotDetermined/Unsupported).
- Decision: Add `expect object PlatformPermissionProvider` with `permissions()`, `status(permission)`, and `request(permission, onResult)` APIs.
- Decision: The permissions list is provided per platform and contains only permissions required by current app features.
- Decision: The permissions settings entry renders only when `permissions()` is non-empty.

## Platform Notes
- Android: map permission ids to runtime permissions and special settings (e.g., MANAGE_EXTERNAL_STORAGE). For special permissions, `request` opens the appropriate settings screen. A permission host bound from `MainActivity` can own ActivityResult callbacks.
- iOS: expose notification permission; use `UNUserNotificationCenter` to check/request and update status.
- JS/Wasm: expose notification permission; use `Notification.permission` and `requestPermission()`.
- JVM: return an empty permission list.

## Risks / Trade-offs
- Android requests require an Activity context; mitigate by binding a permission host from `MainActivity` and exposing it through the provider.
- Special permissions change in system settings; refresh status on app resume.

## Migration Plan
- Add the provider and settings screen without removing current startup permission prompts.
- Consider consolidating Android permission handling later once the settings screen is in use.

## Open Questions
- None.
